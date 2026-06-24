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

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.audiocapture.recorder/audio"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private var methodChannel: MethodChannel? = null
    private var audioService: AudioCaptureService? = null
    private var isServiceBound = false

    private var pendingResultCode: Int = -1
    private var pendingResultData: Intent? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as AudioCaptureService.LocalBinder
            audioService = localBinder.getService()
            isServiceBound = true
            audioService?.setMethodChannel(methodChannel)

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
                "stopRecording"  -> handleStopRecording(result)
                else             -> result.notImplemented()
            }
        }
    }

    private fun handleStartRecording(result: MethodChannel.Result) {
        // Langsung balik ke Flutter — UI tidak freeze
        result.success(null)

        // Tampilkan dialog izin MediaProjection
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
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

        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode != Activity.RESULT_OK || data == null) {
            methodChannel?.invokeMethod("onError", "Izin MediaProjection ditolak")
            return
        }

        // Simpan token dulu, baru start+bind service
        pendingResultCode = resultCode
        pendingResultData  = data

        val svcIntent = Intent(this, AudioCaptureService::class.java).apply {
            // Kirim result data lewat intent agar service bisa startForeground dengan tipe yang benar
            putExtra("RESULT_CODE", resultCode)
            putExtra("RESULT_DATA", data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }

        if (isServiceBound) {
            // Sudah terhubung dari rekaman sebelumnya
            audioService?.setMethodChannel(methodChannel)
            audioService?.initMediaProjection(resultCode, data)
            pendingResultCode = -1
            pendingResultData  = null
        } else {
            bindService(
                Intent(this, AudioCaptureService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
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
