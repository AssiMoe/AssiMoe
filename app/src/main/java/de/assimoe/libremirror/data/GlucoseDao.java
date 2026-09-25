package de.assimoe.libremirror.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface GlucoseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<GlucoseReadingEntity> readings);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(GlucoseReadingEntity reading);

    @Query("SELECT * FROM glucose_readings ORDER BY timestampMs DESC LIMIT 1")
    GlucoseReadingEntity latest();

    @Query("SELECT * FROM glucose_readings ORDER BY timestampMs DESC LIMIT :limit")
    List<GlucoseReadingEntity> recentDescending(int limit);

    @Query(
            "SELECT * FROM glucose_readings "
                    + "WHERE timestampMs >= :fromMs AND timestampMs <= :toMs "
                    + "ORDER BY timestampMs ASC"
    )
    List<GlucoseReadingEntity> between(long fromMs, long toMs);

    @Query("SELECT COUNT(*) FROM glucose_readings")
    int count();

    @Query("DELETE FROM glucose_readings WHERE timestampMs < :beforeMs")
    void deleteOlderThan(long beforeMs);
}
