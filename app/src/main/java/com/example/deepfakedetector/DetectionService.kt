package com.example.deepfakedetector

import android.app.*
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.exp

class DetectionService : Service() {

    companion object {
        const val CHANNEL_ID = "DeepfakeChannel"
        private const val NOTIFICATION_ID = 1
        private const val INPUT_SIZE = 224
        private const val FRAME_COUNT = 16
        private const val FACE_INTERVAL_MS = 250L
        private const val ALERT_COOLDOWN_MS = 3000L
    }

    private lateinit var classifier: TFLiteClassifier
    private lateinit var imageReader: ImageReader
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var overlayView: android.view.View? = null

    private val frameBuffer = ArrayDeque<FloatArray>(FRAME_COUNT)
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var faceDetectionRunning = false
    private var lastFrameTime = 0L
    private var lastAlertTime = 0L
    private var lastSeenResetTime: Long = 0L   // from ScrollEventBus

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    // ─────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        classifier = TFLiteClassifier(this)
        Log.d("SERVICE", "Classifier ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Deepfake Detector Active")
                .setContentText("Monitoring screen for deepfakes...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setSilent(true)
                .build()
        )

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data")
            ?: return START_NOT_STICKY

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        handler = Handler(handlerThread.looper)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("SERVICE", "MediaProjection stopped")
                stopSelf()
            }
        }, handler)

        startCapture()
        return START_STICKY
    }

    // ─────────────────────────────────────────
    private fun startCapture() {
        val metrics = resources.displayMetrics

        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DeepfakeCapture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            val now = System.currentTimeMillis()
            if (now - lastFrameTime < FACE_INTERVAL_MS || faceDetectionRunning) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            val bitmap = imageToBitmap(image)
            image.close()

            faceDetectionRunning = true
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    Log.d("FACE", "Faces: ${faces.size}")
                    if (faces.isNotEmpty()) {
                        val face = faces.maxByOrNull {
                            it.boundingBox.width() * it.boundingBox.height()
                        }!!
                        val faceBitmap = cropFace(bitmap, face.boundingBox)
                        val frame = bitmapToFloatArray(faceBitmap)
                        faceBitmap.recycle()
                        addFrame(frame)
                    }
                    bitmap.recycle()
                }
                .addOnFailureListener { e ->
                    Log.e("FACE", "Failed", e)
                    bitmap.recycle()
                }
                .addOnCompleteListener {
                    faceDetectionRunning = false
                }
        }, handler)
    }

    // ─────────────────────────────────────────
    private fun addFrame(frame: FloatArray) {
        synchronized(frameBuffer) {
            if (ScrollEventBus.lastResetTime > lastSeenResetTime) {
                frameBuffer.clear()
                lastSeenResetTime = ScrollEventBus.lastResetTime
            }

            if (frameBuffer.size == FRAME_COUNT) frameBuffer.removeFirst()
            frameBuffer.addLast(frame)

            // ✅ Pad with duplicates for faster first inference
            if (frameBuffer.size in 4..15) {
                val current = frameBuffer.toList()
                while (frameBuffer.size < FRAME_COUNT) {
                    frameBuffer.addLast(current.last())
                }
            }

            Log.d("BUFFER", "Buffer size: ${frameBuffer.size}/$FRAME_COUNT")

            if (frameBuffer.size == FRAME_COUNT) {
                runInference()
                frameBuffer.clear()
            }
        }
    }


    // ─────────────────────────────────────────
    private fun runInference() {
        val frames: List<FloatArray>
        synchronized(frameBuffer) {
            frames = frameBuffer.toList()
        }
        if (frames.size < FRAME_COUNT) return

        inferenceExecutor.execute {
            try {
                val inputBuffer = prepareInputBuffer(frames)
                inputBuffer.rewind()
                val dbg = FloatArray(10)
                inputBuffer.asFloatBuffer().get(dbg)
                Log.d("TENSOR", "First 10: ${dbg.map { "%.3f".format(it) }}")
                inputBuffer.rewind()

                val logit = classifier.predict(inputBuffer)
                val prob = sigmoid(logit)

                val label = when {
                    prob > 0.4f -> "FAKE 🔥"
                    else -> "REAL ✅"
                }

                val probStr = "%.0f%%".format(prob * 100)
                Log.e("DEEPFAKE", "$label | Logit=${String.format("%.4f", logit)} | Prob=$probStr")

                mainHandler.post { handleResult(label, probStr, prob) }

            } catch (e: Exception) {
                Log.e("DEEPFAKE", "Inference error", e)
            }
        }
    }

    // ─────────────────────────────────────────
    private fun handleResult(label: String, probStr: String, prob: Float) {
        val now = System.currentTimeMillis()

        val manager = getSystemService(NotificationManager::class.java)
        val silent = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Deepfake Detector Active")
            .setContentText("Last: $label ($probStr)")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .build()
        manager?.notify(NOTIFICATION_ID, silent)

        if (prob > 0.4f) {
            if (now - lastAlertTime < ALERT_COOLDOWN_MS) return
            lastAlertTime = now
            showOverlay(label, probStr)
        }
    }

    // ─────────────────────────────────────────
    private fun showOverlay(label: String, prob: String) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w("OVERLAY", "No overlay permission")
            return
        }

        removeOverlay()

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        val bgColor = if (label.contains("FAKE"))
            Color.argb(230, 200, 0, 0)
        else
            Color.argb(230, 200, 100, 0)

        val view = TextView(this).apply {
            text = "$label  ($prob)"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(bgColor)
            setPadding(48, 24, 48, 24)
        }

        overlayView = view
        wm.addView(view, params)
        mainHandler.postDelayed({ removeOverlay() }, 3000)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    // ─────────────────────────────────────────
    private fun prepareInputBuffer(frames: List<FloatArray>): ByteBuffer {
        val cSize = 3
        val hSize = INPUT_SIZE
        val wSize = INPUT_SIZE
        val tSize = FRAME_COUNT

        val buffer = ByteBuffer
            .allocateDirect(cSize * hSize * wSize * tSize * 4)
            .order(ByteOrder.nativeOrder())

        for (c in 0 until cSize)
            for (h in 0 until hSize)
                for (w in 0 until wSize)
                    for (t in 0 until tSize) {
                        val v = frames.getOrNull(t)
                            ?.get(c * hSize * wSize + h * wSize + w) ?: 0f
                        buffer.putFloat(v)
                    }

        buffer.rewind()
        return buffer
    }

    private fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        resized.recycle()

        val out = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[0 * INPUT_SIZE * INPUT_SIZE + i] = (((p shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
            out[1 * INPUT_SIZE * INPUT_SIZE + i] = (((p shr 8)  and 0xFF) / 255f - 0.456f) / 0.224f
            out[2 * INPUT_SIZE * INPUT_SIZE + i] = (( p         and 0xFF) / 255f - 0.406f) / 0.225f
        }
        return out
    }

    private fun cropFace(bitmap: Bitmap, r: Rect): Bitmap {
        val x      = r.left.coerceAtLeast(0)
        val y      = r.top.coerceAtLeast(0)
        val right  = r.right.coerceAtMost(bitmap.width)
        val bottom = r.bottom.coerceAtMost(bitmap.height)
        val w = (right - x).coerceAtLeast(1)
        val h = (bottom - y).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane       = image.planes[0]
        val buffer      = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride   = plane.rowStride
        val rowPadding  = rowStride - pixelStride * image.width

        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    // ─────────────────────────────────────────
    override fun onDestroy() {
        removeOverlay()
        try { imageReader.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (::handlerThread.isInitialized) handlerThread.quitSafely()
        inferenceExecutor.shutdown()
        if (::classifier.isInitialized) classifier.close()
        super.onDestroy()
        Log.d("SERVICE", "Destroyed")
    }

    override fun onBind(intent: Intent?) = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.deleteNotificationChannel(CHANNEL_ID)
            val channel = NotificationChannel(
                CHANNEL_ID, "Deepfake Detection",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(false)
                setSound(null, null)
            }
            manager?.createNotificationChannel(channel)
        }
    }
}
