package com.luogen.ghpro.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

object Bus {
    /** 用户点下按钮后的真实提交：页面退出也不取消，保证 Star/关闭议题/评论真的落到 GitHub。 */
    val actions = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val tick = MutableStateFlow(0L)
    val starPoke = MutableStateFlow(0L)
    val issuePoke = MutableStateFlow(0L)
    val issueLocalPoke = MutableStateFlow(0L)
    val activityPoke = MutableStateFlow(0L)
    val repoPoke = MutableStateFlow(0L)
    val statusPoke = MutableStateFlow(0L)
    /** 关注/取关某个人后，首页和资料页的关注数要立刻刷新，不能继续显示 0。 */
    val followPoke = MutableStateFlow(0L)
    fun poke() { tick.value = tick.value + 1 }
    fun starChanged() { starPoke.value = starPoke.value + 1 }
    fun repoChanged() { repoPoke.value = System.currentTimeMillis() }
    fun issuesChanged() { issuePoke.value = System.currentTimeMillis() }
    fun issueLocalChanged() { issueLocalPoke.value = System.currentTimeMillis() }
    fun activityChanged() { activityPoke.value = activityPoke.value + 1 }
    fun statusChanged() { statusPoke.value = statusPoke.value + 1 }
    fun followChanged() { followPoke.value = followPoke.value + 1 }
}
