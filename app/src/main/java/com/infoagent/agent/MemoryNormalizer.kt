package com.infoagent.agent

import android.content.Context
import org.json.JSONObject

/** subject 规范化（§8.5）：小写/去空格 + 同义词表归一（assets/regex/memory_synonyms.json） */
class MemoryNormalizer(context: Context) {

    private val groups: List<List<String>> = loadGroups(context)

    private fun loadGroups(context: Context): List<List<String>> = runCatching {
        val text = context.assets.open("regex/memory_synonyms.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val arr = root.optJSONArray("groups") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val g = arr.optJSONArray(i) ?: return@map emptyList()
            (0 until g.length()).map { j -> g.optString(j).lowercase().trim() }.filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }
    }.getOrElse { emptyList() }

    fun normalize(subject: String?): String {
        val key = subject.orEmpty().lowercase().replace(Regex("""\s+"""), "").trim()
        if (key.isEmpty()) return ""
        for (group in groups) {
            if (group.any { it == key || key.contains(it) && it.length >= 2 }) return group[0]
        }
        return key
    }
}