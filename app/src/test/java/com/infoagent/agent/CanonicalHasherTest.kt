package com.infoagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CanonicalHasherTest {

    private fun input(
        temperature: Double = 0.3,
        model: String = "deepseek-chat",
        msgLastTs: Long = 100L,
        recalled: Long = 0,
        facts: List<Pair<Long, Long>> = listOf(1L to 60L),
    ) = BriefingInput(
        bizDate = "2026-11-04",
        window = "MORNING",
        model = model,
        temperature = temperature,
        maxTokens = 2048,
        messages = listOf(
            BriefingMessageRef(10L, "dk-1", "ch-1", recalled, msgLastTs),
            BriefingMessageRef(11L, "dk-2", "ch-2", 0, 200L),
        ),
        memoSnapshot = "daily:健身|temp:买猫粮",
        topFacts = facts,
    )

    @Test
    fun `相同输入哈希稳定`() {
        assertEquals(CanonicalHasher.inputHash(input()), CanonicalHasher.inputHash(input()))
    }

    @Test
    fun `改温度或模型即不命中（D04 评审点）`() {
        assertNotEquals(CanonicalHasher.inputHash(input()), CanonicalHasher.inputHash(input(temperature = 0.7)))
        assertNotEquals(CanonicalHasher.inputHash(input()), CanonicalHasher.inputHash(input(model = "gpt-4o-mini")))
    }

    @Test
    fun `撤回或内容变化即不命中，但 lastTs 不再击穿缓存（A4）`() {
        assertNotEquals(CanonicalHasher.inputHash(input()), CanonicalHasher.inputHash(input(recalled = 1)))
        // A4：重复通知刷新 last_ts 不应导致缓存失效
        assertEquals(
            CanonicalHasher.inputHash(input()),
            CanonicalHasher.inputHash(input(msgLastTs = 999)),
        )
    }

    @Test
    fun `关注会话变化即不命中（A3）`() {
        val base = input()
        val focused = base.copy(focus = setOf("王总"))
        assertEquals(CanonicalHasher.inputHash(focused), CanonicalHasher.inputHash(base.copy(focus = setOf("王总"))))
        assertNotEquals(CanonicalHasher.inputHash(base), CanonicalHasher.inputHash(focused))
    }

    @Test
    fun `记忆事实变化即不命中`() {
        assertNotEquals(
            CanonicalHasher.inputHash(input()),
            CanonicalHasher.inputHash(input(facts = listOf(1L to 60L, 2L to 50L))),
        )
    }

    @Test
    fun `batchHash 对消息顺序不敏感但对内容敏感`() {
        val a = listOf(BriefingMessageRef(1, "k1", "h1", 0, 1), BriefingMessageRef(2, "k2", "h2", 0, 2))
        val b = a.reversed()
        assertEquals(CanonicalHasher.batchHash(a, "m"), CanonicalHasher.batchHash(b, "m"))
        assertNotEquals(
            CanonicalHasher.batchHash(a, "m"),
            CanonicalHasher.batchHash(listOf(a[0].copy(contentHash = "hX"), a[1]), "m"),
        )
    }
}