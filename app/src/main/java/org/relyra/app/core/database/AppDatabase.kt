package org.relyra.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        IdentityEntity::class, ContactEntity::class, SecurityEventEntity::class,
        MessageEntity::class, ProcessedIdEntity::class, AttachmentEntity::class,
        RatchetEntity::class,
        GroupEntity::class, GroupMemberEntity::class, GroupMessageEntity::class, GroupOutboxEntity::class, GroupAttachmentEntity::class, CommunityEntity::class,
        ChatPrefEntity::class, VaultItemEntity::class, PreKeyEntity::class, EncryptedPacketEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun contactDao(): ContactDao
    abstract fun eventDao(): SecurityEventDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun ratchetDao(): RatchetDao
    abstract fun groupDao(): GroupDao
    abstract fun communityDao(): CommunityDao
    abstract fun chatPrefDao(): ChatPrefDao
    abstract fun vaultDao(): VaultDao
    abstract fun preKeyDao(): PreKeyDao
    abstract fun encryptedPacketDao(): EncryptedPacketDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (`messageId` TEXT NOT NULL, `chatId` TEXT NOT NULL, " +
                        "`outgoing` INTEGER NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, " +
                        "`readLocally` INTEGER NOT NULL, PRIMARY KEY(`messageId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_chatId_createdAt` ON `messages` (`chatId`, `createdAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `processed_ids` (`messageId` TEXT NOT NULL, " +
                        "`receivedAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `encKey` BLOB")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `nostrKey` BLOB")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attachments` (`messageId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`mime` TEXT NOT NULL, `size` INTEGER NOT NULL, `blobSize` INTEGER NOT NULL, " +
                        "`plainSha256` BLOB NOT NULL, `blobSha256` BLOB NOT NULL, `fileKey` BLOB NOT NULL, " +
                        "`serverUrl` TEXT NOT NULL, `localPath` TEXT, `phase` TEXT NOT NULL, PRIMARY KEY(`messageId`))"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_sessions` (`peerId` TEXT NOT NULL, `state` BLOB NOT NULL, " +
                        "PRIMARY KEY(`peerId`))"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_groups` (`groupId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `invitedBy` TEXT NOT NULL, PRIMARY KEY(`groupId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_members` (`groupId` TEXT NOT NULL, `memberId` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, PRIMARY KEY(`groupId`, `memberId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_messages` (`messageId` TEXT NOT NULL, `groupId` TEXT NOT NULL, " +
                        "`senderId` TEXT NOT NULL, `outgoing` INTEGER NOT NULL, `body` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `readLocally` INTEGER NOT NULL, PRIMARY KEY(`messageId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_outbox` (`id` TEXT NOT NULL, `groupId` TEXT NOT NULL, " +
                        "`memberId` TEXT NOT NULL, `baseMessageId` TEXT, `payload` BLOB NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, " +
                        "`acked` INTEGER NOT NULL, `failed` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_prefs` (`chatId` TEXT NOT NULL, `pinned` INTEGER NOT NULL, " +
                        "`archived` INTEGER NOT NULL, `muted` INTEGER NOT NULL, PRIMARY KEY(`chatId`))"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_items` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`mime` TEXT NOT NULL, `size` INTEGER NOT NULL, `plainSha256` BLOB NOT NULL, " +
                        "`fileKey` BLOB NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `prekeys` (`keyId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`publicKey` BLOB NOT NULL, `expiresAt` INTEGER NOT NULL, `consumed` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`keyId`))"
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `signedPreKeyId` INTEGER")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `signedPreKey` BLOB")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `signedPreKeyExpiresAt` INTEGER")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `signedPreKeySignature` BLOB")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `oneTimePreKeyId` INTEGER")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `oneTimePreKey` BLOB")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `wirePayload` BLOB")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `encrypted_packets` (`messageId` TEXT NOT NULL, " +
                        "`peerId` TEXT NOT NULL, `packet` BLOB NOT NULL, PRIMARY KEY(`messageId`))"
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `group_outbox` ADD COLUMN `readAt` INTEGER")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_groups` ADD COLUMN `mlsEnabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chat_groups` ADD COLUMN `mlsEpoch` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `group_members` ADD COLUMN `mlsJoined` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_attachments` (" +
                        "`messageId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `senderId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `mime` TEXT NOT NULL, `size` INTEGER NOT NULL, " +
                        "`blobSize` INTEGER NOT NULL, `plainSha256` BLOB NOT NULL, `blobSha256` BLOB NOT NULL, " +
                        "`fileKey` BLOB NOT NULL, `serverUrl` TEXT NOT NULL, `localPath` TEXT, `phase` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `outgoing` INTEGER NOT NULL, PRIMARY KEY(`messageId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_attachments_groupId_createdAt` ON `group_attachments` (`groupId`, `createdAt`)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `communities` (" +
                        "`communityId` TEXT NOT NULL, `manifestJson` TEXT NOT NULL, `localRole` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`communityId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_communities_updatedAt` ON `communities` (`updatedAt`)")
            }
        }

        /** SQLCipher-encrypted. Never uses destructive migration: user data must survive updates. */
        fun build(context: Context, passphrase: ByteArray): AppDatabase =
            buildNamed(context, passphrase, "relyra.db")

        /** Opens an app-private SQLCipher database by its filename (used to validate/sanitize staged restores). */
        fun buildNamed(context: Context, passphrase: ByteArray, databaseName: String): AppDatabase {
            require(databaseName.isNotBlank() && !databaseName.contains('/') && !databaseName.contains('\\'))
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, databaseName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                .build()
        }
    }
}
