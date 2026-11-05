package com.infoagent.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * R2/D2：v1→v2 迁移测试。
 * 1) SQL 级：手工建 v1 的 memory_fact → 执行 MIGRATION_1_2 → 断言列与默认值。
 * 2) Room 级：用导出的 schema(1.json) 建库 + v2 identityHash，让 Room 真正打开并校验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        context.getDatabasePath("migration-test.db").delete()
        context.getDatabasePath("migration-room.db").delete()
    }

    private val v1Schema = """
        CREATE TABLE memory_fact (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            category TEXT NOT NULL,
            subject TEXT,
            fact_text TEXT,
            confidence INTEGER NOT NULL,
            evidence_count INTEGER NOT NULL,
            last_seen INTEGER,
            source_refs TEXT,
            period_hint TEXT,
            status TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
    """.trimIndent()

    @Test
    fun `v1 到 v2 补出 feedback_sum 列且默认 0`() {
        val file = context.getDatabasePath("migration-test.db")
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v1Schema)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(config).also { helper = it }
        val db = openHelper.writableDatabase

        db.execSQL(
            "INSERT INTO memory_fact(category, confidence, evidence_count, status, created_at, updated_at) " +
                "VALUES('HABIT', 50, 1, 'ACTIVE', 1, 1)",
        )

        InfoAgentDatabase.MIGRATION_1_2.migrate(db)

        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(memory_fact)").use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        assertTrue("迁移后应存在 feedback_sum 列", "feedback_sum" in columns)

        db.query("SELECT feedback_sum FROM memory_fact").use { cursor ->
            assertTrue("旧行应被补上默认值 0", cursor.moveToFirst())
            assertTrue("默认值应为 0", cursor.getLong(0) == 0L)
        }
    }

    @Test
    fun `Room 真实打开 v1 库并走迁移校验`() = runBlocking {
        val file = context.getDatabasePath("migration-room.db")
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()

        val schema1 = org.json.JSONObject(readResource("schemas/1.json"))
        val schema2 = org.json.JSONObject(readResource("schemas/2.json"))
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema1.getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            fun realSql(sql: String) = sql.replace("\${TABLE_NAME}", table)
            raw.execSQL(realSql(entity.getString("createSql")))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                raw.execSQL(realSql(indices.getJSONObject(j).getString("createSql")))
            }
        }
        val identity = schema2.getJSONObject("database").getString("identityHash")
        raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        raw.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$identity')")
        raw.version = 1
        raw.close()

        // InfoAgentDatabase.build：应执行 MIGRATION_1_2 并通过 Room 的 schema 校验
        val opened = InfoAgentDatabase.build(context, "migration-room.db")
        try {
            val columns = mutableListOf<String>()
            opened.openHelper.readableDatabase.query("PRAGMA table_info(memory_fact)").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue("Room 迁移后应有 feedback_sum", "feedback_sum" in columns)
            // v2→v3：时间轴表必须存在且可读写
            val sliceTables = mutableListOf<String>()
            opened.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type='table' AND name='brief_slice'")
                .use { c -> while (c.moveToNext()) sliceTables += c.getString(0) }
            assertTrue("Room 迁移后应有 brief_slice", "brief_slice" in sliceTables)
            val sliceId = opened.briefSliceDao().insert(
                BriefSliceEntity(
                    bizDate = "2026-11-04",
                    seq = 1,
                    trigger = "MANUAL",
                    fromTs = 1L,
                    toTs = 2L,
                    state = "OK",
                    createdAt = 2L,
                ),
            )
            assertTrue(sliceId > 0)
            assertEquals(1, opened.briefSliceDao().listByDay("2026-11-04").size)
            opened.memoryDao().insertFact(
                MemoryFactEntity(
                    category = "HABIT",
                    factText = "迁移后可写",
                    confidence = 50,
                    evidenceCount = 1,
                    status = "ACTIVE",
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            assertTrue(opened.memoryDao().topActiveFacts(5).any { it.factText == "迁移后可写" })
        } finally {
            opened.close()
            file.delete()
        }
    }

    private fun readResource(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
}