package com.example.vishkillpro

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    private lateinit var audioThread: Job

    private val SERVER_URL = "http://192.168.0.106:8000/analyze-audio"
    private val CHANNEL_ID = "recording_channel"
    private val NOTIFICATION_ID = 1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundService()
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
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                audioRecord.startRecording()

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

                    val resultJson = uploadChunk(wavFile, chunkStart, chunkEnd)
                    wavFile.delete()

                    println("Server response for chunk_$chunkIndex: $resultJson")

                    resultJson?.let {
                        try {
                            val json = JSONObject(it)
                            if (json.optBoolean("scam", false)) {
                                val reasoning = json.optString("reasoning", "Scam detected.")
                                showScamNotification(reasoning)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

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

    private fun uploadChunk(file: File, start: Double, end: Double): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaTypeOrNull()))
                .build()

            val request = Request.Builder()
                .url(SERVER_URL)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    println("Upload failed with code: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun showScamNotification(reasoning: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚠️ Scam Alert Detected")
            .setContentText(reasoning.take(80) + if (reasoning.length > 80) "..." else "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(reasoning))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setFullScreenIntent(pendingIntent, true)
            .build()

        manager.notify(NOTIFICATION_ID + chunkIndex, notification)
    }

    private fun convertPcmToWav(
        pcmFile: File,
        wavFile: File,
        sampleRate: Int = 44100,
        channels: Int = 1,
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scam Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Voice Audio")
            .setContentText("Recording from microphone in 10s chunks...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRecording = false
        if (::audioThread.isInitialized) audioThread.cancel()
        super.onDestroy()
    }
}