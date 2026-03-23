package no.nordicsemi.android.blinky.ble.logging

import timber.log.Timber

class GrpcAudioClientLogTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val resolvedTag = tag ?: return
        if (resolvedTag != "GrpcAudioClient") {
            return
        }

        val fullMessage = buildString {
            append(message)
            if (t != null) {
                append('\n')
                append(t.stackTraceToString())
            }
        }
        GrpcRuntimeLogStore.append(priority, resolvedTag, fullMessage)
    }
}
