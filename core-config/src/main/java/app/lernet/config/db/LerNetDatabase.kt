package app.lernet.config.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        OutboundEntity::class,
        RuleNodeEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class LerNetDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun outboundDao(): OutboundDao

    abstract fun ruleNodeDao(): RuleNodeDao

    abstract fun groupDao(): GroupDao

    abstract fun groupMemberDao(): GroupMemberDao

    companion object {
        fun create(context: Context): LerNetDatabase =
            Room.databaseBuilder(context, LerNetDatabase::class.java, "lernet.db")
                .addMigrations(
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE profiles ADD COLUMN dnsPolicy TEXT NOT NULL DEFAULT 'UNDERLAY'",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rule_nodes ADD COLUMN pipeName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE groups ADD COLUMN sortIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN canvasLayout TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN canvasLayout TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rule_nodes ADD COLUMN blocksJson TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rule_nodes ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN autoFailover INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN sortIndex INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rule_nodes ADD COLUMN processesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN modeOverride TEXT")
            }
        }
    }
}
