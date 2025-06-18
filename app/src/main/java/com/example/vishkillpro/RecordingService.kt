package com.example.vishkillpro

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import java.io.File
import java.io.*

class RecordingService : Service() {

    private var isRecording = false
    private lateinit var audioThread: Thread

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ✅ Runtime RECORD_AUDIO permission check
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        // ✅ Start foreground before accessing MediaProjection
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data")
            ?: return START_NOT_STICKY

        val mediaProjection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, data)

        // ✅ Configure internal audio capture (media, games, etc.)
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        val outputFile = File(externalCacheDir, "internal_audio.pcm")

        isRecording = true

        audioThread = Thread {
            try {
                audioRecord.startRecording()
                val outputStream = outputFile.outputStream()
                val buffer = ByteArray(bufferSize)

                while (isRecording) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                    }
                }

                audioRecord.stop()
                outputStream.close()
                audioRecord.release()

            } catch (e: SecurityException) {
                e.printStackTrace()
                stopSelf()
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }

        audioThread.start()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "record_audio_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Recording Internal Audio")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRecording = false

        if (::audioThread.isInitialized) {
            try {
                audioThread.join() // ⏳ Waits until PCM writing is done
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }

        // ✅ Now the PCM file is complete, safe to convert
        val pcmFile = File(externalCacheDir, "internal_audio.pcm")
        val wavFile = File(externalCacheDir, "internal_audio.wav")
        if (pcmFile.exists()) {
            try {
                convertPcmToWav(pcmFile, wavFile)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        super.onDestroy()
    }
}


fun convertPcmToWav(pcmFile: File, wavFile: File, sampleRate: Int = 44100, channels: Int = 2, bitsPerSample: Int = 16) {
    val pcmData = pcmFile.readBytes()
    val totalDataLen = pcmData.size + 36
    val byteRate = sampleRate * channels * bitsPerSample / 8

    val header = ByteArrayOutputStream().apply {
        write("RIFF".toByteArray())                          // ChunkID
        write(intToByteArrayLE(totalDataLen))                // ChunkSize
        write("WAVE".toByteArray())                          // Format
        write("fmt ".toByteArray())                          // Subchunk1ID
        write(intToByteArrayLE(16))                          // Subchunk1Size (16 for PCM)
        write(shortToByteArrayLE(1))                         // AudioFormat (1 for PCM)
        write(shortToByteArrayLE(channels.toShort()))        // NumChannels
        write(intToByteArrayLE(sampleRate))                  // SampleRate
        write(intToByteArrayLE(byteRate))                    // ByteRate
        write(shortToByteArrayLE((channels * bitsPerSample / 8).toShort())) // BlockAlign
        write(shortToByteArrayLE(bitsPerSample.toShort()))   // BitsPerSample
        write("data".toByteArray())                          // Subchunk2ID
        write(intToByteArrayLE(pcmData.size))                // Subchunk2Size
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
