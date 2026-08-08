package dev.pschmitt.nyetbox.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private fun SupportSQLiteDatabase.execCreateTable(sql: String) {
    execSQL(sql.trimIndent())
}

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `netbox_models` (
                    `appKey` TEXT NOT NULL,
                    `appLabel` TEXT NOT NULL,
                    `modelKey` TEXT NOT NULL,
                    `modelLabel` TEXT NOT NULL,
                    `endpointPath` TEXT NOT NULL,
                    PRIMARY KEY(`appKey`, `modelKey`)
                )
                """
            )
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `netbox_objects` (
                    `endpointPath` TEXT NOT NULL,
                    `id` INTEGER NOT NULL,
                    `display` TEXT NOT NULL,
                    `secondaryLine` TEXT,
                    `json` TEXT NOT NULL,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`endpointPath`, `id`)
                )
                """
            )
        }
    }

val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `device_types` (
                    `id` INTEGER NOT NULL,
                    `model` TEXT,
                    `frontImageUrl` TEXT,
                    `rearImageUrl` TEXT,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """
            )
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `image_attachments` (
                    `id` INTEGER NOT NULL,
                    `objectType` TEXT NOT NULL,
                    `objectId` INTEGER NOT NULL,
                    `name` TEXT,
                    `imageUrl` TEXT,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """
            )
        }
    }

val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `bookmarks` (
                    `id` INTEGER NOT NULL,
                    `display` TEXT NOT NULL,
                    `objectType` TEXT NOT NULL,
                    `targetEndpointPath` TEXT,
                    `targetId` INTEGER,
                    `created` TEXT NOT NULL,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """
            )
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `object_changes` (
                    `id` INTEGER NOT NULL,
                    `time` TEXT NOT NULL,
                    `userDisplay` TEXT NOT NULL,
                    `actionValue` TEXT NOT NULL,
                    `actionLabel` TEXT NOT NULL,
                    `objectRepr` TEXT NOT NULL,
                    `targetEndpointPath` TEXT,
                    `targetId` INTEGER,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """
            )
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `dashboard_stats` (
                    `endpointPath` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `count` INTEGER NOT NULL,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`endpointPath`)
                )
                """
            )
        }
    }

val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `image_attachments` ADD COLUMN `display` TEXT")
            db.execSQL("ALTER TABLE `image_attachments` ADD COLUMN `description` TEXT")
            db.execSQL("ALTER TABLE `image_attachments` ADD COLUMN `imageHeight` INTEGER")
            db.execSQL("ALTER TABLE `image_attachments` ADD COLUMN `imageWidth` INTEGER")
            db.execSQL("ALTER TABLE `image_attachments` ADD COLUMN `created` TEXT")
            db.execSQL("ALTER TABLE `image_attachments` ADD COLUMN `lastUpdated` TEXT")
        }
    }

val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `custom_fields` (
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`name`)
                )
                """
            )
        }
    }

val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `pending_edits` (
                    `endpointPath` TEXT NOT NULL,
                    `id` INTEGER NOT NULL,
                    `baseJson` TEXT NOT NULL,
                    `localJson` TEXT NOT NULL,
                    `patchJson` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `serverJson` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`endpointPath`, `id`)
                )
                """
            )
        }
    }

val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `recent_visits` (
                    `endpointPath` TEXT NOT NULL,
                    `id` INTEGER NOT NULL,
                    `display` TEXT NOT NULL,
                    `secondaryLine` TEXT,
                    `visitedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`endpointPath`, `id`)
                )
                """
            )
        }
    }

val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `custom_fields` ADD COLUMN `label` TEXT")
            db.execSQL("ALTER TABLE `custom_fields` ADD COLUMN `groupName` TEXT")
            db.execSQL("ALTER TABLE `custom_fields` ADD COLUMN `weight` INTEGER NOT NULL DEFAULT 0")
        }
    }

val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) = Unit
    }

val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `custom_fields` ADD COLUMN `objectTypes` TEXT")
            db.execSQL("ALTER TABLE `custom_fields` ADD COLUMN `choiceSetUrl` TEXT")
        }
    }

val MIGRATION_11_12 =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `rack_elevation` (
                    `rackId` INTEGER NOT NULL,
                    `face` TEXT NOT NULL,
                    `slotName` TEXT NOT NULL,
                    `position` REAL NOT NULL,
                    `deviceId` INTEGER,
                    `deviceDisplay` TEXT,
                    `occupied` INTEGER NOT NULL,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`rackId`, `face`, `slotName`)
                )
                """
            )
        }
    }

val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `devices` ADD COLUMN `customFieldsJson` TEXT")
            db.execSQL("ALTER TABLE `devices` ADD COLUMN `primaryIpId` INTEGER")
        }
    }

val MIGRATION_13_14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) = Unit
    }

val MIGRATION_14_15 =
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `news_items` (
                    `guid` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `link` TEXT NOT NULL,
                    `summary` TEXT,
                    `publishedAt` INTEGER NOT NULL,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`guid`)
                )
                """
            )
        }
    }

val MIGRATION_15_16 =
    object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `netbox_objects` ADD COLUMN `lastUpdated` TEXT")
        }
    }

val MIGRATION_16_17 =
    object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execCreateTable(
                """
                CREATE TABLE IF NOT EXISTS `cable_trace_segments` (
                    `traceEndpointPath` TEXT NOT NULL,
                    `traceObjectId` INTEGER NOT NULL,
                    `segmentIndex` INTEGER NOT NULL,
                    `json` TEXT NOT NULL,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`traceEndpointPath`, `traceObjectId`, `segmentIndex`)
                )
                """
            )
        }
    }

val MIGRATION_17_18 =
    object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `netbox_objects` ADD COLUMN `relatedObjectId` INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_netbox_objects_endpointPath_relatedObjectId` " +
                    "ON `netbox_objects` (`endpointPath`, `relatedObjectId`)"
            )
        }
    }

val MIGRATION_18_19 =
    object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `netbox_objects` ADD COLUMN `frontImageUrl` TEXT")
        }
    }
