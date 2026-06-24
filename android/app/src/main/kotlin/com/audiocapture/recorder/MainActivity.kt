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
 * ARSITEKTUR BARU (Android 14+ compatible):
 *
 * Urutan yang BENAR untuk MediaProjection di Android 14+:
 * 1. Start foreground service DULU (wajib sebelum dialog)
 * 2. Tunggu service bound (onServiceConnected)
 * 3. BARU tampilkan dialog MediaProjection
 * 4. Setelah user setujui → pass token ke service → mulai rekam
 *
 * Sebelumnya: dialog ditampilkan SEBELUM service jalan → Android 14 reject token
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.audiocapture.recorder/audio"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private var methodChannel: MethodChannel? = null
    private var audioService: AudioCaptureService? = null
    private var isServiceBound = false
    private var pendingShowDialog = false  // flag: tampilkan dialog setelah service bound

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as AudioCaptureService.LocalBinder
            audioService = localBinder.getService()
            isServiceBound = true

            audioService?.setMethodChannel(methodChannel)

            // Service sudah jalan sebagai foreground → SEKARANG aman tampilkan dialog
            if (pendingShowDialog) {
                pendingShowDialog = false
                showMediaProjectionDialog()
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
        // Langsung balik ke Flutter — UI tidak freeze
        result.success(null)

        // Step 1: Start foreground service DULU (wajib Android 14+)
        val serviceIntent = Intent(this, AudioCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Step 2: Bind — setelah onServiceConnected, baru tampilkan dialog
        pendingShowDialog = true
        if (isServiceBound) {
            // Sudah terhubung (rekaman sebelumnya), langsung tampilkan dialog
            audioService?.setMethodChannel(methodChannel)
            pendingShowDialog = false
            showMediaProjectionDialog()
        } else {
            bindService(
                Intent(this, AudioCaptureService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    private fun showMediaProjectionDialog() {
        // Step 3: Dialog muncul SETELAH service foreground sudah jalan
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
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
                // Step 4: Pass token ke service → mulai rekam
                audioService?.initMediaProjection(resultCode, data)
            } else {
                // User menolak dialog
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
