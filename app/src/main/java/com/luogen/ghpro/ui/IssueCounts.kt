package com.luogen.ghpro.ui

import androidx.compose.runtime.mutableStateMapOf
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Repo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 卡片/仓库头部的“开放议题”严格计数缓存。
 * 走 GraphQL issues（不含 PR），批量查询；失败时退回 REST 的旧数字，不影响页面。
 */
object IssueCounts {
    private val map = mutableStateMapOf<String, Int>()
    private val tried = HashSet<String>()
    private val mutex = Mutex()

    fun get(full: String): Int? = map[full]

    suspend fun loadBatch(repos: List<Repo>) {
        val todo = repos.map { it.full_name }.filter { it.contains("/") && it !in tried }.distinct()
        if (todo.isEmpty()) return
        mutex.withLock {
            todo.filter { it !in tried }.chunked(25).forEach { chunk ->
                chunk.forEach { tried.add(it) }
                val res = runCatching {
                    Api.issueCounts(chunk.map { it.substringBefore("/") to it.substringAfter("/") })
                }.getOrDefault(emptyMap())
                res.forEach { (k, v) -> map[k] = v }
            }
        }
    }

    /** 本地关/开议题后立刻修正已缓存的严格数；没缓存就等下次 ensure 拉真值。 */
    fun adjust(full: String, delta: Int) {
        map[full]?.let { map[full] = (it + delta).coerceAtLeast(0) }
    }

    fun invalidate(full: String) {
        map.remove(full)
        tried.remove(full)
    }

    suspend fun ensure(full: String) {
        if (!full.contains("/") || map.containsKey(full) || full in tried) return
        loadBatch(listOf(Repo(id = 0, name = full.substringAfter("/"), full_name = full,
            owner = null)))
    }
}
