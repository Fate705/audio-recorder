package com.audiocapture.recorder

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * FIX: Race condition diperbaiki.
 * Dulu: Service langsung rekam di onStartCommand → channel belum terhubung → callback hilang
 * Sekarang: Service hanya startForeground di onStartCommand, rekaman dimulai SETELAH binding selesai
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.audiocapture.recorder/audio"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private var methodChannel: MethodChannel? = null
    private var audioService: AudioCaptureService? = null
    private var isServiceBound = false

    // Simpan token MediaProjection sampai binding selesai
    private var pendingResultCode: Int = -1
    private var pendingResultData: Intent? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as AudioCaptureService.LocalBinder
            audioService = localBinder.getService()
            isServiceBound = true

            // Set channel DULU, baru mulai rekam — ini kunci fix race condition
            audioService?.setMethodChannel(methodChannel)

            // Sekarang aman untuk mulai rekam
            if (pendingResultCode != -1 && pendingResultData != null) {
                audioService?.initMediaProjection(pendingResultCode, pendingResultData!!)
                pendingResultCode = -1
                pendingResultData = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            audioService = null
            isServiceBound = false
        }
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        )

        methodChannel!!.setMethodCallHandler { call, result ->
            when (call.method) {
                "startRecording" -> handleStartRecording(result)
                "stopRecording" -> handleStopRecording(result)
                else -> result.notImplemented()
            }
        }
    }

    private fun handleStartRecording(result: MethodChannel.Result) {
        // Langsung balik ke Flutter — jangan tunggu dialog
        // Flutter akan update status "Setujui dialog..." dan tunggu callback onRecordingStarted
        result.success(null)
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    private fun handleStopRecording(result: MethodChannel.Result) {
        if (isServiceBound && audioService != null) {
            audioService!!.stopRecording()
            result.success(null)
        } else {
            result.error("NOT_RECORDING", "Tidak sedang merekam", null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                pendingResultCode = resultCode
                pendingResultData = data

                val serviceIntent = Intent(this, AudioCaptureService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                bindService(
                    Intent(this, AudioCaptureService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
            } else {
                // Dialog ditolak — kirim error lewat callback, bukan pendingResult
                methodChannel?.invokeMethod("onError", "Izin MediaProjection ditolak")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
