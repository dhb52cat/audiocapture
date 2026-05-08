package com.audiocapture

import android.app.*
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * 前台服务：捕获系统音频，支持静音自动分割 + 手动分割
 */
class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.audiocapture.START"
        const val ACTION_STOP = "com.audiocapture.STOP"
        const val ACTION_SPLIT = "com.audiocapture.SPLIT"
        const val ACTION_RECORDING_STARTED = "com.audiocapture.STARTED"
        const val ACTION_RECORDING_STOPPED = "com.audiocapture.STOPPED"
        const val ACTION_FILE_SPLIT = "com.audiocapture.FILE_SPLIT"
        const val ACTION_ERROR = "com.audiocapture.ERROR"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_ERROR_MSG = "error_msg"
        const val EXTRA_OUTPUT_DIR = "output_dir"
        const val EXTRA_SILENCE_MS = "silence_ms"
        const val EXTRA_SILENCE_ENABLED = "silence_enabled"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_capture_channel"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_AMPLITUDE_THRESHOLD = 150
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    @Volatile private var splitRequested = false

    private var outputDir: String = ""
    private var fileIndex = 1
    private var currentFilePath: String = ""

    private var silenceEnabled = true
    private var silenceThresholdMs = 1500L
    private var silenceSince = 0L
    private var isRecordingStarted = false  // 是否真正开始录音（检测到声音）
    private var audioStartTime = 0L  // 开始录音的时间

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: filesDir.absolutePath
                silenceThresholdMs = intent.getLongExtra(EXTRA_SILENCE_MS, 1500L)
                silenceEnabled = intent.getBooleanExtra(EXTRA_SILENCE_ENABLED, true)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("正在录制系统音频..."))
                    startCapture(resultCode, data)
                }
            }
            ACTION_STOP -> stopCapture()
            ACTION_SPLIT -> {
                if (isRecordingStarted) {
                    splitRequested = true
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(8192)

        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                sendError("AudioRecord 初始化失败，请检查权限和Android版本")
                return
            }

            fileIndex = 1
            isRecordingStarted = false
            audioStartTime = 0L

            audioRecord?.startRecording()
            isRecording = true
            sendBroadcast(Intent(ACTION_RECORDING_STARTED))

            recordingThread = Thread { encodeLoop(bufferSize) }
            recordingThread?.start()

        } catch (e: Exception) {
            sendError("启动录音失败：${e.message}")
        }
    }

    // ----------------------------------------------------------------
    // 主编码循环
    // ----------------------------------------------------------------
    private fun encodeLoop(bufferSize: Int) {
        val inputBuffer = ByteArray(bufferSize)
        var muxerStarted = false
        var audioTrackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()
        var presentationTimeUs = 0L
        val bytesPerSecond = SAMPLE_RATE * 2 * 2

        while (isRecording) {
            val bytesRead = audioRecord?.read(inputBuffer, 0, bufferSize) ?: 0
            if (bytesRead <= 0) continue

            // 检测当前音量
            val amplitude = calculateAmplitude(inputBuffer, bytesRead)
            val now = SystemClock.elapsedRealtime()

            // 逻辑：如果还没开始录音，静音时等待，有声音时开始
            if (!isRecordingStarted) {
                if (amplitude >= SILENCE_AMPLITUDE_THRESHOLD) {
                    // 开始录音
                    isRecordingStarted = true
                    audioStartTime = now
                    currentFilePath = nextFilePath()
                    setupEncoder(currentFilePath)
                    presentationTimeUs = 0L
                    muxerStarted = false
                    audioTrackIndex = -1
                } else {
                    // 静音等待，不做任何处理
                    continue
                }
            }

            // 已开始录音后的处理
            if (isRecordingStarted) {
                // 判断是否需要分割（手动分割 或 静音分割）
                val shouldSplit = splitRequested ||
                        (silenceEnabled && checkSilence(inputBuffer, bytesRead))

                if (shouldSplit) {
                    splitRequested = false
                    silenceSince = 0L

                    // 只有录音时长超过1秒才保存文件
                    if (SystemClock.elapsedRealtime() - audioStartTime > 1000) {
                        val savedPath = currentFilePath
                        flushAndCloseMuxer(bufferInfo, muxerStarted, audioTrackIndex, presentationTimeUs)
                        notifyFileSplit(savedPath)
                        fileIndex++
                    }

                    // 开新文件
                    currentFilePath = nextFilePath()
                    setupEncoder(currentFilePath)
                    audioStartTime = SystemClock.elapsedRealtime()
                    presentationTimeUs = 0L
                    muxerStarted = false
                    audioTrackIndex = -1
                }

                // 送入编码器
                val inputIdx = mediaCodec?.dequeueInputBuffer(10_000) ?: -1
                if (inputIdx >= 0) {
                    val buf = mediaCodec?.getInputBuffer(inputIdx)
                    buf?.clear()
                    buf?.put(inputBuffer, 0, bytesRead)
                    presentationTimeUs += (bytesRead.toLong() * 1_000_000L) / bytesPerSecond
                    mediaCodec?.queueInputBuffer(inputIdx, 0, bytesRead, presentationTimeUs, 0)
                }

                // drain 输出
                val result = drainEncoder(bufferInfo, muxerStarted, audioTrackIndex)
                muxerStarted = result.first
                audioTrackIndex = result.second
            }
        }

        // EOS
        if (isRecordingStarted && mediaCodec != null) {
            val eosIdx = mediaCodec?.dequeueInputBuffer(10_000) ?: -1
            if (eosIdx >= 0) {
                mediaCodec?.queueInputBuffer(eosIdx, 0, 0, presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainUntilEOS(bufferInfo, muxerStarted, audioTrackIndex)
        }
        releaseResources()
    }

    // ----------------------------------------------------------------
    // 计算振幅
    // ----------------------------------------------------------------
    private fun calculateAmplitude(buffer: ByteArray, bytesRead: Int): Long {
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < bytesRead) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += abs(sample.toInt())
            count++
            i += 2
        }
        return if (count > 0) sum / count else 0L
    }

    // ----------------------------------------------------------------
    // 静音检测
    // ----------------------------------------------------------------
    private fun checkSilence(buffer: ByteArray, bytesRead: Int): Boolean {
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < bytesRead) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += abs(sample.toInt())
            count++
            i += 2
        }
        val amplitude = if (count > 0) sum / count else 0L
        val now = SystemClock.elapsedRealtime()

        return if (amplitude < SILENCE_AMPLITUDE_THRESHOLD) {
            if (silenceSince == 0L) silenceSince = now
            (now - silenceSince) >= silenceThresholdMs
        } else {
            silenceSince = 0L
            false
        }
    }

    // ----------------------------------------------------------------
    // flush 当前文件并关闭
    // ----------------------------------------------------------------
    private fun flushAndCloseMuxer(
        bufferInfo: MediaCodec.BufferInfo,
        muxerStarted: Boolean,
        audioTrackIndex: Int,
        presentationTimeUs: Long
    ) {
        try {
            val eosIdx = mediaCodec?.dequeueInputBuffer(5_000) ?: -1
            if (eosIdx >= 0) {
                mediaCodec?.queueInputBuffer(eosIdx, 0, 0, presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainUntilEOS(bufferInfo, muxerStarted, audioTrackIndex)
        } catch (_: Exception) {}
        try { mediaCodec?.stop(); mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
    }

    // ----------------------------------------------------------------
    // drain（非阻塞）
    // ----------------------------------------------------------------
    private fun drainEncoder(
        bufferInfo: MediaCodec.BufferInfo,
        muxerStarted: Boolean,
        audioTrackIndex: Int
    ): Pair<Boolean, Int> {
        var started = muxerStarted
        var trackIndex = audioTrackIndex
        var outputIdx = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: return Pair(started, trackIndex)

        while (outputIdx >= 0 || outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            when {
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) {
                        trackIndex = mediaMuxer!!.addTrack(mediaCodec!!.outputFormat)
                        mediaMuxer!!.start()
                        started = true
                    }
                }
                outputIdx >= 0 -> {
                    val outputBuf = mediaCodec?.getOutputBuffer(outputIdx)
                    if (outputBuf != null && started &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) &&
                        bufferInfo.size > 0
                    ) {
                        mediaMuxer?.writeSampleData(trackIndex, outputBuf, bufferInfo)
                    }
                    mediaCodec?.releaseOutputBuffer(outputIdx, false)
                }
            }
            outputIdx = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: break
        }
        return Pair(started, trackIndex)
    }

    // ----------------------------------------------------------------
    // drain 直到 EOS
    // ----------------------------------------------------------------
    private fun drainUntilEOS(
        bufferInfo: MediaCodec.BufferInfo,
        muxerStarted: Boolean,
        audioTrackIndex: Int
    ) {
        var started = muxerStarted
        var trackIndex = audioTrackIndex
        var eosReached = false
        while (!eosReached) {
            val outputIdx = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10_000) ?: break
            when {
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) {
                        trackIndex = mediaMuxer!!.addTrack(mediaCodec!!.outputFormat)
                        mediaMuxer!!.start()
                        started = true
                    }
                }
                outputIdx >= 0 -> {
                    val outputBuf = mediaCodec?.getOutputBuffer(outputIdx)
                    if (outputBuf != null && started &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) &&
                        bufferInfo.size > 0
                    ) {
                        mediaMuxer?.writeSampleData(trackIndex, outputBuf, bufferInfo)
                    }
                    mediaCodec?.releaseOutputBuffer(outputIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eosReached = true
                    }
                }
                else -> eosReached = true
            }
        }
        try { mediaMuxer?.stop(); mediaMuxer?.release() } catch (_: Exception) {}
        mediaMuxer = null
    }

    private fun setupEncoder(filePath: String) {
        try { mediaCodec?.stop(); mediaCodec?.release() } catch (_: Exception) {}
        try { mediaMuxer?.stop(); mediaMuxer?.release() } catch (_: Exception) {}
        
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        mediaCodec = MediaCodec.createEncoderByType(mime)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec?.start()
        mediaMuxer = MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun nextFilePath(): String {
        val dir = java.io.File(outputDir)
        if (!dir.exists()) dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val nanoTime = System.nanoTime() % 1000000
        return java.io.File(dir, "录音${fileIndex}_${stamp}_${nanoTime}.m4a").absolutePath
    }

    private fun notifyFileSplit(savedPath: String) {
        handler.post {
            sendBroadcast(Intent(ACTION_FILE_SPLIT).apply {
                putExtra(EXTRA_FILE_PATH, savedPath)
            })
        }
    }

    private fun stopCapture() {
        isRecording = false
        recordingThread?.join(4000)
        recordingThread = null
        isRecordingStarted = false
    }

    private fun releaseResources() {
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        handler.post {
            sendBroadcast(Intent(ACTION_RECORDING_STOPPED).apply {
                putExtra(EXTRA_FILE_PATH, currentFilePath)
            })
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "音频录制",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "系统内录服务通知"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        val splitIntent = PendingIntent.getService(this, 1,
            Intent(this, AudioCaptureService::class.java).apply { action = ACTION_SPLIT },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎵 系统音频录制")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_media_next, "✂️ 手动分割", splitIntent)
            .addAction(android.R.drawable.ic_media_pause, "⏹ 停止", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun sendError(msg: String) {
        handler.post {
            sendBroadcast(Intent(ACTION_ERROR).apply { putExtra(EXTRA_ERROR_MSG, msg) })
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRecording = false
        super.onDestroy()
    }
}
