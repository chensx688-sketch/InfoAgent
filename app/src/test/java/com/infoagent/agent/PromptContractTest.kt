package com.infoagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词「不留垃圾」的回归护栏。
 *
 * 背景：简报系统提示词曾经写着「写进 continued 字段」——而契约里从来没有 continued，
 * 模型要么白烧 token，要么把本该说的东西藏进一个没人读的字段。
 * 这类「幻觉字段」肉眼很难发现，所以用测试钉住。
 */
class PromptContractTest {

    @Test
    fun `系统提示词不许提到契约里没有的字段`() {
        val phantom = listOf("continued", "handle", "suggestion", "reply_draft", "summary_v2")
        phantom.forEach {
            assertFalse(
                "出现了契约外字段「$it」：模型会往里写东西，白烧 token",
                Prompts.BRIEF_SYSTEM.contains(it),
            )
        }
    }

    @Test
    fun `window 和 generated_at 不再让模型回显`() {
        // 这两项由客户端注入：模型不知道真实时间，回显只会写错，写错还会让整条简报失败
        assertFalse(Prompts.BRIEF_SYSTEM.contains("\"window\""))
        assertFalse(Prompts.BRIEF_SYSTEM.contains("\"generated_at\""))
    }

    @Test
    fun `提示词里没有硬编码的地点例子`() {
        // 以前 watch 的例子写死了「11 月杭州早晚温差、西湖边风大、断桥/苏堤路滑」，
        // 等于天天暗示模型聊西湖，既是模板泄漏也浪费 token。
        listOf("西湖", "断桥", "苏堤").forEach {
            assertFalse("别再写死地点例子：$it", Prompts.BRIEF_SYSTEM.contains(it))
        }
    }

    @Test
    fun `提示词长度守在上限内`() {
        // 超过 2200 字符就该回头删，而不是继续往里加
        assertTrue(
            "系统提示词 ${Prompts.BRIEF_SYSTEM.length} 字符，太长（上限 2200）",
            Prompts.BRIEF_SYSTEM.length <= 2_200,
        )
    }
}