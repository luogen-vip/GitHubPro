package com.luogen.ghpro.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** 通知点击统一入口：MainActivity 写入目标路由，NavRoot 负责真正跳转。 */
object DeepLinkBus {
    val target = MutableStateFlow<String?>(null)

    fun open(route: String?) {
        if (!route.isNullOrBlank()) target.value = route
    }
}
