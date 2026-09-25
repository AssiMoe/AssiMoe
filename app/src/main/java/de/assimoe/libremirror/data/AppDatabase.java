package de.assimoe.libremirror.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                GlucoseReadingEntity.class,
                SyncEventEntity.class
        },
        version = 1,
        exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract GlucoseDao glucoseDao();
    public abstract SyncEventDao syncEventDao();

    public static AppDatabase get(Context context) {
        AppDatabase current = INSTANCE;
        if (current != null) return current;

        synchronized (AppDatabase.class) {
            current = INSTANCE;

            if (current == null) {
                current = Room.databaseBuilder(
                                context.getApplicationContext(),
                                AppDatabase.class,
                                "libremirror.db"
                        )
                        .build();

                INSTANCE = current;
            }

            return current;
        }
    }
}
