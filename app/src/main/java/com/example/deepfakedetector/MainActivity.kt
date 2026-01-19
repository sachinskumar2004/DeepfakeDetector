package com.example.deepfakedetector
import android.provider.Settings
import android.net.Uri

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private val SCREEN_CAPTURE_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        val startButton = findViewById<Button>(R.id.startButton)

        startButton.setOnClickListener {
            Toast.makeText(
                this,
                "Deepfake detection started",
                Toast.LENGTH_SHORT
            ).show()
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                SCREEN_CAPTURE_REQUEST_CODE
            )

        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE &&
            resultCode == Activity.RESULT_OK &&
            data != null
        ) {
            val intent = Intent(this, DetectionService::class.java)
            intent.putExtra("resultCode", resultCode)
            intent.putExtra("data", data)

            startForegroundService(intent)
        }
    }

}
