package io.github.rvbcrs.wallmode

internal fun pulseSpeechMayFinish(streamFinished: Boolean, playerActive: Boolean, queued: Int, pendingEnqueues: Int): Boolean =
    streamFinished && !playerActive && queued == 0 && pendingEnqueues == 0
