package com.audiocapture.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioCaptureService — Foreground Service inti aplikasi
 *
 * Bertanggung jawab untuk:
 * 1. Menangkap audio internal (Discord, game, dll) via MediaProjection + AudioPlaybackCapture
 * 2. Menangkap audio mic via AudioRecord
 * 3. Mix kedua sumber audio menjadi satu file WAV
 * 4. Berjalan sebagai Foreground Service agar tidak dimatikan OS
 *
 * PENTING: Service ini harus dideklarasikan dengan
 * android:foregroundServiceType="mediaProjection" di AndroidManifest.xml
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "audio_capture_channel"

        // Audio config — harus konsisten antara AudioRecord dan AudioTrack
        private const val SAMPLE_RATE = 44100          // Hz (standar CD quality)
        private const val CHANNEL_COUNT = 2            // Stereo
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BITS_PER_SAMPLE = 16
    }

    // Binder untuk komunikasi dengan MainActivity
    inner class LocalBinder : Binder() {
        fun getService(): AudioCaptureService = this@AudioCaptureService
    }

    private val binder = LocalBinder()
    private var methodChannel: MethodChannel? = null

    private var mediaProjection: MediaProjection? = null
    private var audioPlaybackRecord: AudioRecord? = null   // Internal audio (MediaProjection)
    private var micAudioRecord: AudioRecord? = null        // Mikrofon
    private var outputFile: File? = null

    private val isRecording = AtomicBoolean(false)
    private var internalAudioThread: Thread? = null
    private var mixerThread: Thread? = null

    // Buffer shared antara thread internal audio dan mixer
    private val internalAudioBuffer = ArrayDeque<ShortArray>()
    private val micAudioBuffer = ArrayDeque<ShortArray>()
    private val bufferLock = Object()

    override fun onBind(intent: Intent): IBinder = binder

    fun setMethodChannel(channel: MethodChannel?) {
        methodChannel = channel
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Menyiapkan rekaman...")
        // Android 10+ wajib deklarasikan tipe foreground service saat startForeground
        // Tanpa ini, di Android 14 bisa crash atau token MediaProjection di-reject
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    /**
     * Dipanggil oleh MainActivity SETELAH binding selesai dan channel sudah di-set
     * Sehingga callback ke Flutter dijamin sampai
     */
    fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            notifyError("Gagal inisialisasi MediaProjection")
            return
        }

        // Daftarkan callback untuk mendeteksi jika MediaProjection dihentikan sistem
        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection dihentikan sistem")
                if (isRecording.get()) {
                    stopRecording()
                }
            }
        }, null)

        startRecording()
    }

    /**
     * Mulai proses rekaman:
     * 1. Setup AudioRecord untuk mic
     * 2. Setup AudioRecord untuk internal audio (AudioPlaybackCapture)
     * 3. Mulai thread mixing + menulis file
     */
    fun startRecording() {
        if (isRecording.get()) return

        // Jalankan setup di background thread agar UI tidak freeze
        Thread {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(Date())
                val dir = getOutputDirectory()
                outputFile = File(dir, "AudioCapture_$timestamp.wav")

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    notifyError("Android 10+ diperlukan untuk merekam audio internal")
                    return@Thread
                }

                setupMicRecorder()
                setupInternalAudioRecorder()

                isRecording.set(true)

                // Notify Flutter SEKARANG — jangan tunggu thread rekaman selesai setup
                // Ini yang membuat UI langsung responsif
                updateNotification("⏺ Merekam Audio Internal + Mic...")
                notifyStarted()

                // Baru mulai thread rekaman setelah UI sudah update
                startRecordingThreads()

            } catch (e: Exception) {
                Log.e(TAG, "Error saat memulai rekaman: ${e.message}", e)
                cleanup()
                notifyError(e.message ?: "Error tidak diketahui")
            }
        }.also { it.name = "SetupThread"; it.start() }
    }

    /**
     * AudioRecord untuk Mikrofon (input dari mic HP/headset)
     */
    private fun setupMicRecorder() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AUDIO_FORMAT
        )

        micAudioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,       // Sumber: mikrofon
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AUDIO_FORMAT,
            minBufferSize * 4
        )

        if (micAudioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord (mic) gagal diinisialisasi")
        }
    }

    /**
     * AudioRecord untuk Internal Audio menggunakan AudioPlaybackCaptureConfiguration
     *
     * AudioPlaybackCaptureConfiguration (Android 10/API 29+) adalah cara satu-satunya
     * untuk merekam audio yang keluar dari aplikasi lain (Discord, game, dll).
     *
     * Catatan penting:
     * - Aplikasi yang direkam audionya harus TIDAK menyetel ALLOW_CAPTURE_BY_NONE
     * - Discord dan game umumnya tidak memblokir capture, jadi ini akan bekerja
     * - YouTube Music dan Spotify MEMBLOKIR capture (mereka setel allowAudioPlaybackCapture=false)
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupInternalAudioRecorder() {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            // MATCH_ALL: rekam audio dari SEMUA aplikasi
            // Ini yang membuat kita bisa menangkap Discord + game secara bersamaan
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AUDIO_FORMAT
        )

        audioPlaybackRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBufferSize * 4)
            .setAudioPlaybackCaptureConfig(config)   // <-- Kunci utama!
            .build()

        if (audioPlaybackRecord!!.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord (internal) gagal diinisialisasi")
        }
    }

    /**
     * Mulai semua thread rekaman
     * - Thread 1: Baca dari mic → buffer mic
     * - Thread 2: Baca dari internal audio → buffer internal
     * - Thread 3: Mix kedua buffer → tulis ke file WAV
     */
    private fun startRecordingThreads() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AUDIO_FORMAT
        ) * 4

        // Mulai AudioRecord
        micAudioRecord?.startRecording()
        audioPlaybackRecord?.startRecording()

        // Thread 1: Baca Mic
        Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording.get()) {
                val read = micAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(bufferLock) {
                        micAudioBuffer.add(buffer.copyOf(read))
                    }
                }
            }
        }.also {
            it.name = "MicRecorderThread"
            it.start()
            internalAudioThread = it
        }

        // Thread 2: Baca Internal Audio
        Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording.get()) {
                val read = audioPlaybackRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(bufferLock) {
                        internalAudioBuffer.add(buffer.copyOf(read))
                    }
                }
            }
        }.also {
            it.name = "InternalAudioThread"
            it.start()
        }

        // Thread 3: Mixer → tulis ke file WAV
        mixerThread = Thread {
            try {
                writeWavFile()
            } catch (e: Exception) {
                Log.e(TAG, "Error saat menulis file: ${e.message}", e)
                notifyError("Gagal menulis file: ${e.message}")
            }
        }.also {
            it.name = "AudioMixerThread"
            it.start()
        }
    }

    /**
     * Mix audio mic + internal audio dan tulis langsung ke file WAV
     *
     * Algoritma mixing:
     * - Ambil sample dari kedua buffer
     * - Clamp hasil penjumlahan agar tidak overflow (soft clipping)
     * - Tulis ke file WAV
     */
    private fun writeWavFile() {
        val fos = FileOutputStream(outputFile!!)

        // Tulis WAV header placeholder dulu (akan diupdate nanti dengan ukuran sebenarnya)
        val headerPlaceholder = ByteArray(44)
        fos.write(headerPlaceholder)

        var totalSamplesWritten = 0L

        while (isRecording.get() || micAudioBuffer.isNotEmpty() || internalAudioBuffer.isNotEmpty()) {
            val micData: ShortArray?
            val internalData: ShortArray?

            synchronized(bufferLock) {
                micData = if (micAudioBuffer.isNotEmpty()) micAudioBuffer.removeFirst() else null
                internalData =
                    if (internalAudioBuffer.isNotEmpty()) internalAudioBuffer.removeFirst() else null
            }

            if (micData == null && internalData == null) {
                Thread.sleep(5)
                continue
            }

            // Tentukan ukuran buffer output (pakai yang terbesar)
            val size = maxOf(micData?.size ?: 0, internalData?.size ?: 0)
            if (size == 0) continue

            val mixed = ShortArray(size)

            for (i in 0 until size) {
                val micSample = micData?.getOrElse(i) { 0 }?.toInt() ?: 0
                val internalSample = internalData?.getOrElse(i) { 0 }?.toInt() ?: 0

                // Mix dengan soft clipping untuk mencegah distorsi
                val sum = micSample + internalSample
                mixed[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }

            // Tulis sebagai little-endian bytes (format WAV standar)
            val bytes = ByteArray(mixed.size * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mixed)
            fos.write(bytes)
            totalSamplesWritten += mixed.size
        }

        fos.flush()
        fos.close()

        // Update WAV header dengan ukuran data sebenarnya
        writeWavHeader(outputFile!!, totalSamplesWritten)

        Log.d(TAG, "File selesai: ${outputFile!!.absolutePath}, samples: $totalSamplesWritten")
        notifyStopped(outputFile!!.absolutePath)
    }

    /**
     * Tulis WAV header yang benar ke awal file
     * WAV header standar 44 bytes
     */
    private fun writeWavHeader(file: File, totalSamples: Long) {
        val numChannels = CHANNEL_COUNT
        val dataSize = totalSamples * numChannels * (BITS_PER_SAMPLE / 8)
        val fileSize = dataSize + 36  // 44 - 8 bytes RIFF header

        val raf = RandomAccessFile(file, "rw")
        raf.seek(0)

        // RIFF chunk
        raf.writeBytes("RIFF")
        raf.writeInt(Integer.reverseBytes(fileSize.toInt()))
        raf.writeBytes("WAVE")

        // fmt sub-chunk
        raf.writeBytes("fmt ")
        raf.writeInt(Integer.reverseBytes(16))       // Sub-chunk size
        raf.writeShort(java.lang.Short.reverseBytes(1).toInt())   // PCM format
        raf.writeShort(java.lang.Short.reverseBytes(numChannels.toShort()).toInt())
        raf.writeInt(Integer.reverseBytes(SAMPLE_RATE))
        raf.writeInt(Integer.reverseBytes((SAMPLE_RATE * numChannels * BITS_PER_SAMPLE / 8).toInt()))
        raf.writeShort(java.lang.Short.reverseBytes((numChannels * BITS_PER_SAMPLE / 8).toShort()).toInt())
        raf.writeShort(java.lang.Short.reverseBytes(BITS_PER_SAMPLE.toShort()).toInt())

        // data sub-chunk
        raf.writeBytes("data")
        raf.writeInt(Integer.reverseBytes(dataSize.toInt()))

        raf.close()
    }

    fun stopRecording() {
        if (!isRecording.get()) return

        Log.d(TAG, "Menghentikan rekaman...")
        isRecording.set(false)

        // Hentikan AudioRecord
        micAudioRecord?.stop()
        audioPlaybackRecord?.stop()

        // Tunggu mixer thread selesai menulis file
        mixerThread?.join(5000)
    }

    private fun cleanup() {
        isRecording.set(false)

        micAudioRecord?.release()
        micAudioRecord = null

        audioPlaybackRecord?.release()
        audioPlaybackRecord = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun getOutputDirectory(): File {
        // Simpan di folder Music/AudioCapture — mudah diakses user
        val musicDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_MUSIC
        )
        val dir = File(musicDir, "AudioCapture")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ====================== NOTIFICATION ======================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Audio Capture Service",
                NotificationManager.IMPORTANCE_LOW   // LOW = tidak ada suara notifikasi
            ).apply {
                description = "Menampilkan status rekaman audio"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Audio Capture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ====================== FLUTTER CALLBACKS ======================

    private fun notifyStarted() {
        android.os.Handler(mainLooper).post {
            methodChannel?.invokeMethod("onRecordingStarted", null)
        }
    }

    private fun notifyStopped(filePath: String) {
        android.os.Handler(mainLooper).post {
            methodChannel?.invokeMethod("onRecordingStopped", filePath)
            updateNotification("Rekaman selesai: ${File(filePath).name}")
            cleanup()
            stopSelf()
        }
    }

    private fun notifyError(message: String) {
        Log.e(TAG, "notifyError: $message")
        android.os.Handler(mainLooper).post {
            // Toast sebagai fallback — kelihatan meski MethodChannel gagal
            android.widget.Toast.makeText(this, "❌ $message", android.widget.Toast.LENGTH_LONG).show()
            methodChannel?.invokeMethod("onError", message)
            cleanup()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }
}
