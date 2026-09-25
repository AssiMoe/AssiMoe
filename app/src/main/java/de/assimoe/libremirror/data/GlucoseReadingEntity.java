package de.assimoe.libremirror.data;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "glucose_readings",
        indices = {
                @Index(value = {"timestampMs"}, unique = true),
                @Index(value = {"receivedAtMs"})
        }
)
public class GlucoseReadingEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestampMs;
    public double mgdl;
    public int trend;
    public String source;
    public long receivedAtMs;
}
