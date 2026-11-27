package com.luogen.ghpro.data

import android.content.Context
import com.luogen.ghpro.AppCtx
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/** 收藏夹里的一个仓库。note 是这个仓库在本收藏夹里的备注，可以为空。 */
data class FavEntry(
    val fullName: String = "",
    val note: String = "",
    val addedAt: Long = 0L
)

/** 一个可命名的收藏夹。用户可建多个，各自带名称和描述。 */
data class FavFolder(
    val id: String = "",
    val name: String = "",
    val note: String = "",
    val createdAt: Long = 0L,
    val entries: List<FavEntry> = emptyList()
)

/**
 * 收藏夹本地存储。数据量很小，直接用 SharedPreferences + JSON，不依赖网络，
 * 点击收藏/取消收藏都会立即写盘，冷启动后仍在。
 */
object Favorites {
    private const val PREF = "ghpro_favorites"
    private const val KEY = "folders"
    private val gson = Gson()
    private var ctx: Context? = null
    private var loaded = false
    private val folders = mutableListOf<FavFolder>()

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
    }

    private fun sp() = (ctx ?: AppCtx.app).getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val raw = sp().getString(KEY, null) ?: return
            val type = object : TypeToken<List<FavFolder>>() {}.type
            val list: List<FavFolder> = gson.fromJson(raw, type) ?: emptyList()
            folders.clear()
            list.forEach { f ->
                if (f.id.isBlank()) return@forEach
                folders.add(f.copy(
                    name = f.name ?: "",
                    note = f.note ?: "",
                    entries = (f.entries ?: emptyList()).map { e ->
                        e.copy(fullName = e.fullName ?: "", note = e.note ?: "")
                    }
                ))
            }
        }
    }

    private fun ensure() {
        if (!loaded) load()
    }

    private fun persist() {
        runCatching { sp().edit().putString(KEY, gson.toJson(folders)).apply() }
    }

    @Synchronized
    fun all(): List<FavFolder> {
        ensure()
        return folders.map { it.copy(entries = it.entries.toList()) }
    }

    @Synchronized
    fun folder(id: String): FavFolder? {
        ensure()
        return folders.firstOrNull { it.id == id }?.let { it.copy(entries = it.entries.toList()) }
    }

    @Synchronized
    fun isSaved(fullName: String): Boolean {
        ensure()
        if (fullName.isBlank()) return false
        return folders.any { f -> f.entries.any { it.fullName.equals(fullName, true) } }
    }

    @Synchronized
    fun foldersOf(fullName: String): List<FavFolder> {
        ensure()
        return folders.filter { f -> f.entries.any { it.fullName.equals(fullName, true) } }
            .map { it.copy(entries = it.entries.toList()) }
    }

    @Synchronized
    fun create(name: String, note: String = ""): FavFolder {
        ensure()
        val f = FavFolder(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "未命名收藏夹" },
            note = note.trim(),
            createdAt = System.currentTimeMillis(),
            entries = emptyList()
        )
        folders.add(f)
        persist()
        return f
    }

    @Synchronized
    fun updateFolder(id: String, name: String, note: String) {
        ensure()
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = folders[i]
        folders[i] = old.copy(name = name.trim().ifBlank { old.name }, note = note.trim())
        persist()
    }

    @Synchronized
    fun deleteFolder(id: String) {
        ensure()
        if (folders.removeAll { it.id == id }) persist()
    }

    /** 加入收藏夹；同一个收藏夹里不会重复。 */
    @Synchronized
    fun add(folderId: String, fullName: String, note: String = "") {
        ensure()
        val i = folders.indexOfFirst { it.id == folderId }
        if (i < 0 || fullName.isBlank()) return
        val old = folders[i]
        if (old.entries.any { it.fullName.equals(fullName, true) }) return
        val entry = FavEntry(fullName = fullName.trim(), note = note.trim(), addedAt = System.currentTimeMillis())
        folders[i] = old.copy(entries = old.entries + entry)
        persist()
    }

    /** 从所有收藏夹里移除这个仓库，返回是否真的移除了。 */
    @Synchronized
    fun remove(fullName: String): Boolean {
        ensure()
        if (fullName.isBlank()) return false
        var changed = false
        for (i in folders.indices) {
            val f = folders[i]
            val kept = f.entries.filterNot { it.fullName.equals(fullName, true) }
            if (kept.size != f.entries.size) {
                folders[i] = f.copy(entries = kept)
                changed = true
            }
        }
        if (changed) persist()
        return changed
    }

    @Synchronized
    fun removeFrom(folderId: String, fullName: String) {
        ensure()
        val i = folders.indexOfFirst { it.id == folderId }
        if (i < 0) return
        val f = folders[i]
        val kept = f.entries.filterNot { it.fullName.equals(fullName, true) }
        if (kept.size != f.entries.size) {
            folders[i] = f.copy(entries = kept)
            persist()
        }
    }

    @Synchronized
    fun setEntryNote(folderId: String, fullName: String, note: String) {
        ensure()
        val fi = folders.indexOfFirst { it.id == folderId }
        if (fi < 0) return
        val f = folders[fi]
        val next = f.entries.map { if (it.fullName.equals(fullName, true)) it.copy(note = note.trim()) else it }
        folders[fi] = f.copy(entries = next)
        persist()
    }
}
