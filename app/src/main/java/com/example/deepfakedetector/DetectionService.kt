package com.example.deepfakedetector

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer

class DetectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var imageReader: ImageReader

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private var projectionStarted = false

    // ⏱ Frame sampling
    private var lastProcessedTime = 0L
    private val FRAME_INTERVAL_MS = 2000L   // 2 seconds

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        Log.d("DetectionService", "onStartCommand called")

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Deepfake Detection Running")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        if (projectionStarted || intent == null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra("resultCode", -1)
        val data = intent.getParcelableExtra<Intent>("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e("DetectionService", "MediaProjection permission missing")
            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        handlerThread = HandlerThread("ScreenCaptureThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("DetectionService", "MediaProjection stopped")
                stopSelf()
            }
        }, handler)

        projectionStarted = true
        startScreenCapture()

        return START_NOT_STICKY
    }

    private fun startScreenCapture() {

        val metrics = resources.displayMetrics

        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler
        )

        imageReader.setOnImageAvailableListener({ reader ->

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessedTime < FRAME_INTERVAL_MS) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            lastProcessedTime = currentTime

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            val bitmap = imageToBitmapRGBA(image)
            image.close()

            if (bitmap != null) {
                Log.d(
                    "ScreenCapture",
                    "Bitmap captured every 2s: ${bitmap.width} x ${bitmap.height}"
                )

                // 👉 NEXT STEP: Face Detection / TFLite
            }

        }, handler)
    }

    /**
     * SAFE RGBA → Bitmap conversion (NO JNI CRASH)
     */
    private fun imageToBitmapRGBA(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            bitmap.copyPixelsFromBuffer(buffer)

            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            Log.e("BitmapConvert", "RGBA to Bitmap failed", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        imageReader.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        mediaProjection?.stop()
        handlerThread.quitSafely()

        Log.d("DetectionService", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Deepfake Detection Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "DeepfakeDetectionChannel"
        private const val NOTIFICATION_ID = 1
    }
}
