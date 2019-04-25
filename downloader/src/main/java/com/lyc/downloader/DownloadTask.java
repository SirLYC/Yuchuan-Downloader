package com.lyc.downloader;

import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.WorkerThread;

import com.lyc.downloader.db.CustomerHeader;
import com.lyc.downloader.db.CustomerHeaderDao;
import com.lyc.downloader.db.DaoSession;
import com.lyc.downloader.db.DownloadInfo;
import com.lyc.downloader.db.DownloadInfoDao;
import com.lyc.downloader.db.DownloadThreadInfo;
import com.lyc.downloader.db.DownloadThreadInfoDao;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Request.Builder;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * @author liuyuchuan
 * @date 2019/4/1
 * @email kevinliu.sir@qq.com
 */
// TODO 2019/4/10 @liuyuchuan: 处理服务器文件发生改变的情况
public class DownloadTask {

    public static final int PENDING = 0;
    public static final int PREPARING = 1;
    public static final int RUNNING = 2;
    public static final int PAUSING = 3;
    public static final int PAUSED = 4;
    public static final int FINISH = 5;
    public static final int WAITING = 6;
    public static final int CANCELED = 7;
    public static final int ERROR = 8;
    // this state should not be resumed
    public static final int FATAL_ERROR = 9;

    // in bytes
    private static final int MAX_BUFFER = 1 << 16;
    private static final int MIN_BUFFER = 4 * (2 << 10);

    private static final int MAX_DOWNLOAD_THREAD = 4;

    private final String url;
    private final String path;
    private final Map<String, String> customerHeaders;
    private final AtomicInteger state = new AtomicInteger(PENDING);
    private final DownloadError downloadError = DownloadError.instance();
    private File downloadFile;
    private int downloadThreadCount = 1;
    private String fileName;
    private OkHttpClient client;
    private Request pivotRequest;
    private long totalLen;
    private DownloadRunnable[] downloadRunnables;
    private WriteToDiskRunnable[] writeToDiskRunnables;
    private Set<Thread> threads = new HashSet<>();
    private final DownloadInfo downloadInfo;
    private final DownloadListener downloadListener;
    private DownloadThreadInfo[] downloadThreadInfos;
    private Call pivotCall;
    private long bufferTimeout = 1;
    private AtomicLong downloadSize = new AtomicLong(0);
    private Request baseRequest;
    private boolean resumable;
    private boolean resuming;
    private AtomicInteger leftActiveThreadCount = new AtomicInteger();
    private Semaphore semaphore;

    /**
     * only used by {@link DownloadManager}
     */
    @WorkerThread
    DownloadTask(DownloadInfo downloadInfo, OkHttpClient client, DownloadListener downloadListener) {
        this.downloadInfo = downloadInfo;
        this.downloadListener = downloadListener;
        this.url = downloadInfo.getUrl();
        this.path = downloadInfo.getPath();
        this.client = client;
        switch (downloadInfo.getDownloadItemState()) {
            case PENDING:
            case PREPARING:
            case RUNNING:
                state.set(PENDING);
                break;
            case PAUSING:
            case PAUSED:
                state.set(PAUSED);
                break;
            case FINISH:
                state.set(FINISH);
                break;
            case WAITING:
                state.set(WAITING);
                break;
            case CANCELED:
                state.set(CANCELED);
                break;
            case ERROR:
                state.set(ERROR);
                break;
            case FATAL_ERROR:
                state.set(FATAL_ERROR);
                break;
        }
        resumable = downloadInfo.getResumable();
        totalLen = downloadInfo.getTotalSize();
        downloadSize.set(downloadInfo.getDownloadedSize());
        downloadInfo.setDownloadItemState(state.get());
        Map<String, String> headers = new HashMap<>();
        for (CustomerHeader customerHeader : downloadInfo.getCustomerHeaders()) {
            headers.put(customerHeader.getKey(), customerHeader.getValue());
        }
        this.customerHeaders = headers;
        setup();
    }

    private void setup() {
        downloadFile = new File(path + Constants.TMP_FILE_SUFFIX);
        int index = path.lastIndexOf('/');
        if (index == -1) {
            throw new IllegalArgumentException("path should be absolute path");
        }

        fileName = path.substring(index + 1);
        if (fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("filename should not be empty");
        }
        downloadFile = new File(path + Constants.TMP_FILE_SUFFIX);
    }

    public @DownloadState
    int getState() {
        return state.get();
    }

    private boolean prepare() {
        File targetFile = new File(path);
        if (targetFile.exists() && !targetFile.delete()) {
            reportError(DownloadError.ERROR_FILE_EXITS);
            return false;
        }

        File dir = targetFile.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            reportError(DownloadError.ERROR_CREATE_DIR);
            return false;
        }

        if (downloadInfo.getId() == null) {
            if (!persistDownloadInfo()) {
                reportError(DownloadError.ERROR_CREATE_TASK);
                return false;
            }
        }

        Builder builder = new Builder();
        if (customerHeaders != null && !customerHeaders.isEmpty()) {
            Set<String> keys = customerHeaders.keySet();
            for (String s : keys) {
                String value = customerHeaders.get(s);
                if (value != null) {
                    builder.addHeader(s, value);
                }
            }
        }
        builder.url(url);
        baseRequest = builder.build();
        // returns 206 if it supports resume or 200 not support
        pivotRequest = new Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=0-")
                .build();
        return true;
    }

    private boolean persistDownloadInfo() {
        DaoSession daoSession = DownloadManager.instance().daoSession;
        try {
            daoSession.runInTx(() -> {
                DownloadInfoDao downloadInfoDao = daoSession.getDownloadInfoDao();
                CustomerHeaderDao customerHeaderDao = daoSession.getCustomerHeaderDao();
                long id = downloadInfoDao.insert(downloadInfo);
                if (id != -1) {
                    for (String key : customerHeaders.keySet()) {
                        String value = customerHeaders.get(key);
                        if (value != null) {
                            customerHeaderDao.insert(new CustomerHeader(null, id, key, value));
                        }
                    }
                }
            });
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
            return false;
        }
        Long id = downloadInfo.getId();

        return id != null && id != -1;
    }

    // resume or start
    void start(boolean restart) {
        int s = state.get();
        if (restart) {
            if (s != RUNNING && s != PAUSING && s != CANCELED && state.compareAndSet(s, PENDING)) {
                stateChange();
                DownloadExecutors.io.execute(this::execute);
            }
        } else {
            if (s != RUNNING && s != PAUSING && s != CANCELED && s != FINISH) {
                DownloadExecutors.io.execute(this::execute);
            }
        }
    }

    @WorkerThread
    private void execute() {
        if (BuildConfig.DEBUG) {
            Log.d(Constants.DEBUG_TAG, "execute: " + url);
        }

        int s = state.get();
        if (state.compareAndSet(s, PREPARING)) {
            stateChange();
            downloadListener.onPreparing(downloadInfo.getId());
        } else if (handlePauseOrCancel()) {
            return;
        } else {
            Log.w("DownloadTask", "failed to prepare? state = " + state.get());
            reportError(DownloadError.ERROR_DOWNLOAD_FAIL);
            return;
        }

        // 如果是从暂停或者错误中恢复，不需要再重试
        if (!resuming) {
            if (!prepare()) {
                handlePauseOrCancel();
                return;
            }

            if (!initDownloadInfo()) {
                handlePauseOrCancel();
                return;
            }
        }
        resuming = false;

        if (state.compareAndSet(PREPARING, RUNNING)) {
            stateChange();
            downloadListener.onDownloadStart(downloadInfo.getId());
        } else {
            handlePauseOrCancel();
            return;
        }

        semaphore = new Semaphore(-1);
        for (WriteToDiskRunnable writeToDiskRunnable : writeToDiskRunnables) {
            Thread t = new Thread(writeToDiskRunnable);
            threads.add(t);
            t.start();
        }

        for (DownloadRunnable downloadRunnable : downloadRunnables) {
            Thread t = new Thread(downloadRunnable);
            threads.add(t);
            t.start();
        }

        leftActiveThreadCount.set(downloadThreadCount * 2);
        new ProgressWatcher().run();

        s = state.get();
        if (s == RUNNING) {
            // pause will not work now
            if (!downloadFile.renameTo(new File(path))) {
                reportError(DownloadError.ERROR_WRITE_FILE);
                Log.e("DownloadTask", "Task#" + downloadInfo.getId() + " cannot rename file " + downloadFile.getAbsolutePath() + " to " + path);
                return;
            }

            s = state.get();
            while (s == RUNNING || s == PAUSING || s == PAUSED) {
                if (state.compareAndSet(s, FINISH)) {
                    stateChange();
                    downloadListener.onDownloadFinished(downloadInfo.getId());
                    return;
                } else {
                    s = state.get();
                }
            }
        } else if (!handlePauseOrCancel()) {
            Log.e("DownloadTask", "Task#" + downloadInfo.getId() + " has a wrong state: " + state.get());
        }
    }

    private boolean handlePauseOrCancel() {
        int s = state.get();
        if (s == PAUSING && state.compareAndSet(PAUSING, PAUSED)) {
            stateChange();
            downloadListener.onDownloadPaused(downloadInfo.getId());
            preparedForResuming();
            return true;
        } else return s == CANCELED;
    }

    private void preparedForResuming() {
        int s = state.get();
        resuming = (s == PAUSED || s == ERROR) && resumable && downloadThreadInfos != null;
    }

    @WorkerThread
    private void stateChange() {
        int newState = state.get();

        if (downloadInfo != null && downloadInfo.getDownloadItemState() != newState) {
            downloadInfo.setDownloadItemState(newState);
            try {
                DownloadManager.instance().daoSession.getDownloadInfoDao().save(downloadInfo);
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
            }
        }
    }

    void toWait() {
        int s = state.get();
        if (s == PENDING || s == PAUSED || s == ERROR || s == FATAL_ERROR) {
            preparedForResuming();
            if (state.compareAndSet(s, WAITING)) {
                stateChange();
                downloadListener.onDownloadTaskWait(downloadInfo.getId());
            }
        }
    }

    private boolean initDownloadInfo() {
        int bufferSize = MAX_BUFFER;
        // try to recover from last download
        try {
            List<DownloadThreadInfo> downloadThreadInfoList = downloadInfo.getDownloadThreadInfos();
            if (!downloadThreadInfoList.isEmpty()) {
                downloadThreadInfos = new DownloadThreadInfo[downloadThreadInfoList.size()];
                for (DownloadThreadInfo downloadThreadInfo : downloadThreadInfoList) {
                    downloadThreadInfos[downloadThreadInfo.getTid()] = downloadThreadInfo;
                }
                long tmp = 0;
                for (DownloadThreadInfo downloadThreadInfo : downloadThreadInfos) {
                    if (downloadThreadInfo.getTotalSize() != -1) {
                        tmp += downloadThreadInfo.getDownloadedSize();
                    } else {
                        downloadSize.set(0);
                        tmp = -1;
                        if (downloadThreadInfos.length != 1) {
                            DownloadManager.instance().daoSession.getDownloadThreadInfoDao().deleteInTx(downloadThreadInfos);
                            downloadThreadInfos = null;
                            downloadInfo.resetDownloadThreadInfos();
                        }
                        break;
                    }
                }

                downloadSize.set(tmp);
                if (downloadInfo.getDownloadedSize() != tmp) {
                    downloadInfo.setDownloadedSize(tmp);
                    try {
                        DownloadManager.instance().daoSession.getDownloadInfoDao().save(downloadInfo);
                    } catch (Exception e) {
                        if (BuildConfig.DEBUG) {
                            e.printStackTrace();
                        }
                    }
                }

                if (downloadThreadInfos != null) {
                    bufferSize = chooseBufferSize(totalLen);
                    downloadThreadCount = downloadThreadInfos.length;
                }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
            if (downloadThreadInfos != null) {
                DownloadManager.instance().daoSession.getDownloadThreadInfoDao().deleteInTx(downloadThreadInfos);
                downloadThreadInfos = null;
                downloadInfo.resetDownloadThreadInfos();
            }
            downloadSize.set(0);
        }

        if (downloadThreadInfos == null) {
            downloadSize.set(0);
            // fetch last download info if possible
            Response response;
            try {
                pivotCall = client.newCall(pivotRequest);
                response = pivotCall.execute();
            } catch (IOException e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
                if (pivotCall != null) {
                    pivotCall.cancel();
                    pivotCall = null;
                }
                reportError(DownloadError.ERROR_NETWORK);
                return false;
            }

            int s = state.get();
            if (s != PREPARING) {
                return false;
            }

            pivotCall = null;
            resumable = response.code() == 206 || "bytes".equalsIgnoreCase(response.header("Accept-Ranges"));
            if (downloadInfo.getResumable() != resumable) {
                downloadInfo.setResumable(resumable);
                try {
                    DownloadManager.instance().daoSession.getDownloadInfoDao().save(downloadInfo);
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) {
                        e.printStackTrace();
                    }
                }
            }
            if (resumable) {
                ResponseBody body = response.body();
                if (body == null) {
                    throw new NullPointerException("nothing available for download");
                }
                // multi thread with resuming
                totalLen = body.contentLength();
                if (totalLen == -1) {
                    String contentRange = response.header("Content-Rang");
                    if (contentRange != null) {
                        int index = contentRange.lastIndexOf('/');
                        if (index != -1) {
                            String len = contentRange.substring(index);
                            try {
                                totalLen = Long.parseLong(len);
                            } catch (NumberFormatException e) {
                                totalLen = -1;
                            }
                        }
                    }
                }

                bufferSize = chooseBufferSize(totalLen);

                // 选择合适的线程数，每个线程能多做事
                // 如果每个线程做的事太少了，减少线程数
                int threadCnt;
                for (threadCnt = MAX_DOWNLOAD_THREAD; threadCnt >= 1; threadCnt--) {
                    int readCnt = 100 / threadCnt;
                    if (readCnt * bufferSize < totalLen) {
                        break;
                    }
                }
                downloadThreadCount = threadCnt;
            } else if (response.isSuccessful()) {
                // single thread without resuming
                downloadThreadCount = 1;
                downloadInfo.setResumable(false);
                try {
                    downloadInfo.update();
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) {
                        e.printStackTrace();
                    }
                }
            } else {
                reportError(DownloadError.ERROR_NETWORK);
                return false;
            }

            downloadThreadInfos = new DownloadThreadInfo[downloadThreadCount];
            if (totalLen == -1) {
                // cannot resume
                // downloadTreadCount == 1
                downloadThreadInfos[0] = new DownloadThreadInfo(
                        null, 0, 0,
                        0, -1, downloadInfo.getId()
                );
            } else {
                long downloadLen = totalLen / downloadThreadCount;
                long lenSum = 0, last;
                for (int i = 0; i < downloadThreadCount; i++) {
                    last = lenSum;
                    lenSum = Math.min(totalLen, lenSum + downloadLen);
                    downloadThreadInfos[i] = new DownloadThreadInfo(
                            null, i, i * downloadLen,
                            0, lenSum - last, downloadInfo.getId());
                }
            }
        }

        if (!preAllocation()) {
            reportError(DownloadError.ERROR_SPACE_FULL);
            return false;
        }

        if (downloadInfo.getTotalSize() != totalLen) {
            downloadInfo.setTotalSize(totalLen);
            try {
                DownloadManager.instance().daoSession.getDownloadInfoDao().save(downloadInfo);
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
            }
        }

        writeToDiskRunnables = new WriteToDiskRunnable[downloadThreadCount];
        downloadRunnables = new DownloadRunnable[downloadThreadCount];
        for (int i = 0; i < downloadThreadInfos.length; i++) {
            DownloadBuffer downloadBuffer = new DownloadBuffer(bufferSize);
            downloadRunnables[i] = new DownloadRunnable(
                    downloadThreadInfos[i].getStartPosition(),
                    downloadThreadInfos[i].getDownloadedSize(),
                    downloadThreadInfos[i].getTotalSize(),
                    downloadBuffer, downloadThreadInfos[i].getTid()
            );
            writeToDiskRunnables[i] = new WriteToDiskRunnable(
                    downloadThreadInfos[i].getStartPosition(),
                    downloadThreadInfos[i].getTotalSize(),
                    downloadThreadInfos[i].getTid(),
                    downloadBuffer);
        }

        return true;
    }

    private int chooseBufferSize(long totalLen) {
        if (totalLen == -1) {
            return MAX_BUFFER;
        }
        // 选择bufferSize
        // 尽可能让进度条能多动，但下载缓冲区不至于太小或太大
        long bufferSize = totalLen / 100L;
        if (bufferSize < MIN_BUFFER) bufferSize = MIN_BUFFER;
        else bufferSize = MAX_BUFFER;

        return (int) bufferSize;
    }

    void pause() {
        int s = state.get();
        if (((s == RUNNING || s == PREPARING || s == PENDING)
                && state.compareAndSet(s, PAUSING))
                || (s == WAITING && state.compareAndSet(s, PAUSED))
        ) {
            stateChange();
            if (downloadRunnables != null) {
                for (DownloadRunnable downloadRunnable : downloadRunnables) {
                    downloadRunnable.cancelRequest();
                }

                Iterator<Thread> iterator = threads.iterator();
                while (iterator.hasNext()) {
                    Thread t = iterator.next();
                    if (!t.isInterrupted()) {
                        t.interrupt();
                    }
                    iterator.remove();
                }
            }
            s = state.get();
            if (s == PAUSING) {
                downloadListener.onDownloadPausing(downloadInfo.getId());
            } else if (s == PAUSED) {
                downloadListener.onDownloadPaused(downloadInfo.getId());
            }
        }
    }

    void cancel() {
        int s = state.get();

        if (s != CANCELED) {
            state.set(CANCELED);
            stateChange();
            if (downloadRunnables != null) {
                for (DownloadRunnable downloadRunnable : downloadRunnables) {
                    downloadRunnable.cancelRequest();
                }

                Iterator<Thread> iterator = threads.iterator();
                while (iterator.hasNext()) {
                    Thread t = iterator.next();
                    if (!t.isInterrupted()) {
                        t.interrupt();
                    }
                    iterator.remove();
                }
            }
            downloadListener.onDownloadCanceled(downloadInfo.getId());
        }
    }

    private void reportError(int code) {
        int s = state.get();
        if (s == ERROR) {
            return;
        }

        if (s == FATAL_ERROR) {
            return;
        }

        boolean fatal = downloadError.isFatal(code);
        Log.e("DownloadTask", "error: " + downloadError.translate(code) + "; isFatal: " + fatal);
        if (!fatal && state.compareAndSet(s, ERROR)) {
            preparedForResuming();
            stateChange();
            downloadListener.onDownloadError(downloadInfo.getId(), downloadError.translate(code), false);
        } else if (fatal && state.compareAndSet(s, FATAL_ERROR)) {
            DownloadManager.instance().daoSession
                    .getDownloadThreadInfoDao().deleteInTx(downloadInfo.getDownloadThreadInfos());
            downloadInfo.getDownloadThreadInfos().clear();
            downloadThreadInfos = null;
            stateChange();
            downloadListener.onDownloadError(downloadInfo.getId(), downloadError.translate(code), true);
        }
    }

    private boolean preAllocation() {
        if (downloadFile == null) {
            reportError(DownloadError.ERROR_WRITE_FILE);
            return false;
        }
        if (downloadFile.exists() || totalLen == -1) {
            return true;
        }

        try (RandomAccessFile raf = new RandomAccessFile(downloadFile, "rw")) {
            raf.setLength(totalLen);
        } catch (FileNotFoundException e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
            return false;
        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
            return false;
        }

        return true;
    }

    @IntDef({PENDING, PREPARING, RUNNING, PAUSING, PAUSED, FINISH, WAITING, CANCELED, ERROR, FATAL_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DownloadState {
    }

    private class DownloadRunnable implements Runnable {
        private long startPos;
        private long contentLen;
        private long threadDownloadedSize;
        private Call call;
        private final int retryCount = 2;
        private final DownloadBuffer downloadBuffer;
        /**
         * @see DownloadThreadInfo#getTid()
         */
        private final int id;

        DownloadRunnable(long startPos, long threadDownloadedSize, long contentLen, DownloadBuffer downloadBuffer, int id) {
            this.startPos = startPos;
            this.threadDownloadedSize = threadDownloadedSize;
            this.contentLen = contentLen;
            this.downloadBuffer = downloadBuffer;
            this.id = id;
        }

        private void cancelRequest() {
            if (call != null) {
                call.cancel();
            }
        }

        @Override
        public void run() {
            try {
                innerRun();
            } finally {
                leftActiveThreadCount.decrementAndGet();
            }
        }

        private void innerRun() {
            InputStream is = null;
            long currentPos = startPos + threadDownloadedSize;

            if (currentPos < startPos + contentLen || contentLen == -1) {
                Request request;
                if (contentLen > 0 && resumable) {
                    request = baseRequest.newBuilder().addHeader("Range", "bytes=" + currentPos + "-" + (startPos + contentLen - 1)).build();
                } else if (resumable) {
                    request = baseRequest.newBuilder().addHeader("Range", "bytes=" + currentPos + "-").build();
                } else {
                    currentPos = 0;
                    downloadSize.set(0);
                    request = baseRequest;
                }

                boolean success = false;
                int retryCount = this.retryCount;
                ResponseBody body = null;
                do {
                    int s = state.get();
                    if (s != RUNNING) {
                        return;
                    }
                    try {
                        call = client.newCall(request);
                        Response response = call.execute();
                        if (!response.isSuccessful() || ((body = response.body()) == null))
                            continue;
                        retryCount = 0;
                        success = true;
                    } catch (IOException e) {
                        if (BuildConfig.DEBUG) {
                            e.printStackTrace();
                        }
                        s = state.get();
                        if (s != RUNNING) {
                            return;
                        }
                    }
                } while (retryCount-- > 0);

                if (!success) {
                    reportError(DownloadError.ERROR_NETWORK);
                    return;
                }

                is = Objects.requireNonNull(body).byteStream();
            }

            int retryCount = this.retryCount;
            Segment segment;
            for (; ; ) {
                int s = state.get();
                if (s != RUNNING) {
                    return;
                }
                try {
                    segment = downloadBuffer.availableWriteSegment(bufferTimeout);
                } catch (InterruptedException e) {
                    continue;
                }

                // timeout
                if (segment == null) {
                    continue;
                }

                //-----------------------segment must return to buffer!--------------------------//

                int readSize = -1;

                try {
                    if (is != null) {
                        readSize = is.read(segment.buffer);
                    }
                    segment.startPos = currentPos;
                    segment.tid = this.id;
                    segment.readSize = readSize;
                    if (readSize > 0) {
                        threadDownloadedSize += segment.readSize;
                    }
                    downloadBuffer.enqueueReadSegment(segment);
                } catch (IOException e) {
                    s = state.get();
                    if (s != PAUSING && s != PAUSED && s != CANCELED && retryCount-- <= 0) {
                        reportError(DownloadError.ERROR_DOWNLOAD_FAIL);
                        if (BuildConfig.DEBUG) {
                            e.printStackTrace();
                        }
                    }
                    downloadBuffer.enqueueWriteSegment(segment);
                    continue;
                }

                if (readSize <= 0) {
                    break;
                }
            }
        }
    }

    private class WriteToDiskRunnable implements Runnable {
        private final int id;
        private long startPos;
        private long contentLen;
        private final int retryCount = 2;
        private final DownloadBuffer downloadBuffer;

        WriteToDiskRunnable(long startPos, long contentLen, int id, DownloadBuffer downloadBuffer) {
            this.startPos = startPos;
            this.contentLen = contentLen;
            this.id = id;
            this.downloadBuffer = downloadBuffer;
        }

        @Override
        public void run() {
            try {
                innerRun();
            } finally {
                leftActiveThreadCount.decrementAndGet();
            }
        }

        private void innerRun() {
            if (contentLen != -1 && downloadThreadInfos[id].getDownloadedSize() >= contentLen) {
                return;
            }
            int retryCount = this.retryCount;
            RandomAccessFile raf;

            for (; ; ) {
                try {
                    raf = new RandomAccessFile(downloadFile, "rw");
                    raf.seek(startPos + downloadThreadInfos[id].getDownloadedSize());
                    break;
                } catch (IOException e) {
                    if (retryCount > 0 && state.get() == RUNNING) {
                        retryCount--;
                        continue;
                    }
                    if (BuildConfig.DEBUG) {
                        e.printStackTrace();
                    }
                    reportError(DownloadError.ERROR_WRITE_FILE);
                    return;
                }
            }

            Segment segment;
            for (; ; ) {
                int s = state.get();
                if (s != RUNNING) {
                    return;
                }
                try {
                    segment = downloadBuffer.availableReadSegment(bufferTimeout);
                } catch (InterruptedException e) {
                    continue;
                }

                // timeout
                if (segment == null) {
                    continue;
                }

                //-----------------------segment must return to buffer!--------------------------//

                int writeSize = segment.readSize;
                if (writeSize > 0) {
                    try {
                        raf.write(segment.buffer, 0, writeSize);
                        downloadBuffer.enqueueWriteSegment(segment);
                        long downloadedSize = downloadThreadInfos[id].getDownloadedSize();
                        downloadThreadInfos[id].setDownloadedSize(downloadedSize + writeSize);
                        downloadSize.addAndGet(writeSize);
                        // inform watcher to update progress
                        semaphore.release();
                    } catch (IOException e) {
                        s = state.get();
                        if (s != PAUSING && s != PAUSED && s != CANCELED && retryCount-- <= 0) {
                            reportError(DownloadError.ERROR_DOWNLOAD_FAIL);
                            if (BuildConfig.DEBUG) {
                                e.printStackTrace();
                            }
                            return;
                        }
                        downloadBuffer.enqueueReadSegment(segment);
                    }
                } else {
                    break;
                }
            }
        }
    }

    private class ProgressWatcher implements Runnable {
        // ms
        private long watchInterval = 16;
        // 16ms min
        private final long minWatchInterval = 16;
        // 1s max
        private final long maxWatchInterval = 1000;
        private long lastDownloadSize;
        private long lastTimeNano;

        @Override
        public void run() {
            lastTimeNano = System.nanoTime();
            lastDownloadSize = downloadSize.get();
            while (true) {
                if (checkEnd()) {
                    return;
                }

                try {
                    semaphore.tryAcquire(watchInterval, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (checkEnd()) {
                        return;
                    }
                    continue;
                }

                long time = System.nanoTime();
                long delta = time - lastTimeNano;
                if (delta < watchInterval * 1000000) {
                    continue;
                }

                if (checkEnd()) {
                    return;
                }

                long current = downloadSize.get();
                long downloaded = current - lastDownloadSize;
                // try to avoid 0B/s...
                // git it more time to do it
                if (downloaded < 1 && watchInterval < maxWatchInterval) {
                    watchInterval = Math.min(watchInterval * 2, maxWatchInterval);
                    continue;
                }

                updateDownloadInfo();
                double bps = downloaded / (delta / 1e9);
                downloadListener.onProgressUpdate(downloadInfo.getId(), totalLen, current, bps);
                lastDownloadSize = current;
                lastTimeNano = time;
                if (downloaded > 1) {
                    watchInterval = Math.max(minWatchInterval, watchInterval - minWatchInterval);
                }
            }
        }
    }

    private boolean checkEnd() {
        boolean result = leftActiveThreadCount.get() <= 0 || state.get() != RUNNING;
        if (result) {
            updateDownloadInfo();
        }
        return result;
    }

    private void updateDownloadInfo() {
        DaoSession daoSession = DownloadManager.instance().daoSession;
        downloadInfo.setDownloadedSize(downloadSize.get());
        try {
            daoSession.callInTx(() -> {
                DownloadInfoDao downloadInfoDao = daoSession.getDownloadInfoDao();
                DownloadThreadInfoDao downloadThreadInfoDao = daoSession.getDownloadThreadInfoDao();
                downloadInfoDao.save(downloadInfo);
                for (DownloadThreadInfo downloadThreadInfo : downloadThreadInfos) {
                    downloadThreadInfoDao.save(downloadThreadInfo);
                }
                return null;
            });
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
        }
    }
}
