package com.cc.jumprope

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cc.jumprope.databinding.ActivityCountBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 计数页：
 *  - 计时模式(MODE_TIME)：固定时长，倒计时，统计个数。每 10 个报数，最后 10 秒报“还剩十秒”，到时总结。
 *  - 计数模式(MODE_COUNT)：固定目标个数，正计时。每 10 个报数，还剩 10 个时报已用时间，达标总结。
 *
 * 每跳一下播放一次短促提示音。
 * 计数方式：摄像头 + ML Kit 姿态识别自动检测跳跃（默认），或“手动 +1”按钮。
 *
 * 跳跃判定原理：追踪双肩中点的纵坐标，画面中人会周期性地向上移动。
 * 以躯干长度（肩-髋距离）为标尺做自适应阈值，向上位移超过阈值记为一次起跳，
 * 落回阈值以下后允许下一次计数。
 */
class CountActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_PLAYER = "extra_player"
        const val MODE_TIME = "time"
        const val MODE_COUNT = "count"

        /** 两次跳跃的最小间隔，防止一次动作算多次 */
        private const val JUMP_REFRACTORY_MS = 300L

        /** 最小有效振幅：脚踝离地高度 > 躯干长度 × 此比例 才算一次跳跃（漏记调小，误记调大） */
        private const val AMP_MIN_RATIO = 0.05f

        private const val REQ_CAMERA = 1001
    }

    private lateinit var binding: ActivityCountBinding

    private var mode = MODE_TIME
    private var playerName = "运动员"
    private var targetSeconds = 60
    private var targetCount = 100

    // ---- 运行状态 ----
    private var count = 0
    private var running = false
    private var finished = false
    private var startStamp = 0L          // 本次（继续）开始时刻
    private var accumulatedMs = 0L       // 暂停前已累计的时长
    private var tenSecondsAnnounced = false
    private var lastTenAnnounced = false

    // ---- 播报（内置语音片段 SoundPool 预加载，不依赖系统 TTS 引擎） ----
    private var tone: ToneGenerator? = null
    private var soundPool: SoundPool? = null
    private val clipSoundIds = HashMap<Int, Int>()   // resId -> soundId
    private val clipDurMs = HashMap<Int, Long>()     // resId -> 时长（用于片段衔接）
    private val clipQueue = ArrayDeque<Int>()
    private var announcing = false

    private val allClips = intArrayOf(
        R.raw.n0, R.raw.n1, R.raw.n2, R.raw.n3, R.raw.n4,
        R.raw.n5, R.raw.n6, R.raw.n7, R.raw.n8, R.raw.n9,
        R.raw.n10, R.raw.n100, R.raw.n1000,
        R.raw.ge, R.raw.miao, R.raw.fen,
        R.raw.say_start, R.raw.say_pause, R.raw.say_resume,
        R.raw.say_last10s, R.raw.say_last10n, R.raw.say_used,
        R.raw.say_timeup, R.raw.say_goal, R.raw.say_early,
        R.raw.say_total, R.raw.say_elapsed, R.raw.say_newrecord
    )

    // ---- 摄像头与姿态检测 ----
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var poseDetector: PoseDetector? = null
    private var filtY = Float.NaN         // 四点融合特征（3 帧均值滤波后）
    private var prevRaw1 = Float.NaN      // 均值滤波窗口
    private var prevRaw2 = Float.NaN
    private var cycleMin = Float.NaN      // 本周期最高点（画面 Y 最小）
    private var cycleMax = Float.NaN      // 本周期最低点（画面 Y 最大）
    private var cameraAbove = false       // 当前是否处于“腾空”状态
    private var lastJumpAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (running) {
                updateTimeUi()
                checkTimeEvents()
                handler.postDelayed(this, 100)
            }
        }
    }

    /**
     * 摄像头逐帧分析：四点融合特征（左肩+右肩+左髋+右髋纵坐标求和），
     * 单点抖动会被平均掉，是成熟跳绳计数产品（天天跳绳等）的同款方案。
     * 标尺为躯干长度（肩-髋距离）。
     */
    private val analyzer = object : ImageAnalysis.Analyzer {
        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        override fun analyze(proxy: ImageProxy) {
            val detector = poseDetector
            val media = proxy.image
            if (detector == null || media == null) {
                proxy.close()
                return
            }
            val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { pose ->
                    val ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                    val rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                    val lh = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
                    val rh = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
                    if (ls != null && rs != null && lh != null && rh != null) {
                        // 四点融合特征 t = h11 + h12 + h23 + h24
                        val feature = ls.position.y + rs.position.y +
                            lh.position.y + rh.position.y
                        val shoulderY = (ls.position.y + rs.position.y) * 0.5f
                        val hipY = (lh.position.y + rh.position.y) * 0.5f
                        val torso = abs(hipY - shoulderY)
                        if (torso > 1f) {
                            val now = SystemClock.elapsedRealtime()
                            handler.post { onPoseSample(feature, torso, now) }
                        }
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_TIME
        playerName = intent.getStringExtra(EXTRA_PLAYER)?.trim().orEmpty().ifBlank { "运动员" }
        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        initAudio()

        setupUi()
    }

    /** 预加载全部语音片段到内存，并测量每个片段时长用于无缝衔接 */
    private fun initAudio() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()

        val mmr = MediaMetadataRetriever()
        for (r in allClips) {
            try {
                val fd = resources.openRawResourceFd(r)
                mmr.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                clipDurMs[r] =
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                        ?: 400L
            } catch (e: Exception) {
                clipDurMs[r] = 400L
            }
        }
        mmr.release()

        for (r in allClips) clipSoundIds[r] = soundPool!!.load(this, r, 1)
    }

    // ---------------- UI 初始化 ----------------

    private fun setupUi() {
        if (mode == MODE_TIME) {
            binding.tvModeTitle.text = getString(R.string.title_mode_time)
            binding.layoutTimePickers.visibility = View.VISIBLE
            binding.layoutCountPicker.visibility = View.GONE
            binding.npMinute.minValue = 0
            binding.npMinute.maxValue = 60
            binding.npMinute.value = 1
            binding.npSecond.minValue = 0
            binding.npSecond.maxValue = 59
            binding.npSecond.value = 0
        } else {
            binding.tvModeTitle.text = getString(R.string.title_mode_count)
            binding.layoutTimePickers.visibility = View.GONE
            binding.layoutCountPicker.visibility = View.VISIBLE
            binding.npCount.minValue = 10
            binding.npCount.maxValue = 5000
            binding.npCount.value = 100
        }

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnPause.setOnClickListener { togglePause() }
        binding.btnStop.setOnClickListener { finishCounting(manual = true) }
        binding.btnManual.setOnClickListener { onJump() }
        binding.btnAgain.setOnClickListener { resetToSetup() }
        binding.btnBack.setOnClickListener { finish() }
    }

    // ---------------- 权限 ----------------

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun onStartClicked() {
        if (binding.switchSensor.isChecked && !hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        beginCountdown()
    }

    // ---------------- 开赛倒计时 ----------------

    private var countdownLeft = 0

    /** 3→2→1 倒数，语音同步播报，结束后正式开始计数 */
    private fun beginCountdown() {
        countdownLeft = 3
        binding.tvCountdown.visibility = View.VISIBLE
        stepCountdown()
    }

    private fun stepCountdown() {
        if (isFinishing || isDestroyed) return
        if (countdownLeft > 0) {
            binding.tvCountdown.text = countdownLeft.toString()
            speakParts(cnParts(countdownLeft))
            countdownLeft--
            handler.postDelayed({ stepCountdown() }, 1000)
        } else {
            binding.tvCountdown.visibility = View.GONE
            startCounting()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginCountdown()
            } else {
                Toast.makeText(this, R.string.toast_camera_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- 摄像头 ----------------

    private fun startCamera() {
        if (analysisExecutor == null) analysisExecutor = Executors.newSingleThreadExecutor()
        if (poseDetector == null) {
            poseDetector = PoseDetection.getClient(
                PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build()
            )
        }
        filtY = Float.NaN
        prevRaw1 = Float.NaN
        prevRaw2 = Float.NaN
        cycleMin = Float.NaN
        cycleMax = Float.NaN
        cameraAbove = false
        lastJumpAt = 0L
        binding.previewView.visibility = View.VISIBLE

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor!!, analyzer)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.toast_camera_fail, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        binding.previewView.visibility = View.GONE
    }

    /**
     * 姿态跳跃判定（主线程）。画面坐标 Y 向下为正，人向上跳时四点特征变小。
     * 流程：3 帧均值滤波 → 逐周期峰谷检测（追踪本周期最高点/最低点，
     * 从低点快速上移超过最小振幅记一次起跳，落回后重置周期）。
     * 四点求和特征的幅度约为身体位移的 4 倍，阈值以躯干长度 × 4 为标尺。
     */
    private fun onPoseSample(feature: Float, torso: Float, now: Long) {
        // 3 帧均值滤波（头两帧数据不足时直通）
        val filt = if (prevRaw1.isNaN() || prevRaw2.isNaN()) feature
        else (feature + prevRaw1 + prevRaw2) / 3f
        prevRaw2 = prevRaw1
        prevRaw1 = feature

        if (filtY.isNaN()) {
            filtY = filt
            cycleMin = filt
            cycleMax = filt
        }
        filtY = filt
        val ampMin = torso * AMP_MIN_RATIO * 4f

        if (!cameraAbove) {
            if (filtY > cycleMax) cycleMax = filtY
            if (filtY < cycleMin) cycleMin = filtY
            val range = cycleMax - cycleMin
            // 从低点快速上移（起跳）
            if (range > ampMin && filtY < cycleMax - range * 0.6f) {
                cameraAbove = true
                cycleMin = filtY
                if (running && !finished && now - lastJumpAt > JUMP_REFRACTORY_MS) {
                    lastJumpAt = now
                    onJump()
                }
            }
        } else {
            if (filtY < cycleMin) cycleMin = filtY
            if (filtY > cycleMax) cycleMax = filtY
            val range = cycleMax - cycleMin
            // 落回地面，准备下一次
            if (range > ampMin && filtY > cycleMin + range * 0.6f) {
                cameraAbove = false
                cycleMax = filtY
            }
        }
    }

    // ---------------- 流程控制 ----------------

    private fun startCounting() {
        if (mode == MODE_TIME) {
            targetSeconds = binding.npMinute.value * 60 + binding.npSecond.value
            if (targetSeconds < 10) {
                Toast.makeText(this, R.string.toast_min_time, Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            targetCount = binding.npCount.value
        }

        count = 0
        accumulatedMs = 0L
        startStamp = SystemClock.elapsedRealtime()
        running = true
        finished = false
        tenSecondsAnnounced = false
        lastTenAnnounced = false

        binding.groupSetup.visibility = View.GONE
        binding.groupResult.visibility = View.GONE
        binding.groupRunning.visibility = View.VISIBLE
        binding.btnPause.text = getString(R.string.btn_pause)
        binding.tvTargetHint.text = if (mode == MODE_TIME)
            getString(R.string.hint_target_time, formatClock(targetSeconds * 1000L))
        else
            getString(R.string.hint_target_count, targetCount)

        updateCountUi()
        updateTimeUi()
        if (binding.switchSensor.isChecked) startCamera()
        speakParts(listOf(R.raw.say_start))
        handler.post(ticker)
    }

    private fun togglePause() {
        if (finished) return
        if (running) {
            accumulatedMs += SystemClock.elapsedRealtime() - startStamp
            running = false
            binding.btnPause.text = getString(R.string.btn_resume)
            speakParts(listOf(R.raw.say_pause))
        } else {
            startStamp = SystemClock.elapsedRealtime()
            running = true
            binding.btnPause.text = getString(R.string.btn_pause)
            speakParts(listOf(R.raw.say_resume))
            handler.post(ticker)
        }
    }

    private fun finishCounting(manual: Boolean) {
        if (finished) return
        finished = true
        if (running) {
            accumulatedMs += SystemClock.elapsedRealtime() - startStamp
            running = false
        }
        stopCamera()

        val msg = when {
            mode == MODE_TIME && !manual ->
                getString(R.string.result_time_up, count)
            mode == MODE_TIME ->
                getString(R.string.result_early_stop, count, formatSpokenTime(accumulatedMs))
            !manual ->
                getString(R.string.result_goal_done, targetCount, formatSpokenTime(accumulatedMs))
            else ->
                getString(R.string.result_early_stop, count, formatSpokenTime(accumulatedMs))
        }
        // 语音播报结果
        val parts = mutableListOf<Int>()
        when {
            mode == MODE_TIME && !manual -> parts.add(R.raw.say_timeup)
            mode == MODE_TIME -> parts.add(R.raw.say_early)
            !manual -> parts.add(R.raw.say_goal)
            else -> parts.add(R.raw.say_early)
        }
        parts.add(R.raw.say_total)
        parts.addAll(cnParts(count))
        parts.add(R.raw.ge)
        parts.add(R.raw.say_elapsed)
        parts.addAll(timeParts(accumulatedMs))

        // 保存成绩并判断是否新纪录（至少跳 1 个才记录）
        var resultText = msg
        if (count > 0) {
            val target = if (mode == MODE_TIME) targetSeconds else targetCount
            val rec = ScoreStore.Record(
                playerName, mode, target, count, accumulatedMs, System.currentTimeMillis()
            )
            val newBest = ScoreStore.isNewBest(this, rec)
            ScoreStore.addRecord(this, rec)
            if (newBest) {
                resultText += "\n" + getString(R.string.result_new_record)
                parts.add(R.raw.say_newrecord)
            } else {
                val best = ScoreStore.best(this, playerName, mode, target)
                if (best != null) {
                    val bestText = if (mode == MODE_TIME)
                        "${best.count} 个"
                    else
                        formatSpokenTime(best.durationMs)
                    resultText += "\n" + getString(R.string.result_best_line, bestText)
                }
            }
        }
        speakParts(parts)

        binding.tvResult.text = resultText
        binding.groupRunning.visibility = View.GONE
        binding.groupResult.visibility = View.VISIBLE
    }

    private fun resetToSetup() {
        stopCamera()
        count = 0
        accumulatedMs = 0L
        finished = false
        running = false
        binding.groupRunning.visibility = View.GONE
        binding.groupResult.visibility = View.GONE
        binding.groupSetup.visibility = View.VISIBLE
    }

    // ---------------- 计数 ----------------

    private fun onJump() {
        if (!running || finished) return
        count++
        beep()
        updateCountUi()

        // 每跳 10 个报数一次
        if (count % 10 == 0) speakParts(cnParts(count) + R.raw.ge)

        if (mode == MODE_COUNT) {
            val remain = targetCount - count
            // 还剩 10 个时播报已用时间
            if (remain == 10 && !lastTenAnnounced) {
                lastTenAnnounced = true
                speakParts(listOf(R.raw.say_last10n, R.raw.say_used) + timeParts(elapsedMs()))
            }
            if (remain <= 0) finishCounting(manual = false)
        }
    }

    /** 计时模式的定时检查：最后 10 秒播报、到时结束 */
    private fun checkTimeEvents() {
        if (mode != MODE_TIME || finished) return
        val remainMs = targetSeconds * 1000L - elapsedMs()
        if (remainMs <= 10_000L && !tenSecondsAnnounced) {
            tenSecondsAnnounced = true
            speakParts(listOf(R.raw.say_last10s))
        }
        if (remainMs <= 0) finishCounting(manual = false)
    }

    // ---------------- 播报与提示音 ----------------

    /** 每跳一下的短促提示音（低延迟，不走语音播报队列） */
    private fun beep() {
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 60)
        } catch (_: Exception) {
        }
    }

    // ---------------- 内置语音播报 ----------------

    private val digitRes = intArrayOf(
        R.raw.n0, R.raw.n1, R.raw.n2, R.raw.n3, R.raw.n4,
        R.raw.n5, R.raw.n6, R.raw.n7, R.raw.n8, R.raw.n9
    )

    /** 中文数字 0~9999 → 语音片段序列，如 250 → [二, 百, 五, 十] */
    private fun cnParts(n: Int): List<Int> {
        if (n < 10) return listOf(digitRes[n])
        if (n < 100) {
            val t = n / 10; val u = n % 10
            return buildList {
                if (t > 1) add(digitRes[t])
                add(R.raw.n10)
                if (u > 0) add(digitRes[u])
            }
        }
        if (n < 1000) {
            val h = n / 100; val r = n % 100; val t = r / 10; val u = r % 10
            return buildList {
                add(digitRes[h]); add(R.raw.n100)
                if (r in 1..9) {
                    add(R.raw.n0); add(digitRes[u])
                } else if (r >= 10) {
                    if (t > 1) add(digitRes[t])
                    add(R.raw.n10)
                    if (u > 0) add(digitRes[u])
                }
            }
        }
        val th = n / 1000; val r = n % 1000; val h = r / 100; val t = (r % 100) / 10; val u = r % 10
        return buildList {
            add(digitRes[th]); add(R.raw.n1000)
            if (r == 0) return@buildList
            if (h > 0) {
                add(digitRes[h]); add(R.raw.n100)
            } else {
                add(R.raw.n0)
            }
            if (t == 0 && u > 0) {
                if (h > 0) add(R.raw.n0)
                add(digitRes[u])
            } else if (t > 0) {
                if (t > 1) add(digitRes[t])
                add(R.raw.n10)
                if (u > 0) add(digitRes[u])
            }
        }
    }

    /** 时长 → 语音片段：X 分 Y 秒 */
    private fun timeParts(ms: Long): List<Int> {
        val totalSec = ms / 1000
        val min = (totalSec / 60).toInt()
        val sec = (totalSec % 60).toInt()
        return buildList {
            if (min > 0) { addAll(cnParts(min)); add(R.raw.fen) }
            if (sec > 0 || min == 0) { addAll(cnParts(sec)); add(R.raw.miao) }
        }
    }

    /** 把语音片段加入队列顺序播放 */
    private fun speakParts(parts: List<Int>) {
        clipQueue.addAll(parts)
        if (!announcing) playNextClip()
    }

    private fun playNextClip() {
        val res = clipQueue.removeFirstOrNull()
        if (res == null) {
            announcing = false
            return
        }
        announcing = true
        val sp = soundPool
        val sid = clipSoundIds[res]
        if (sp == null || sid == null || sid == 0 || sp.play(sid, 1f, 1f, 1, 0, 1f) == 0) {
            playNextClip()
            return
        }
        // 按实测时长衔接下一片段，留 40ms 自然间隔
        handler.postDelayed({ playNextClip() }, (clipDurMs[res] ?: 400L) + 40L)
    }

    // ---------------- 工具 ----------------

    private fun elapsedMs(): Long =
        accumulatedMs + if (running) SystemClock.elapsedRealtime() - startStamp else 0L

    private fun updateCountUi() {
        binding.tvCount.text = count.toString()
    }

    private fun updateTimeUi() {
        binding.tvTimer.text = formatClock(elapsedMs())
    }

    private fun formatClock(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun formatSpokenTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return when {
            min <= 0 -> getString(R.string.spoken_seconds, sec)
            sec == 0L -> getString(R.string.spoken_minutes, min)
            else -> getString(R.string.spoken_minutes_seconds, min, sec)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        stopCamera()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        poseDetector?.close()
        poseDetector = null
        tone?.release()
        tone = null
        soundPool?.release()
        soundPool = null
        clipQueue.clear()
        super.onDestroy()
    }
}
