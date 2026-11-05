package com.infoagent.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.repo.IncomingMessage
import com.infoagent.data.repo.MessageRepository
import com.infoagent.remind.CaptureWatchdogWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 微信/QQ 通知采集（FR-01，§8.8）：静默入库、不弹本应用通知。
 * - 不再跳过「组摘要」通知（微信折叠后只有摘要，跳过等于丢消息）；
 * - 记录连接/入库时间，供看门狗与消息页状态卡展示；
 * - 断连时写状态并安排看门狗重绑（HyperOS 冻结后自愈）。
 */
class NotifyCaptureService : NotificationListenerService() {

    companion object {
        private const val TAG = "InfoAgentCapture"
        const val PREFS = "capture_state"
        const val KEY_CONNECTED_AT = "connected_at"
        const val KEY_LAST_INGEST_AT = "last_ingest_at"
        const val KEY_LAST_OUTCOME = "last_outcome"

        fun state(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** 请求系统重新绑定（Android 8.1+）；失败返回 false */
        fun requestRebind(context: Context): Boolean {
            return runCatching {
                requestRebind(ComponentName(context, NotifyCaptureService::class.java))
                true
            }.getOrElse {
                Log.w(TAG, "requestRebind failed", it)
                false
            }
        }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(TAG, "capture coroutine failed", e) },
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected")
        state(this).edit().putLong(KEY_CONNECTED_AT, System.currentTimeMillis()).apply()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "listener disconnected → schedule rebind")
        state(this).edit().putLong(KEY_CONNECTED_AT, 0L).apply()
        runCatching { CaptureWatchdogWorker.scheduleNow(applicationContext) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handlePosted(sbn)
        } catch (e: Exception) {
            // C1：单条通知解析/入库失败只记日志，绝不让通知回调把进程带崩
            Log.e(TAG, "handle notification failed", e)
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        if (!NotificationParser.isSupported(sbn.packageName)) return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        val isSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val raw = RawNotification(
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            tag = sbn.tag,
            category = n.category,
            postTime = sbn.postTime,
        )

        // A5：先查会话是否群聊，再决定是否按「发送者: 内容」切分（查询放到 IO 协程）
        scope.launch {
            val db = InfoAgentDatabase.get(applicationContext)
            val peer = raw.title?.trim().orEmpty()
            val conv = runCatching { db.messageDao().conversation(peer) }.getOrNull()
            // N1：群聊判定不再依赖「会话表已标记」（否则新群永远判成单聊）
            val systemGroup = runCatching {
                (android.os.Build.VERSION.SDK_INT >= 30 &&
                    extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) ||
                    (extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                        ?.mapNotNull { (it as? android.os.Bundle)?.getCharSequence("sender")?.toString() }
                        ?.filter { it.isNotBlank() }
                        ?.distinct()?.size ?: 0) >= 2
            }.getOrDefault(false)
            val knownGroup = conv?.isGroup == 1L || systemGroup
            val knownSenders = if (knownGroup) emptySet() else runCatching {
                db.messageDao().senderNames(peer)
                    .filterNot { it.isBlank() || it.length > 12 || NotificationParser.isLabelWord(it) }
                    .toSet()
            }.getOrDefault(emptySet())
            val effective = raw.copy(knownGroup = knownGroup, knownSenders = knownSenders)
            when (val outcome = NotificationParser.parse(effective)) {
                is ParseOutcome.Messages -> {
                    Log.i(TAG, "posted ${sbn.packageName} summary=$isSummary lines=${outcome.messages.size}")
                    writeOutcome("messages:${outcome.messages.size}")
                    ingestAll(outcome.messages)
                }
                is ParseOutcome.Withdraw -> {
                    Log.i(TAG, "posted withdraw peer=${outcome.peerName}")
                    writeOutcome("withdraw")
                    handleWithdraw(outcome, sbn.postTime)
                }
                is ParseOutcome.Folded -> {
                    Log.i(TAG, "posted folded count=${outcome.count}")
                    writeOutcome("folded:${outcome.count}")
                    ingestFolded(outcome)
                }
                ParseOutcome.Ignored -> {
                    Log.d(TAG, "ignored ${sbn.packageName} summary=$isSummary")
                    writeOutcome("ignored")
                }
            }
        }
    }

    private fun writeOutcome(outcome: String) {
        state(this).edit().putString(KEY_LAST_OUTCOME, outcome).apply()
    }

    private suspend fun ingestAll(messages: List<IncomingMessage>) {
        val repo = MessageRepository(InfoAgentDatabase.get(applicationContext))
        var inserted = 0
        messages.forEach { if (repo.ingest(it) is com.infoagent.data.repo.IngestResult.Created) inserted++ }
        if (inserted > 0) {
            state(this).edit().putLong(KEY_LAST_INGEST_AT, System.currentTimeMillis()).apply()
            com.infoagent.agent.AutoMemoWorker.scheduleDebounced(applicationContext)
        }
    }

    private suspend fun ingestFolded(outcome: ParseOutcome.Folded) {
        if (outcome.count <= 0) return
        val db = InfoAgentDatabase.get(applicationContext)
        MessageRepository(db).ingest(
            IncomingMessage(
                appSource = "wechat",
                peerId = outcome.peerName,
                peerName = outcome.peerName,
                sender = outcome.peerName,
                direction = "IN",
                msgType = "folded",
                content = "${outcome.count} 条新消息（需打开微信查看）",
                ts = System.currentTimeMillis(),
                notificationKey = "folded",
                isGroup = true,
            ),
        )
        state(this).edit().putLong(KEY_LAST_INGEST_AT, System.currentTimeMillis()).apply()
    }

    /** D07：撤回匹配失败只记系统行，不猜原消息 */
    private suspend fun handleWithdraw(outcome: ParseOutcome.Withdraw, postTime: Long) {
        val db = InfoAgentDatabase.get(applicationContext)
        val dao = db.messageDao()
        val candidates = dao.withdrawCandidates(
            peerId = outcome.peerName,
            sender = outcome.sender,
            from = postTime - 120_000L,
            to = postTime + 5_000L,
        )
        if (candidates.isNotEmpty()) {
            dao.updateRecalled(candidates.first().id, 1)
            return
        }
        MessageRepository(db).ingest(
            IncomingMessage(
                appSource = "wechat",
                peerId = outcome.peerName,
                peerName = outcome.peerName,
                sender = outcome.sender,
                direction = "IN",
                msgType = "system",
                content = "某消息已撤回",
                ts = postTime,
                notificationKey = "withdraw",
                isGroup = outcome.isGroup,
            ),
        )
    }
}