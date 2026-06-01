package com.example.bilitranscript

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * 悬浮球服务
 * - 动态呼吸小球
 * - 面板可拖拽移动
 * - 点击展开输入/结果面板
 * - 后台处理文案提取
 * - 通知栏实时进度监控
 */
class FloatingBallService : Service() {

    companion object {
        const val ACTION_SHOW = "SHOW_BALL"
        const val ACTION_HIDE = "HIDE_BALL"
        const val ACTION_RESULT = "RESULT_READY"
        const val EXTRA_TRANSCRIPT = "transcript"
        private const val TAG = "FloatingBall"
        private const val NOTIF_CHANNEL = "floating_ball"
        private const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var panelView: View? = null
    private var resultView: View? = null
    private var monitorView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var breathAnimator: ValueAnimator? = null
    private var rotateAnimator: ValueAnimator? = null

    private var transcriptResult: String = ""
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private lateinit var notificationManager: NotificationManager

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESULT) {
                transcriptResult = intent.getStringExtra(EXTRA_TRANSCRIPT) ?: ""
                showResultView()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        startForeground()
        registerReceiver(resultReceiver, IntentFilter(ACTION_RESULT), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        removeAllViews()
        breathAnimator?.cancel()
        rotateAnimator?.cancel()
        scope.cancel()
        try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBall()
            ACTION_HIDE -> {
                removeAllViews()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL, "文案福特悬浮球",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        notificationBuilder = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("文案福特")
            .setContentText("悬浮球正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, 0, false)

        startForeground(NOTIF_ID, notificationBuilder.build())
    }

    private fun updateNotification(title: String, text: String, progress: Int = -1) {
        notificationBuilder.setContentTitle(title)
        notificationBuilder.setContentText(text)
        if (progress >= 0) {
            notificationBuilder.setProgress(100, progress, false)
        } else {
            notificationBuilder.setProgress(0, 0, false)
        }
        notificationManager.notify(NOTIF_ID, notificationBuilder.build())
    }

    private fun showBall() {
        if (ballView != null) return

        val size = dpToPx(56)
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(20)
            y = dpToPx(300)
        }

        ballView = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    0xFF4ECDC4.toInt(),
                    0xFF2E5E8C.toInt()
                )
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = size * 0.6f
            }
            elevation = dpToPx(8).toFloat()

            addView(TextView(context).apply {
                text = "✦"
                textSize = 24f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            setOnTouchListener(BallTouchListener(params))
            setOnClickListener { showPanel() }
        }

        windowManager.addView(ballView, params)
        startBreathAnimation()
    }

    private fun startBreathAnimation() {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0.85f, 1.15f).apply {
            duration = 1200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val v = ballView ?: return@addUpdateListener
                val scale = animator.animatedValue as Float
                v.scaleX = scale
                v.scaleY = scale
                v.postInvalidate()
                try {
                    val p = v.layoutParams as WindowManager.LayoutParams
                    windowManager.updateViewLayout(v, p)
                } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun startProcessingAnimation() {
        breathAnimator?.cancel()
        rotateAnimator?.cancel()
        ballView?.scaleX = 1f
        ballView?.scaleY = 1f

        rotateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val v = ballView ?: return@addUpdateListener
                v.rotation = animator.animatedValue as Float
                v.postInvalidate()
                try {
                    val p = v.layoutParams as WindowManager.LayoutParams
                    windowManager.updateViewLayout(v, p)
                } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun stopProcessingAnimation() {
        rotateAnimator?.cancel()
        ballView?.rotation = 0f
        startBreathAnimation()
    }

    private fun showPanel() {
        removePanel()
        removeResultView()
        removeMonitorView()

        val width = (resources.displayMetrics.widthPixels * 0.85).toInt()
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - width) / 2
            y = dpToPx(200)
        }

        val inflater = LayoutInflater.from(this)
        panelView = inflater.inflate(R.layout.floating_panel, null).apply {
            // 拖拽头
            findViewById<View>(R.id.dragHandle)?.setOnTouchListener(
                PanelDragListener(params, this)
            )
            findViewById<View>(R.id.btnClose).setOnClickListener { removePanel() }
            val etUrl = findViewById<EditText>(R.id.etUrl)
            findViewById<View>(R.id.btnSubmit).setOnClickListener {
                val url = etUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    removePanel()
                    startBackgroundExtract(url)
                } else {
                    Toast.makeText(this@FloatingBallService, "请输入链接", Toast.LENGTH_SHORT).show()
                }
            }
        }

        windowManager.addView(panelView, params)
    }

    private fun showMonitorView(phase: String, percent: Int) {
        if (monitorView == null) {
            val width = dpToPx(180)
            val params = WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(20)
                y = dpToPx(370)
            }

            val inflater = LayoutInflater.from(this)
            monitorView = inflater.inflate(R.layout.floating_monitor, null).apply {
                // 整个监控窗口可拖拽
                setOnTouchListener(PanelDragListener(params, this))
            }
            windowManager.addView(monitorView, params)
        }

        monitorView?.let { v ->
            v.findViewById<ProgressBar>(R.id.progressBar)?.progress = percent
            v.findViewById<TextView>(R.id.tvMonitorPhase)?.text = phase
            v.findViewById<TextView>(R.id.tvMonitorPercent)?.text = "$percent%"
            v.visibility = View.VISIBLE
            v.postInvalidate()
            try {
                windowManager.updateViewLayout(v, v.layoutParams)
            } catch (_: Exception) {}
        }
    }

    private fun hideMonitorView() {
        monitorView?.visibility = View.GONE
    }

    private fun removeMonitorView() {
        monitorView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            monitorView = null
        }
    }

    private fun startBackgroundExtract(url: String) {
        startProcessingAnimation()
        updateNotification("文案福特", "正在提取文案...", 0)
        showMonitorView("解析中", 0)

        scope.launch(Dispatchers.IO) {
            try {
                val ctx = this@FloatingBallService
                val pipeline = AppGraph.pipeline(ctx)

                val outcome = pipeline.extract(url) { fraction, phase ->
                    val percent = (fraction * 100).toInt()
                    scope.launch(Dispatchers.Main) {
                        updateNotification("文案福特", "$phase $percent%", percent)
                        showMonitorView(phase, percent)
                    }
                }

                // 自动入库历史
                if (AppGraph.settings(ctx).settings.value.saveHistory) {
                    val repo = AppGraph.history(ctx)
                    repo.add(
                        HistoryRecord(
                            id = repo.newId(),
                            bvid = outcome.bvid,
                            title = outcome.title,
                            text = outcome.text,
                            wordCount = outcome.wordCount,
                            durationSec = outcome.durationSec,
                            source = outcome.source.label,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }

                transcriptResult = outcome.text

                withContext(Dispatchers.Main) {
                    stopProcessingAnimation()
                    updateNotification("文案福特", "提取完成！点击复制", 100)
                    hideMonitorView()
                    showResultView()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    stopProcessingAnimation()
                    updateNotification("文案福特", "提取失败: ${e.message}", -1)
                    hideMonitorView()
                    Toast.makeText(this@FloatingBallService, "提取失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showResultView() {
        removePanel()
        removeResultView()

        val width = (resources.displayMetrics.widthPixels * 0.85).toInt()
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - width) / 2
            y = dpToPx(200)
        }

        val inflater = LayoutInflater.from(this)
        resultView = inflater.inflate(R.layout.floating_result, null).apply {
            // 拖拽头
            findViewById<View>(R.id.dragHandle)?.setOnTouchListener(
                PanelDragListener(params, this)
            )
            findViewById<TextView>(R.id.tvResult).text = transcriptResult
            findViewById<View>(R.id.btnCopy).setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("文案", transcriptResult))
                Toast.makeText(this@FloatingBallService, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            findViewById<View>(R.id.btnDownload).setOnClickListener {
                TranscriptSaver.save(this@FloatingBallService, "悬浮窗提取", transcriptResult)
            }
            findViewById<View>(R.id.btnCloseResult).setOnClickListener {
                removeResultView()
                updateNotification("文案福特", "悬浮球正在运行")
            }
        }

        windowManager.addView(resultView, params)
    }

    private fun removePanel() {
        panelView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            panelView = null
        }
    }

    private fun removeResultView() {
        resultView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            resultView = null
        }
    }

    private fun removeAllViews() {
        removePanel()
        removeResultView()
        removeMonitorView()
        ballView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            ballView = null
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // ===================== 触摸监听器 =====================

    /**
     * 悬浮球拖拽 + 点击监听
     */
    inner class BallTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var initX = 0
        private var initY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var isClick = true

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isClick = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isClick = false
                    }
                    params.x = initX + dx
                    params.y = initY + dy
                    windowManager.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP -> {
                    val screenWidth = resources.displayMetrics.widthPixels
                    val centerX = params.x + v.width / 2
                    val targetX = if (centerX < screenWidth / 2) dpToPx(10) else screenWidth - v.width - dpToPx(10)
                    animateBallToEdge(v, params, targetX)
                }
            }
            return false
        }

        private fun animateBallToEdge(v: View, params: WindowManager.LayoutParams, targetX: Int) {
            ValueAnimator.ofInt(params.x, targetX).apply {
                duration = 200
                addUpdateListener {
                    params.x = it.animatedValue as Int
                    try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
                }
                start()
            }
        }
    }

    /**
     * 面板拖拽监听器 - 整个面板可拖动
     */
    inner class PanelDragListener(
        private val params: WindowManager.LayoutParams,
        private val targetView: View
    ) : View.OnTouchListener {
        private var initX = 0
        private var initY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    params.x = initX + dx
                    params.y = initY + dy
                    try {
                        windowManager.updateViewLayout(targetView, params)
                    } catch (_: Exception) {}
                }
            }
            return true
        }
    }
}
