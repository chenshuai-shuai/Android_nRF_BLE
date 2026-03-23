package no.nordicsemi.android.blinky

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import no.nordicsemi.android.blinky.ble.GrpcAudioClient
import no.nordicsemi.android.blinky.ble.logging.GrpcAudioClientLogTree
import timber.log.Timber

@HiltAndroidApp
class HiltApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber. By default, the library will log to the Android logcat.
        Timber.plant(Timber.DebugTree())
        Timber.plant(GrpcAudioClientLogTree())

        // Configure gRPC audio defaults. Session starts on push-to-talk.
        Log.i("BlinkyApp", "HiltApplication onCreate: configuring gRPC client")
        GrpcAudioClient.configure(24000, 1, 16)
        GrpcAudioClient.setEncodeAudioAsBase64(false)
        GrpcAudioClient.setDecodeAudioOutputBase64(true)
        GrpcAudioClient.setAutoDetectBase64Output(true)
        GrpcAudioClient.setFormatLocked(true)
        GrpcAudioClient.setTestToneEnabled(false)
        GrpcAudioClient.setProbeEnabled(false)
    }
}
