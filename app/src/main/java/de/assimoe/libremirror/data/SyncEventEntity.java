package de.assimoe.libremirror.data;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "sync_events",
        indices = {
                @Index(value = {"timestampMs"}),
                @Index(value = {"status"})
        }
)
public class SyncEventEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestampMs;
    public String status;
    public String message;
    public long durationMs;
}
