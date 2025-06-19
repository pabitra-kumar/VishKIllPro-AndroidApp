package com.example.vishkillpro

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class RecordingService : Service() {

    private var isRecording = false
    private var chunkIndex = 0
    private lateinit var audioRecord: AudioRecord
    private lateinit var mediaProjection: MediaProjection
    private lateinit var audioThread: Job

    private val SERVER_URL = "http://192.168.253.9:5050/upload" // Replace later

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundService()

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        mediaProjection = (getSystemService(MediaProjectionManager::class.java) as MediaProjectionManager)
            .getMediaProjection(resultCode, data)

        startChunkedRecording()
        return START_STICKY
    }

    private fun startChunkedRecording() {
        isRecording = true

        audioThread = CoroutineScope(Dispatchers.IO).launch {
            try {
                val sampleRate = 44100
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build()

                if (ContextCompat.checkSelfPermission(this@RecordingService, android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    stopSelf()
                    return@launch
                }

                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                audioRecord.startRecording()

                var previousWavFile: File? = null

                while (isRecording) {
                    val pcmFile = File(externalCacheDir, "chunk_$chunkIndex.pcm")
                    val wavFile = File(externalCacheDir, "chunk_$chunkIndex.wav")
                    val buffer = ByteArray(bufferSize)
                    val outputStream = FileOutputStream(pcmFile)

                    val chunkStart = chunkIndex * 10.0
                    val chunkEnd = chunkStart + 10.0

                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < 10_000) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) outputStream.write(buffer, 0, read)
                    }
                    outputStream.close()

                    convertPcmToWav(pcmFile, wavFile)
                    pcmFile.delete()

                    if (chunkIndex > 0 && previousWavFile != null && previousWavFile.exists()) {
                        try {
                            val client = OkHttpClient()
                            val getRequest = Request.Builder().url(SERVER_URL).build()
                            client.newCall(getRequest).execute().use { response ->
                                if (response.isSuccessful) {
                                    previousWavFile.delete()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    uploadChunk(wavFile, chunkStart, chunkEnd)

                    previousWavFile = wavFile
                    chunkIndex++
                }

                audioRecord.stop()
                audioRecord.release()
            } catch (e: SecurityException) {
                e.printStackTrace()
                stopSelf()
            }
        }
    }

    private fun uploadChunk(file: File, start: Double, end: Double) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val metadata = JSONObject().apply {
                put("ambience", JSONObject())
                put("diarization", JSONObject())
                put("ai_voice", JSONObject())
                put("emotion", JSONObject())
                put("transcription", JSONObject())
                put("scam_analysis", JSONObject())
                put("chunks", listOf(
                    JSONObject().apply {
                        put("start", start)
                        put("end", end)
                        put("chunk_path", file.name)
                    }
                ))
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("json", metadata.toString())
                .addFormDataPart("file", file.name,
                    file.asRequestBody("audio/wav".toMediaTypeOrNull()))
                .build()

            val request = Request.Builder()
                .url(SERVER_URL)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) println("Upload failed: ${response.code}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun convertPcmToWav(
        pcmFile: File,
        wavFile: File,
        sampleRate: Int = 44100,
        channels: Int = 2,
        bitsPerSample: Int = 16
    ) {
        val pcmData = pcmFile.readBytes()
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            write(intToByteArrayLE(totalDataLen))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToByteArrayLE(16))
            write(shortToByteArrayLE(1))
            write(shortToByteArrayLE(channels.toShort()))
            write(intToByteArrayLE(sampleRate))
            write(intToByteArrayLE(byteRate))
            write(shortToByteArrayLE((channels * bitsPerSample / 8).toShort()))
            write(shortToByteArrayLE(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToByteArrayLE(pcmData.size))
        }.toByteArray()

        FileOutputStream(wavFile).use { output ->
            output.write(header)
            output.write(pcmData)
        }
    }

    private fun intToByteArrayLE(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )

    private fun shortToByteArrayLE(value: Short): ByteArray =
        byteArrayOf(
            (value.toInt() and 0xff).toByte(),
            ((value.toInt() shr 8) and 0xff).toByte()
        )

    private fun startForegroundService() {
        val channelId = "recording_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Audio Recording", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Recording Internal Audio")
            .setContentText("Recording and uploading in 10s chunks...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRecording = false
        if (::audioThread.isInitialized) audioThread.cancel()
        super.onDestroy()
    }
}