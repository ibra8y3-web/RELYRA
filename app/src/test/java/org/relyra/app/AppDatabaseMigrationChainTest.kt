package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.relyra.app.core.database.AppDatabase

class AppDatabaseMigrationChainTest {
    @Test fun schemaMigrationsAreContiguousFromVersionOneThroughEighteen() {
        val migrations = listOf(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_13_14,
            AppDatabase.MIGRATION_14_15,
            AppDatabase.MIGRATION_15_16,
            AppDatabase.MIGRATION_16_17,
            AppDatabase.MIGRATION_17_18,
        )
        assertEquals((1..17).toList(), migrations.map { it.startVersion })
        assertEquals((2..18).toList(), migrations.map { it.endVersion })
    }
}
