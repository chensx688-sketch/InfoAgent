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
        EnrichCacheEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class InfoAgentDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun memoDao(): MemoDao
    abstract fun agentDao(): AgentDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun memoryDao(): MemoryDao
    abstract fun briefSliceDao(): BriefSliceDao

    abstract fun enrichDao(): EnrichDao

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

        /**
         * v3→v4：片段记录「覆盖到哪条消息 id」。
         *
         * 只加列，老片段 covered_max_id=0，pipeline 遇到 0 会退回按时间窗取数（兼容历史）；
         * 新片段开始按 id 增量，时间戳异常/迟到投递的新消息不会再被水位线永久挡住。
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE brief_slice ADD COLUMN covered_max_id INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4→v5：外部信息缓存表 + 片段记录「本次参考了什么」。
         *
         * context_json 存本次用到的外部事实（天气/搜索摘要），
         * 一来下次渲染可以直接展示「这条简报参考了哪些实时数据」（透明可核），
         * 二来模型换版本重新解析历史简报时不用重查、也不烧额度。
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `enrich_cache` (" +
                        "`cache_key` TEXT NOT NULL, `payload` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`cache_key`))",
                )
                db.execSQL("ALTER TABLE brief_slice ADD COLUMN context_json TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v6：记忆从「覆盖式合并」改成「只增 + 时间有效性」。
         *
         * 加两列：valid_from（何时开始为真）、valid_to（何时失效，null=仍有效）。
         * 老数据没有这两个信息，一律按「created_at 起就有效、至今有效」处理 —— 不做假精确。
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_fact ADD COLUMN valid_from INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_fact ADD COLUMN valid_to INTEGER")
                db.execSQL("UPDATE memory_fact SET valid_from = COALESCE(created_at, 0) WHERE valid_from = 0")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_fact_subject_valid_to` " +
                        "ON `memory_fact` (`subject`, `valid_to`)",
                )
            }
        }

        /** 统一入口：任何 builder 都必须挂 FTS 建表 Callback（P0-1） */
        fun build(context: Context, name: String): InfoAgentDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                InfoAgentDatabase::class.java,
                name,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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