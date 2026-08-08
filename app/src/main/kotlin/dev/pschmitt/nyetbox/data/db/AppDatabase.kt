package dev.pschmitt.nyetbox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities =
        [
            DeviceEntity::class,
            NetBoxModelEntity::class,
            NetBoxObjectEntity::class,
            DeviceTypeEntity::class,
            ImageAttachmentEntity::class,
            BookmarkEntity::class,
            ObjectChangeEntity::class,
            DashboardStatEntity::class,
            CustomFieldEntity::class,
            PendingEditEntity::class,
            RecentVisitEntity::class,
            RackElevationEntity::class,
            NewsItemEntity::class,
            CableTraceEntity::class,
        ],
    version = 19,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    abstract fun netBoxModelDao(): NetBoxModelDao

    abstract fun netBoxObjectDao(): NetBoxObjectDao

    abstract fun deviceTypeDao(): DeviceTypeDao

    abstract fun imageAttachmentDao(): ImageAttachmentDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun objectChangeDao(): ObjectChangeDao

    abstract fun dashboardStatDao(): DashboardStatDao

    abstract fun customFieldDao(): CustomFieldDao

    abstract fun pendingEditDao(): PendingEditDao

    abstract fun recentVisitDao(): RecentVisitDao

    abstract fun rackElevationDao(): RackElevationDao

    abstract fun newsDao(): NewsDao

    abstract fun cableTraceDao(): CableTraceDao
}
