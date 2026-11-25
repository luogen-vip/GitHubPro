package com.luogen.ghpro.data

import kotlinx.coroutines.flow.MutableStateFlow

object Bus {
    val tick = MutableStateFlow(0L)
    fun poke() { tick.value = tick.value + 1 }
}
