package com.infoagent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WechatMessageEntity::class,
        ConversationEntity::class,
        WatchRuleEntity::class,
        AttachItemEntity::class,
        MemoItemEntity::class,
        MemoSourceEntity::class,
        MemoArchiveEntity::class,
        RepeatRuleEntity::class,
        AutoMemoJobEntity::class,
        AgentRunEntity::class,
        FollowupChatEntity::class,
        AgentTraceEntity::class,
        BriefFeedbackEntity::class,
        BudgetCounterEntity::class,
        ProviderConfigEntity::class,
        ModelRouteEntity::class,
        ScheduledRunEntity::class,
        ReminderEntity::class,
        MemoryFactEntity::class,
        StreakEntity::class,
        RecycleEntity::class,
        MessageArchiveEntity::class,
        AgentLockEntity::class,
        NotificationIdMapEntity::class,
        BriefSliceEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class InfoAgentDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun memoDao(): MemoDao
    abstract fun agentDao(): AgentDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun memoryDao(): MemoryDao
    abstract fun briefSliceDao(): BriefSliceDao

    val ftsStore: MessageFtsStore by lazy { MessageFtsStore(this) }

    companion object {
        @Volatile
        private var INSTANCE: InfoAgentDatabase? = null

        /** v1→v2：A7 记忆反馈列（旧库补列，不丢数据，禁止 destructive migration） */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_fact ADD COLUMN feedback_sum INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2→v3：新增 brief_slice（时间轴），并把 agent_run 历史回填为已读片段 */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `brief_slice` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `biz_date` TEXT NOT NULL,
                        `seq` INTEGER NOT NULL,
                        `trigger` TEXT NOT NULL,
                        `from_ts` INTEGER NOT NULL,
                        `to_ts` INTEGER NOT NULL,
                        `payload_json` TEXT,
                        `new_count` INTEGER NOT NULL,
                        `tokens_in` INTEGER NOT NULL,
                        `tokens_out` INTEGER NOT NULL,
                        `cost_est` REAL NOT NULL,
                        `state` TEXT NOT NULL,
                        `error` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `read_at` INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_brief_slice_biz_date_seq` ON `brief_slice` (`biz_date`, `seq`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_brief_slice_state_to_ts` ON `brief_slice` (`state`, `to_ts`)")

                // 历史回填：按 created_at 升序，每天从 1 开始编号；历史一律已读
                val seqByDay = mutableMapOf<String, Int>()
                db.query(
                    "SELECT biz_date, window, brief_json, tokens_used, cost_est, created_at " +
                        "FROM agent_run WHERE biz_date IS NOT NULL ORDER BY created_at ASC",
                ).use { c ->
                    while (c.moveToNext()) {
                        val bizDate = c.getString(0) ?: continue
                        val window = c.getString(1) ?: "MANUAL"
                        val payload = c.getString(2)
                        val tokens = c.getLong(3)
                        val cost = c.getDouble(4)
                        val createdAt = c.getLong(5)
                        val seq = (seqByDay[bizDate] ?: 0) + 1
                        seqByDay[bizDate] = seq
                        val startOfDay = runCatching {
                            java.time.LocalDate.parse(bizDate)
                                .atStartOfDay(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli()
                        }.getOrDefault(createdAt)
                        db.execSQL(
                            "INSERT INTO brief_slice " +
                                "(biz_date, seq, trigger, from_ts, to_ts, payload_json, new_count, " +
                                "tokens_in, tokens_out, cost_est, state, error, created_at, read_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, 'OK', NULL, ?, ?)",
                            arrayOf(
                                bizDate,
                                seq,
                                window,
                                startOfDay,
                                createdAt,
                                payload,
                                tokens,
                                cost,
                                createdAt,
                                createdAt,
                            ),
                        )
                    }
                }
            }
        }

        /** 统一入口：任何 builder 都必须挂 FTS 建表 Callback（P0-1） */
        fun build(context: Context, name: String): InfoAgentDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                InfoAgentDatabase::class.java,
                name,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(Callback)
                .build()

        fun get(context: Context): InfoAgentDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context, "info_agent.db").also { INSTANCE = it }
            }

        private object Callback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                MessageFtsStore.ensureTable(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // 幂等：仅不存在则建，绝不 DROP（P0-1）
                MessageFtsStore.ensureTable(db)
            }
        }
    }
}