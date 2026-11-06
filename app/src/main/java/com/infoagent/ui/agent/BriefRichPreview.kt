package com.infoagent.ui.agent

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.infoagent.agent.model.Brief
import com.infoagent.agent.model.BriefPlan
import com.infoagent.agent.model.MemoStatus
import com.infoagent.agent.model.PendingMemo
import com.infoagent.agent.model.Recommendation
import com.infoagent.agent.model.TimelineItem
import com.infoagent.agent.model.TomorrowPlan
import com.infoagent.agent.model.WechatPoint
import com.infoagent.agent.model.Advice
import com.infoagent.agent.model.TopicCluster
import com.infoagent.data.repo.DayMetrics
import com.infoagent.data.repo.HourPoint
import com.infoagent.data.repo.PeerVolume
import com.infoagent.ui.theme.InfoAgentTheme

/**
 * Android Studio 预览（Design / Split 面板可见）：
 * 左栏点开本文件的 Preview 就能看到简报新版式在浅色 / 深色下的真实渲染，
 * 改 dp、字号、配色立刻反馈，不必装到真机。
 */
@Preview(name = "简报可视化 · 浅色", showBackground = true, widthDp = 380, heightDp = 1600)
@Composable
private fun PreviewBriefLight() {
    InfoAgentTheme(darkTheme = false) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp),
        ) {
            BriefRichView(
                onOpenRefs = {},brief = previewBrief(), metrics = previewMetrics(), nowMinutes = 10 * 60 + 20)
        }
    }
}

@Preview(
    name = "简报可视化 · 深色",
    showBackground = true,
    widthDp = 380,
    heightDp = 1600,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PreviewBriefDark() {
    InfoAgentTheme(darkTheme = true) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp),
        ) {
            BriefRichView(
                onOpenRefs = {},brief = previewBrief(), metrics = previewMetrics(), nowMinutes = 10 * 60 + 20)
        }
    }
}

@Preview(name = "简报可视化 · 怎么办（brief_v2）", showBackground = true, widthDp = 380, heightDp = 900)
@Composable
private fun PreviewAdvice() {
    InfoAgentTheme(darkTheme = false) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp),
        ) {
            BriefRichView(
                onOpenRefs = {},brief = previewBrief(), metrics = null, nowMinutes = 12 * 60 + 20)
        }
    }
}

@Preview(name = "简报可视化 · 空态", showBackground = true, widthDp = 380, heightDp = 420)
@Composable
private fun PreviewBriefEmpty() {
    InfoAgentTheme(darkTheme = false) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp),
        ) {
            BriefRichView(
                onOpenRefs = {},
                brief = Brief(
                    window = "MORNING",
                    generatedAt = "2026-11-06T10:00:00+08:00",
                    memoStatus = MemoStatus(done = 0),
                    plan = BriefPlan(),
                ),
                metrics = null,
            )
        }
    }
}

private fun previewBrief() = Brief(
    window = "MANUAL",
    generatedAt = "2026-11-06T10:15:00+08:00",
    advice = listOf(
        com.infoagent.agent.model.Advice(
            who = "陈英钿",
            ask = "明天去西湖，问你要不要托她带东西",
            say = "不用带啦，我这边都有，你路上轻松点。11 月那边风大，记得带件外套。下午几点出发？太晚就赶不上返程高峰了。",
            why = "她只是顺口问一句，不必让她绕路；但时间还没定，需要你拍板",
            watch = listOf(
                "今天就得说定：她明天一早就走，晚上说就来不及准备",
                "对方是女生，措辞别用命令句，也别开「带点特产回来」这种玩笑",
                "11 月杭州早晚 10℃ 上下、湖边湿冷，提醒外套比提醒带东西更有用",
                "下午出发要避开 17:00 后的返程高峰，且周末景区周边限行、停车难",
            ),
            kind = "reply",
            due = "今天中午前",
            basis = "常识",
            refs = listOf(11L, 12L),
        ),
        com.infoagent.agent.model.Advice(
            who = "王总",
            ask = "报价折扣等你确认后才能回签",
            say = "王总，折扣按 9 折走，我这边今天 15:00 前把回签件发您。",
            why = "报价单已经在等回签，卡在折扣这个决定上",
            watch = listOf("回签件记得同步扫描件给小李", "15:00 是对方开会前的时间点"),
            kind = "prepare",
            due = "15:00 前",
            basis = "原文",
            refs = listOf(3L),
        ),
        com.infoagent.agent.model.Advice(
            who = "房东",
            ask = "本月房租周五前要转",
            say = null,
            why = "金额固定，只是容易忘",
            watch = listOf("转账备注写房号，避免对方对不上账"),
            kind = "remind",
            due = "周五 12:00",
            basis = "原文",
            refs = listOf(9L),
        ),
    ),
    wechatPoints = listOf(
        WechatPoint("王总", "报价单已发，等你确认折扣后回签", "15:00"),
        WechatPoint("王总", "明天上午再对一次合同细节", "明天 10:00"),
        WechatPoint("项目群", "测试环境今天下午会重启一次", null),
        WechatPoint("项目群", "小李让你补一份接口文档", null),
        WechatPoint("小李", "合同扫描件还没收到", null),
        WechatPoint("房东", "本月房租记得周五前转", "周五 12:00"),
    ),
    memoStatus = MemoStatus(
        done = 2,
        pendingDaily = listOf(PendingMemo("写周报", "18:00"), PendingMemo("跑步 5 公里", null)),
        pendingTemp = listOf(PendingMemo("回电话给王总", "14:30")),
    ),
    plan = BriefPlan(
        timeline = listOf(
            TimelineItem("09:30", "晨会同步进度", "memo", "确认本周目标", "must"),
            TimelineItem("14:30", "回电话给王总", "memo", "折扣确认", "must"),
            TimelineItem("15:00", "报价单回签", "chat", "王总", "must"),
            TimelineItem("18:00", "写周报", "memo", null, "maybe"),
        ),
        note = "周五前完成合同扫描件",
    ),
    recommendations = listOf(
        Recommendation("把接口文档提前到上午发", "小李连续两天催过", emptyList()),
        Recommendation("周报模板沿用上周结构", "上周被要求补数据", emptyList()),
    ),
    topics = listOf(
        TopicCluster(
            name = "报价与合同",
            points = listOf("报价单已发，折扣待确认", "合同扫描件还没收到", "明天上午再对一次细节"),
            convs = listOf("王总", "小李"),
            state = "risk",
            refs = listOf(9L, 10L),
        ),
        TopicCluster(
            name = "接口文档",
            points = listOf("小李连催两天", "建议上午发出"),
            convs = listOf("项目群", "小李"),
            state = "open",
            refs = listOf(11L),
        ),
    ),
    tomorrow = TomorrowPlan(todos = listOf(PendingMemo("合同细节对齐", "10:00"))),
    risks = listOf("王总的报价确认还没回，可能影响今天回签"),
)

private fun previewMetrics(): DayMetrics {
    val today = listOf(
        0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 1, 6 to 3, 7 to 6, 8 to 9, 9 to 14,
        10 to 11, 11 to 7, 12 to 4, 13 to 8, 14 to 12, 15 to 16, 16 to 9, 17 to 5,
        18 to 3, 19 to 2, 20 to 1, 21 to 1, 22 to 0, 23 to 0,
    ).map { HourPoint(it.first, it.second) }
    val yesterday = listOf(
        0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0, 6 to 1, 7 to 2, 8 to 5, 9 to 8,
        10 to 12, 11 to 9, 12 to 6, 13 to 5, 14 to 7, 15 to 6, 16 to 8, 17 to 4,
        18 to 2, 19 to 3, 20 to 2, 21 to 0, 22 to 0, 23 to 0,
    ).map { HourPoint(it.first, it.second) }
    return DayMetrics(
        date = "2026-11-06",
        todayHours = today,
        yesterdayHours = yesterday,
        todayTotal = today.sumOf { it.count },
        yesterdayTotal = yesterday.sumOf { it.count },
        topPeers = listOf(
            PeerVolume("项目群", 21),
            PeerVolume("王总", 14),
            PeerVolume("小李", 9),
            PeerVolume("房东", 3),
            PeerVolume("家庭群", 2),
        ),
        dailyDone = 2,
        dailyTotal = 4,
        tempCount = 1,
    )
}
