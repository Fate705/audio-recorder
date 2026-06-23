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
 * MainActivity
 *
 * Menjadi jembatan antara UI Flutter (Dart) dan native Android.
 * Menggunakan MethodChannel untuk komunikasi dua arah.
 *
 * Alur:
 * 1. Flutter memanggil "startRecording"
 * 2. MainActivity meminta izin MediaProjection dari sistem (dialog muncul)
 * 3. Setelah user setuju, AudioCaptureService dimulai sebagai Foreground Service
 * 4. Service memanggil balik Flutter lewat MethodChannel saat status berubah
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.audiocapture.recorder/audio"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private var methodChannel: MethodChannel? = null
    private var pendingResult: MethodChannel.Result? = null
    private var audioService: AudioCaptureService? = null
    private var isServiceBound = false

    // ServiceConnection: dipanggil saat service terhubung/terputus
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as AudioCaptureService.LocalBinder
            audioService = localBinder.getService()
            isServiceBound = true

            // Berikan referensi channel ke service agar bisa callback ke Flutter
            audioService?.setMethodChannel(methodChannel)
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

    /**
     * Mulai rekaman:
     * 1. Minta izin MediaProjection (WAJIB, tidak bisa dilewati)
     * 2. Setelah dapat izin, start Foreground Service
     */
    private fun handleStartRecording(result: MethodChannel.Result) {
        pendingResult = result

        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Tampilkan dialog izin MediaProjection ke user
        // Dialog ini menjelaskan bahwa app akan merekam layar/audio
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
    }

    private fun handleStopRecording(result: MethodChannel.Result) {
        if (isServiceBound && audioService != null) {
            audioService!!.stopRecording()
            result.success(null)
        } else {
            result.error("NOT_RECORDING", "Tidak sedang merekam", null)
        }
    }

    /**
     * Callback setelah user menyetujui/menolak dialog MediaProjection
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // User menyetujui → start Foreground Service dengan token MediaProjection
                startAudioCaptureService(resultCode, data)
                pendingResult?.success(null)
            } else {
                // User menolak dialog
                pendingResult?.error(
                    "PERMISSION_DENIED",
                    "Izin MediaProjection ditolak. Aplikasi tidak bisa merekam audio internal tanpa izin ini.",
                    null
                )
            }
            pendingResult = null
        }
    }

    private fun startAudioCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
        }

        // Android 8+ wajib pakai startForegroundService untuk service dengan notifikasi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Bind ke service agar bisa komunikasi dua arah
        bindService(
            Intent(this, AudioCaptureService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
