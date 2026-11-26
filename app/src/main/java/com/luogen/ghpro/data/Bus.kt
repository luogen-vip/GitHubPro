package com.luogen.ghpro.data

import kotlinx.coroutines.flow.MutableStateFlow

object Bus {
    val tick = MutableStateFlow(0L)
    val starPoke = MutableStateFlow(0L)
    val issuePoke = MutableStateFlow(0L)
    fun poke() { tick.value = tick.value + 1 }
    fun starChanged() { starPoke.value = starPoke.value + 1 }
    fun issuesChanged() { issuePoke.value = issuePoke.value + 1 }
}
