package com.audiocapture

import android.app.*
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "AudioCaptureChannel"

        // --- 核心算法固定参数 ---
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 192000
        private const val SILENCE_THRESHOLD = 180        // 振幅阈值过滤底噪
        private const val MIN_RECORD_DURATION_MS = 2000L // 至少录制2秒才保留

        // --- Action 和 Extra 常量 ---
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SPLIT = "ACTION_SPLIT"

        const val ACTION_RECORDING_STARTED = "com.audiocapture.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.audiocapture.RECORDING_STOPPED"
        const val ACTION_FILE_SPLIT = "com.audiocapture.FILE_SPLIT"
        const val ACTION_ERROR = "com.audiocapture.ERROR"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        const val EXTRA_OUTPUT_DIR = "EXTRA_OUTPUT_DIR"
        const val EXTRA_SILENCE_MS = "EXTRA_SILENCE_MS"
        const val EXTRA_SILENCE_ENABLED = "EXTRA_SILENCE_ENABLED"
        const val EXTRA_MIN_FILE_SIZE = "EXTRA_MIN_FILE_SIZE"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        const val EXTRA_ERROR_MSG = "EXTRA_ERROR_MSG"
    }

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null

    // --- 线程与并发控制锁 ---
    private val isRecording = AtomicBoolean(false)
    private val isReleasing = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    // --- 状态与配置变量 ---
    private var currentFilePath: String? = null
    private var lastVoiceTime = 0L
    private var startTimeMs = 0L
    private var isMuxerStarted = false

    // 【核心修复：时间戳计数器】用于准确计算音频文件的 PTS
    private var totalBytesReadForCurrentFile = 0L

    // 接收来自 MainActivity 的设置
    private var silenceHangtimeMs = 1500L
    private var isSilenceEnabled = true
    private var minFileSizeBytes = 100 * 1024L
    private var outputDir: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

                outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: getExternalFilesDir(null)?.absolutePath + "/Recordings"
                silenceHangtimeMs = intent.getLongExtra(EXTRA_SILENCE_MS, 1500L)
                isSilenceEnabled = intent.getBooleanExtra(EXTRA_SILENCE_ENABLED, true)
                minFileSizeBytes = intent.getLongExtra(EXTRA_MIN_FILE_SIZE, 100 * 1024L)

                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForeground(1, createNotification())
                    val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projection = projectionManager.getMediaProjection(resultCode, data)
                    startCapture()
                    sendBroadcast(Intent(ACTION_RECORDING_STARTED))
                }
            }
            ACTION_STOP -> {
                releaseResources()
                stopSelf()
            }
            ACTION_SPLIT -> {
                if (isRecording.get()) {
                    stopAndReleaseMuxer(isManualSplit = true)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (isRecording.get()) return

        isReleasing.set(false)
        isRecording.set(true)

        recordingThread = Thread {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()

                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
                audioRecord = AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                    .setAudioPlaybackCaptureConfig(config)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                audioRecord?.startRecording()
                val buffer = ShortArray(bufferSize / 2)

                while (isRecording.get()) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        val amplitude = calculateMaxAmplitude(buffer, readSize)
                        sendAmplitudeBroadcast(amplitude)

                        val currentTime = System.currentTimeMillis()
                        val hasVoice = !isSilenceEnabled || amplitude > SILENCE_THRESHOLD

                        if (hasVoice) {
                            lastVoiceTime = currentTime
                            if (muxer == null) {
                                prepareEncoderAndMuxer()
                            }
                            processAudioFrame(buffer, readSize)
                        } else {
                            if (muxer != null && (currentTime - lastVoiceTime > silenceHangtimeMs)) {
                                stopAndReleaseMuxer(isManualSplit = false)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}")
                sendBroadcast(Intent(ACTION_ERROR).putExtra(EXTRA_ERROR_MSG, e.message))
            } finally {
                releaseResources()
            }
        }.apply { name = "AudioRecordThread"; start() }
    }

    private fun prepareEncoderAndMuxer() {
        try {
            val dir = File(outputDir)
            if (!dir.exists()) dir.mkdirs()

            val fileName = "Record_${System.currentTimeMillis()}.m4a"
            val file = File(dir, fileName)
            currentFilePath = file.absolutePath
            startTimeMs = System.currentTimeMillis()

            // 【核心修复：每次创建新文件时，严格将写入字节重置为 0】
            totalBytesReadForCurrentFile = 0L

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 20)

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.start()

            muxer = MediaMuxer(currentFilePath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isMuxerStarted = false
        } catch (e: Exception) {
            sendBroadcast(Intent(ACTION_ERROR).putExtra(EXTRA_ERROR_MSG, "编码器初始化失败: ${e.message}"))
            stopAndReleaseMuxer(false)
        }
    }

    private fun processAudioFrame(buffer: ShortArray, size: Int) {
        val safeEncoder = encoder ?: return
        val safeMuxer = muxer ?: return

        // 1. 输入 PCM 数据
        val inputIndex = safeEncoder.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val inputBuffer = safeEncoder.getInputBuffer(inputIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                // 维持之前的小端序修复，防爆音
                inputBuffer.order(java.nio.ByteOrder.nativeOrder())
                inputBuffer.asShortBuffer().put(buffer, 0, size)

                // 【核心修复：按采样公式精确计算 PTS（微秒）】
                // 1 个采样 (16-bit Mono) 占用 2 个字节。所以 totalBytesRead / 2 = 采样数
                val ptsUs = (totalBytesReadForCurrentFile * 1_000_000L) / (SAMPLE_RATE * 2L)

                safeEncoder.queueInputBuffer(inputIndex, 0, size * 2, ptsUs, 0)

                // 累加本次写入的字节数（ShortArray 的 size 表示采样点个数，字节数需 * 2）
                totalBytesReadForCurrentFile += (size * 2L)
            }
        }

        // 2. 提取编码后的 AAC 数据
        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = safeEncoder.dequeueOutputBuffer(bufferInfo, 10000)
        while (outputIndex >= 0) {
            val outputBuffer = safeEncoder.getOutputBuffer(outputIndex)
            if (outputBuffer != null && bufferInfo.size > 0) {
                if (!isMuxerStarted) {
                    safeMuxer.addTrack(safeEncoder.outputFormat)
                    safeMuxer.start()
                    isMuxerStarted = true
                }
                safeMuxer.writeSampleData(0, outputBuffer, bufferInfo)
            }
            safeEncoder.releaseOutputBuffer(outputIndex, false)
            outputIndex = safeEncoder.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    private fun stopAndReleaseMuxer(isManualSplit: Boolean) {
        synchronized(this) {
            try {
                encoder?.apply { stop(); release() }
                muxer?.apply { if (isMuxerStarted) stop(); release() }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing muxer: ${e.message}")
            } finally {
                encoder = null
                muxer = null
                isMuxerStarted = false
                checkAndCleanupFile(isManualSplit)
            }
        }
    }

    private fun checkAndCleanupFile(isManualSplit: Boolean) {
        currentFilePath?.let { path ->
            val file = File(path)
            val duration = System.currentTimeMillis() - startTimeMs
            if (file.exists()) {
                val isSizeTooSmall = minFileSizeBytes > 0 && file.length() < minFileSizeBytes
                val isDurationTooShort = duration < MIN_RECORD_DURATION_MS

                if (isSizeTooSmall || isDurationTooShort) {
                    file.delete()
                } else {
                    val intent = Intent(if (isManualSplit) ACTION_FILE_SPLIT else "com.audiocapture.NEW_FILE")
                    intent.putExtra(EXTRA_FILE_PATH, file.absolutePath)
                    sendBroadcast(intent)
                }
            }
        }
        currentFilePath = null
    }

    private fun calculateMaxAmplitude(buffer: ShortArray, size: Int): Int {
        var max = 0
        for (i in 0 until size) {
            val abs = Math.abs(buffer[i].toInt())
            if (abs > max) max = abs
        }
        return max
    }

    private fun sendAmplitudeBroadcast(amplitude: Int) {
        sendBroadcast(Intent("com.audiocapture.AMPLITUDE").apply { putExtra("amplitude", amplitude) })
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "录音服务", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("系统音频录制中")
            .setContentText("正在自动检测声音并录制...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun releaseResources() {
        if (isReleasing.getAndSet(true)) return
        isRecording.set(false)

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {} finally { audioRecord = null }

        stopAndReleaseMuxer(false)

        try { projection?.stop() } catch (e: Exception) {} finally { projection = null }

        sendBroadcast(Intent(ACTION_RECORDING_STOPPED).putExtra(EXTRA_FILE_PATH, currentFilePath))
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}