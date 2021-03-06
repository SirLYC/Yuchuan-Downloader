package com.lyc.downloader.db;

import android.os.Parcel;
import android.os.Parcelable;
import com.lyc.downloader.DownloadTask.DownloadState;
import org.greenrobot.greendao.DaoException;
import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Generated;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.NotNull;
import org.greenrobot.greendao.annotation.Property;
import org.greenrobot.greendao.annotation.ToMany;

import java.util.Date;
import java.util.List;

/**
 * Created by Liu Yuchuan on 2019/4/22.
 */
@Entity(
        indexes = {
                @org.greenrobot.greendao.annotation.Index(value = "url DESC")
        }
)
public class DownloadInfo implements Parcelable {
    @Id(autoincrement = true)
    private Long id;
    @NotNull
    private String url;
    @NotNull
    private String path;
    /**
     * Not that this field may be null.
     * It's recommended that use {@link com.lyc.downloader.utils.DownloadStringUtil#parseFilenameFromUrl(String)}
     * to parse filename from {@link #url}
     */
    private String filename;
    private boolean resumable;
    @DownloadState
    private int downloadItemState;
    @Property(nameInDb = "downloaded_size")
    private long downloadedSize;
    @Property(nameInDb = "total_size")
    private long totalSize;
    @Property(nameInDb = "last_modified")
    private String lastModified;
    @Property(nameInDb = "created_time")
    private Date createdTime;
    @Property(nameInDb = "finished_time")
    private Date finishedTime;
    public static final Creator<DownloadInfo> CREATOR = new Creator<DownloadInfo>() {
        @Override
        public DownloadInfo createFromParcel(Parcel in) {
            return new DownloadInfo(in);
        }

        @Override
        public DownloadInfo[] newArray(int size) {
            return new DownloadInfo[size];
        }
    };
    @ToMany(referencedJoinProperty = "downloadInfoId")
    private List<DownloadThreadInfo> downloadThreadInfos;
    @Property(nameInDb = "error_msg")
    private Integer errorCode;
    /**
     * Used to resolve relations
     */
    @Generated(hash = 2040040024)
    private transient DaoSession daoSession;
    /**
     * Used for active entity operations.
     */
    @Generated(hash = 1465593784)
    private transient DownloadInfoDao myDao;

    protected DownloadInfo(Parcel in) {
        readFromParcel(in);
    }

    @Generated(hash = 459431950)
    public DownloadInfo(Long id, @NotNull String url, @NotNull String path, String filename,
                        boolean resumable, int downloadItemState, long downloadedSize, long totalSize,
                        String lastModified, Date createdTime, Date finishedTime, Integer errorCode) {
        this.id = id;
        this.url = url;
        this.path = path;
        this.filename = filename;
        this.resumable = resumable;
        this.downloadItemState = downloadItemState;
        this.downloadedSize = downloadedSize;
        this.totalSize = totalSize;
        this.lastModified = lastModified;
        this.createdTime = createdTime;
        this.finishedTime = finishedTime;
        this.errorCode = errorCode;
    }

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUrl() {
        return this.url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getPath() {
        return this.path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Generated(hash = 327086747)
    public DownloadInfo() {
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (id == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeLong(id);
        }
        dest.writeString(url);
        dest.writeString(path);
        dest.writeString(filename);
        dest.writeByte((byte) (resumable ? 1 : 0));
        dest.writeInt(downloadItemState);
        dest.writeLong(downloadedSize);
        dest.writeLong(totalSize);
        dest.writeString(lastModified);
        dest.writeLong(createdTime.getTime());
        if (finishedTime == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeLong(finishedTime.getTime());
        }
        if (errorCode == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeInt(errorCode);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public String getFilename() {
        return this.filename;
    }

    public int getDownloadItemState() {
        return this.downloadItemState;
    }

    public void setDownloadItemState(int downloadItemState) {
        this.downloadItemState = downloadItemState;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public boolean getResumable() {
        return this.resumable;
    }

    public void setResumable(boolean resumable) {
        this.resumable = resumable;
    }

    public long getDownloadedSize() {
        return this.downloadedSize;
    }

    public void setDownloadedSize(long downloadedSize) {
        this.downloadedSize = downloadedSize;
    }

    public long getTotalSize() {
        return this.totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public String getLastModified() {
        return this.lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public Date getCreatedTime() {
        return this.createdTime;
    }

    public void setCreatedTime(Date createdTime) {
        this.createdTime = createdTime;
    }

    public Date getFinishedTime() {
        return this.finishedTime;
    }

    /**
     * To-many relationship, resolved on first access (and after reset).
     * Changes to to-many relations are not persisted, make changes to the target entity.
     */
    @Generated(hash = 1506040892)
    public List<DownloadThreadInfo> getDownloadThreadInfos() {
        if (downloadThreadInfos == null) {
            final DaoSession daoSession = this.daoSession;
            if (daoSession == null) {
                throw new DaoException("Entity is detached from DAO context");
            }
            DownloadThreadInfoDao targetDao = daoSession.getDownloadThreadInfoDao();
            List<DownloadThreadInfo> downloadThreadInfosNew = targetDao
                    ._queryDownloadInfo_DownloadThreadInfos(id);
            synchronized (this) {
                if (downloadThreadInfos == null) {
                    downloadThreadInfos = downloadThreadInfosNew;
                }
            }
        }
        return downloadThreadInfos;
    }

    /**
     * Resets a to-many relationship, making the next get call to query for a fresh result.
     */
    @Generated(hash = 1600557048)
    public synchronized void resetDownloadThreadInfos() {
        downloadThreadInfos = null;
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#delete(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 128553479)
    public void delete() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.delete(this);
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#refresh(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 1942392019)
    public void refresh() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.refresh(this);
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#update(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 713229351)
    public void update() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.update(this);
    }

    public void setFinishedTime(Date finishedTime) {
        this.finishedTime = finishedTime;
    }

    public Integer getErrorCode() {
        return this.errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    public void readFromParcel(Parcel in) {
        if (in.readByte() == 0) {
            id = null;
        } else {
            id = in.readLong();
        }
        url = in.readString();
        path = in.readString();
        filename = in.readString();
        resumable = in.readByte() != 0;
        downloadItemState = in.readInt();
        downloadedSize = in.readLong();
        totalSize = in.readLong();
        lastModified = in.readString();
        createdTime = new Date(in.readLong());
        if (in.readByte() == 0) {
            finishedTime = null;
        } else {
            finishedTime = new Date(in.readLong());
        }
        if (in.readByte() == 0) {
            errorCode = null;
        } else {
            errorCode = in.readInt();
        }
    }

    /** called by internal mechanisms, do not call yourself. */
    @Generated(hash = 17038220)
    public void __setDaoSession(DaoSession daoSession) {
        this.daoSession = daoSession;
        myDao = daoSession != null ? daoSession.getDownloadInfoDao() : null;
    }
}
