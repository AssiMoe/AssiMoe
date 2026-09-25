package de.assimoe.libremirror.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SyncEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SyncEventEntity event);

    @Query("SELECT * FROM sync_events ORDER BY timestampMs DESC LIMIT :limit")
    List<SyncEventEntity> recent(int limit);

    @Query("DELETE FROM sync_events WHERE timestampMs < :beforeMs")
    void deleteOlderThan(long beforeMs);
}
