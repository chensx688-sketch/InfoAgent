package com.infoagent.ui.agent

/** 通知深链：点简报通知时带上 slice_id，Agent 页据此滚到对应片段 */
object AgentDeepLink {
    @Volatile
    var sliceId: Long? = null

    /** 取一次即清空，避免每次重组都重新滚动 */
    fun consume(): Long? {
        val v = sliceId
        sliceId = null
        return v
    }
}