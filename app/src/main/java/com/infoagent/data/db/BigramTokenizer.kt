package com.infoagent.data.db

/**
 * 中文 bigram 分词器（§7 D02：SQLite FTS4 无中文分词，应用层生成影子列 token）。
 * 规则：连续中文字符按「连续二字组」切分；ASCII 词按空白切分；去重保序。
 * 例：「明天带合同」→ [明天, 天带, 带合, 合同]
 */
object BigramTokenizer {

    private val CJK = Regex("[\\u4e00-\\u9fff]")

    /** 生成查询用（AND 组合）：多字关键词同样 bigram 化，供 MATCH 使用 */
    fun queryTokens(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return tokens
        // ASCII 片段按空白拆词
        val asciiWords = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        val sb = StringBuilder()
        for (part in trimmed) {
            if (CJK.matches(part.toString())) sb.append(part)
            else sb.append(' ')
        }
        val runs = sb.toString().split(Regex("\\s+")).filter { it.isNotBlank() }
        for (run in runs) {
            if (run.length == 1) tokens.add(run)
            else for (i in 0 until run.length - 1) tokens.add(run.substring(i, i + 2))
        }
        asciiWords.forEach { w ->
            val lower = w.lowercase()
            if (!CJK.containsMatchIn(lower)) tokens.add(lower)
        }
        return tokens.distinct()
    }

    /** 入库用落盘 token 字符串（空格分隔） */
    fun tokenString(input: String): String = queryTokens(input).joinToString(" ")

    /** FTS MATCH 查询串：token 间 AND；空输入返回 null */
    fun matchExpression(input: String): String? {
        val tokens = queryTokens(input)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"$it\"" }
    }
}