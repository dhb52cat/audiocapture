package com.audiocapture

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.audiocapture.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isRecording = false
    private var recordingStartTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    // 静音检测设置
    private var silenceEnabled = true
    private var silenceThresholdMs = 1500L  // 默认1.5秒

    // 已分割的文件列表
    private val splitFiles = mutableListOf<String>()
    private var hasRecordingStarted = false  // 标记是否真正开始过录音

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) startMediaProjection()
        else showToast("需要录音权限才能继续")
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startRecordingService(result.resultCode, result.data!!)
        } else {
            showToast("未授权屏幕录制，无法捕获系统音频")
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioCaptureService.ACTION_RECORDING_STARTED -> {
                    isRecording = true
                    splitFiles.clear()
                    hasRecordingStarted = true
                    updateUI()
                    startTimer()
                }
                AudioCaptureService.ACTION_RECORDING_STOPPED -> {
                    isRecording = false
                    updateUI()
                    stopTimer()
                    if (hasRecordingStarted) {
                        hasRecordingStarted = false
                        val path = intent.getStringExtra(AudioCaptureService.EXTRA_FILE_PATH)
                        if (path != null) splitFiles.add(path)
                        refreshRecordingList()
                        showToast("录音已停止，共保存 ${splitFiles.size} 个文件")
                    }
                }
                AudioCaptureService.ACTION_FILE_SPLIT -> {
                    val path = intent.getStringExtra(AudioCaptureService.EXTRA_FILE_PATH)
                    if (path != null) {
                        splitFiles.add(path)
                        binding.tvLastFile.text = "已分割：${File(path).name}"
                        binding.tvSplitCount.text = "已分割 ${splitFiles.size} 个文件"
                        showToast("✂️ 已保存：${File(path).name}")
                    }
                }
                AudioCaptureService.ACTION_ERROR -> {
                    val msg = intent.getStringExtra(AudioCaptureService.EXTRA_ERROR_MSG) ?: "未知错误"
                    showToast("错误：$msg")
                    isRecording = false
                    updateUI()
                    stopTimer()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        setupUI()
        refreshRecordingList()

        val filter = IntentFilter().apply {
            addAction(AudioCaptureService.ACTION_RECORDING_STARTED)
            addAction(AudioCaptureService.ACTION_RECORDING_STOPPED)
            addAction(AudioCaptureService.ACTION_FILE_SPLIT)
            addAction(AudioCaptureService.ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
        stopTimer()
    }

    private fun setupUI() {
        // 开始/停止录音
        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else checkPermissionsAndStart()
        }

        // 手动分割
        binding.btnSplit.setOnClickListener {
            if (isRecording) {
                val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_SPLIT
                }
                startService(serviceIntent)
                showToast("✂️ 手动分割中...")
            } else {
                showToast("请先开始录音")
            }
        }

        // 静音检测开关
        binding.switchSilence.isChecked = silenceEnabled
        binding.switchSilence.setOnCheckedChangeListener { _, checked ->
            silenceEnabled = checked
            binding.layoutSilenceConfig.visibility =
                if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }

        // 静音时长设置
        binding.btnSilenceConfig.setOnClickListener {
            showSilenceConfigDialog()
        }

        updateSilenceLabel()
        updateUI()
    }

    private fun showSilenceConfigDialog() {
        val options = arrayOf("0.5 秒", "1 秒", "1.5 秒（默认）", "2 秒", "3 秒", "5 秒")
        val values = longArrayOf(500, 1000, 1500, 2000, 3000, 5000)
        val currentIndex = values.indexOfFirst { it == silenceThresholdMs }.coerceAtLeast(2)

        AlertDialog.Builder(this)
            .setTitle("静音触发间隔")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                silenceThresholdMs = values[which]
                updateSilenceLabel()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateSilenceLabel() {
        val label = when (silenceThresholdMs) {
            500L -> "0.5 秒"
            1000L -> "1 秒"
            1500L -> "1.5 秒"
            2000L -> "2 秒"
            3000L -> "3 秒"
            5000L -> "5 秒"
            else -> "${silenceThresholdMs}ms"
        }
        binding.btnSilenceConfig.text = "静音间隔：$label"
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) startMediaProjection()
        else permissionLauncher.launch(notGranted.toTypedArray())
    }

    private fun startMediaProjection() {
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val outputDir = getOutputDir().absolutePath
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_DATA, data)
            putExtra(AudioCaptureService.EXTRA_OUTPUT_DIR, outputDir)
            putExtra(AudioCaptureService.EXTRA_SILENCE_MS, silenceThresholdMs)
            putExtra(AudioCaptureService.EXTRA_SILENCE_ENABLED, silenceEnabled)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopRecording() {
        startService(Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        })
    }

    private fun updateUI() {
        if (isRecording) {
            binding.btnRecord.text = "⏹ 停止录音"
            binding.btnRecord.setBackgroundColor(getColor(R.color.stop_color))
            binding.btnSplit.isEnabled = true
            binding.btnSplit.alpha = 1f
            binding.tvStatus.text = "● 正在录制系统音频..."
            binding.tvStatus.setTextColor(getColor(R.color.recording_color))
            binding.waveView.startAnimation()
            binding.layoutSettings.visibility = android.view.View.GONE
            binding.tvSplitCount.text = "已分割 0 个文件"
            binding.tvSplitCount.visibility = android.view.View.VISIBLE
        } else {
            binding.btnRecord.text = "● 开始录音"
            binding.btnRecord.setBackgroundColor(getColor(R.color.start_color))
            binding.btnSplit.isEnabled = false
            binding.btnSplit.alpha = 0.4f
            binding.tvStatus.text = "准备就绪"
            binding.tvStatus.setTextColor(getColor(R.color.ready_color))
            binding.waveView.stopAnimation()
            binding.tvTimer.text = "00:00"
            binding.layoutSettings.visibility = android.view.View.VISIBLE
            binding.tvSplitCount.visibility = android.view.View.GONE
        }
    }

    private fun startTimer() {
        recordingStartTime = SystemClock.elapsedRealtime()
        timerRunnable = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - recordingStartTime
                val seconds = (elapsed / 1000).toInt()
                binding.tvTimer.text = String.format("%02d:%02d", seconds / 60, seconds % 60)
                handler.postDelayed(this, 500)
            }
        }
        timerRunnable?.let { handler.post(it) }
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun refreshRecordingList() {
        val dir = getOutputDir()
        val files = dir.listFiles { f -> f.name.endsWith(".m4a") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        binding.tvRecordingCount.text = "共 ${files.size} 个录音文件"
        if (files.isNotEmpty()) {
            binding.tvLastFile.text = "最新：${files.first().name}"
        } else {
            binding.tvLastFile.text = "尚无录音"
        }
    }

    private fun getOutputDir(): File {
        val dir = File(getExternalFilesDir(null), "Recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
