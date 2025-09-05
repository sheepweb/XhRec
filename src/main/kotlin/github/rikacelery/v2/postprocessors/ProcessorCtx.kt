package github.rikacelery.v2.postprocessors

import github.rikacelery.Room
import java.util.*

data class ProcessorCtx(
    val room: Room,
    val startTime: Date,
    val endTime: Date,
    val duration: Long,
    val quality: String,
)