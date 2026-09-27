package com.palmpay.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Camera
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val cameraRequest = 1001
    private var camera: Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(PalmCameraBridge(), "PalmCamera")
        web.addJavascriptInterface(UpiBridge(), "PalmUPI")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url?.startsWith("upi://") == true) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                    return true
                }
                return false
            }
        }
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
    }

    private fun openPalmCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraRequest)
        } else showNativeCamera()
    }

    private fun showNativeCamera() {
        try {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT)
            val surface = SurfaceView(this)
            val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
            root.addView(surface, FrameLayout.LayoutParams(-1, -1))

            val title = TextView(this).apply {
                text = "PALM SCAN\nKeep your palm inside the frame"
                textSize = 19f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(24, 28, 24, 28)
                setBackgroundColor(0x99000000.toInt())
            }
            root.addView(title, FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })

            val capture = Button(this).apply {
                text = "CAPTURE PALM"
                textSize = 16f
                setOnClickListener { capturePalm() }
            }
            root.addView(capture, FrameLayout.LayoutParams(-1, 62.dp()).apply {
                gravity = Gravity.BOTTOM
                setMargins(28, 0, 28, 34)
            })

            val cancel = Button(this).apply {
                text = "Cancel"
                setOnClickListener { closeCamera(true) }
            }
            root.addView(cancel, FrameLayout.LayoutParams(150.dp(), 55.dp()).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, 108.dp())
            })

            setContentView(root)
            surface.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    try {
                        camera?.setDisplayOrientation(90)
                        camera?.setPreviewDisplay(holder)
                        camera?.startPreview()
                    } catch (_: Exception) {
                        closeCamera(false)
                        reportCameraError("Could not start the camera preview.")
                    }
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    try { camera?.stopPreview() } catch (_: Exception) {}
                }
            })
        } catch (_: Exception) {
            closeCamera(false)
            reportCameraError("Camera could not be opened.")
        }
    }

    private fun capturePalm() {
        try {
            camera?.takePicture(null, null) { data, _ ->
                runOnUiThread {
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bitmap == null) {
                        closeCamera(false)
                        reportCameraError("The camera did not return an image.")
                        return@runOnUiThread
                    }
                    val rotated = rotateBitmap(bitmap, 90f)
                    val output = ByteArrayOutputStream()
                    rotated.compress(Bitmap.CompressFormat.JPEG, 82, output)
                    val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    closeCamera(false)
                    setContentView(web)
                    web.evaluateJavascript("window.palmCaptured('data:image/jpeg;base64,$encoded')", null)
                    if (rotated !== bitmap) rotated.recycle()
                    bitmap.recycle()
                }
            }
        } catch (_: Exception) {
            closeCamera(false)
            reportCameraError("Could not capture the palm.")
        }
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        return try {
            val matrix = Matrix()
            matrix.postRotate(degrees)
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (_: Exception) { source }
    }

    private fun closeCamera(cancelled: Boolean) {
        try { camera?.stopPreview() } catch (_: Exception) {}
        try { camera?.release() } catch (_: Exception) {}
        camera = null
        if (cancelled) {
            setContentView(web)
            web.evaluateJavascript("window.cameraCancelled()", null)
        }
    }

    private fun reportCameraError(message: String) {
        setContentView(web)
        web.evaluateJavascript("window.cameraError('" + message.replace("'", "\\'") + "')", null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) showNativeCamera()
            else {
                Toast.makeText(this, "Camera permission is needed for Palm Verify.", Toast.LENGTH_LONG).show()
                reportCameraError("Camera permission was denied.")
            }
        }
    }

    override fun onDestroy() {
        closeCamera(false)
        super.onDestroy()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    inner class PalmCameraBridge {
        @JavascriptInterface fun open() { runOnUiThread { openPalmCamera() } }
    }

    inner class UpiBridge {
        @JavascriptInterface fun openPayment(uriString: String) {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
                    if (intent.resolveActivity(packageManager) == null) {
                        web.evaluateJavascript("window.upiError('No UPI app is installed on this phone.')", null)
                        return@runOnUiThread
                    }
                    startActivity(Intent.createChooser(intent, "Choose UPI app"))
                } catch (_: ActivityNotFoundException) {
                    web.evaluateJavascript("window.upiError('No compatible UPI app was found.')", null)
                } catch (_: Exception) {
                    web.evaluateJavascript("window.upiError('Could not open the UPI payment app.')", null)
                }
            }
        }
    }
}
