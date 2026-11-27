package com.luogen.ghpro.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

object NetworkTools {
    enum class Kind { NONE, UNVALIDATED, WIFI, CELLULAR, OTHER }

    data class Info(
        val kind: Kind,
        val label: String,
        val online: Boolean,
        val slow: Boolean = false
    )

    @Volatile private var cachedAt = 0L
    @Volatile private var cached: Info? = null

    fun info(ctx: Context, force: Boolean = false): Info {
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && cached != null && nowMs - cachedAt < 800L) return cached!!
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val result = when {
            network == null -> Info(Kind.NONE, "没有连接任何网络，也没有可用的移动数据", false)
            else -> {
                val caps = cm!!.getNetworkCapabilities(network)
                when {
                    caps == null -> Info(Kind.NONE, "当前没有可用网络", false)
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                        Info(Kind.NONE, "当前网络没有互联网能力，请检查 Wi‑Fi 或移动数据", false)
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> {
                        val why = when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                                "Wi‑Fi 已连接，但无法访问互联网（可能路由器没联网、需要网页认证，或信号太弱）"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                                "移动数据已开启，但无法访问互联网（可能欠费、没有流量、未插 SIM 卡或信号差）"
                            else -> "网络已连接，但无法访问互联网（可能欠费、信号差或需要认证）"
                        }
                        Info(Kind.UNVALIDATED, why, false)
                    }
                    else -> {
                        val kbps = caps.linkDownstreamBandwidthKbps
                        val slow = kbps in 1..500
                        when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                                Info(Kind.CELLULAR, "正在使用移动数据" + if (slow) "，当前网络较慢" else "", true, slow)
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                                Info(Kind.WIFI, "正在使用 Wi‑Fi" + if (slow) "，当前网络较慢" else "", true, slow)
                            else -> Info(Kind.OTHER, "正在使用其他网络" + if (slow) "，当前网络较慢" else "", true, slow)
                        }
                    }
                }
            }
        }
        cached = result; cachedAt = nowMs
        return result
    }

    fun isCellular(ctx: Context): Boolean = info(ctx).kind == Kind.CELLULAR

    suspend fun awaitOnline(ctx: Context): Boolean = awaitNetwork(ctx) { it.online }

    suspend fun awaitWifi(ctx: Context): Boolean = awaitNetwork(ctx) {
        it.online && it.kind != Kind.CELLULAR
    }

    private suspend fun awaitNetwork(ctx: Context, ok: (Info) -> Boolean): Boolean {
        if (ok(info(ctx))) return true
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        return suspendCancellableCoroutine { cont ->
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (cont.isActive && ok(info(ctx, true))) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        cont.resume(true)
                    }
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (cont.isActive && ok(info(ctx, true))) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        cont.resume(true)
                    }
                }
            }
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            runCatching { cm.registerDefaultNetworkCallback(cb) }
                .onFailure { if (cont.isActive) cont.resume(false) }
        }
    }
}

/** 只统计「本 App 通过移动数据实际写入文件的字节」，按文件名累计。 */
object MobileDataUsage {
    private val perFile = ConcurrentHashMap<String, Long>()

    fun reset(name: String) { perFile.remove(name) }
    fun add(name: String, delta: Long) {
        if (delta <= 0) return
        perFile.merge(name, delta, Long::plus)
    }
    fun bytesFor(name: String): Long = perFile[name] ?: 0L
}

enum class NetPromptKind { DOWNLOAD_MOBILE, BROWSE_MOBILE, NO_NETWORK }
enum class NetChoice { CONTINUE, WAIT_WIFI, CANCEL, DISMISS }

data class NetPrompt(
    val id: Long,
    val kind: NetPromptKind,
    val fileName: String = "",
    val bytes: Long = -1L,
    val slow: Boolean = false,
    val message: String = ""
)

/**
 * 下载/流量确认的统一入口。下载线程会挂起等待用户选择；浏览提示是非阻塞的。
 * 勾选“以后不再提醒”分别写入两个独立开关，不会把下载提醒和浏览提醒互相影响。
 */
object NetPrompts {
    val current = MutableStateFlow<NetPrompt?>(null)
    private var pending: CompletableDeferred<NetChoice>? = null
    private var seq = 0L

    suspend fun askDownload(name: String, bytes: Long, slow: Boolean): NetChoice {
        if (!Cfg.mobileDownloadWarn) return NetChoice.CONTINUE
        val d = CompletableDeferred<NetChoice>()
        pending?.complete(NetChoice.CANCEL)
        pending = d
        val p = NetPrompt(++seq, NetPromptKind.DOWNLOAD_MOBILE, name, bytes, slow)
        current.value = p
        try {
            return d.await()
        } finally {
            if (pending === d) pending = null
            if (current.value?.id == p.id) current.value = null
        }
    }

    fun showBrowse() {
        if (!Cfg.mobileBrowseWarn) return
        if (current.value != null) return
        current.value = NetPrompt(++seq, NetPromptKind.BROWSE_MOBILE)
    }

    fun showNoNetwork(name: String, message: String) {
        // 下载会继续等网络，不是失败；显示一个不阻塞的说明，用户可取消。
        // 已有别的弹窗时不要覆盖，避免把等待流量选择的下载晾在后台。
        if (current.value != null) return
        current.value = NetPrompt(++seq, NetPromptKind.NO_NETWORK, name, message = message)
    }

    fun answer(choice: NetChoice, dontAskAgain: Boolean = false) {
        val p = current.value
        if (p != null && dontAskAgain) {
            when (p.kind) {
                NetPromptKind.DOWNLOAD_MOBILE -> Cfg.mobileDownloadWarn = false
                NetPromptKind.BROWSE_MOBILE -> Cfg.mobileBrowseWarn = false
                NetPromptKind.NO_NETWORK -> Unit
            }
        }
        pending?.complete(choice)
        pending = null
        current.value = null
    }

    fun dismissNoNetwork() {
        if (current.value?.kind == NetPromptKind.NO_NETWORK) current.value = null
    }
}
