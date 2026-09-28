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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.ByteArrayOutputStream

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val cameraRequest = 1001
    private var camera: Camera? = null
    private var pendingPhonePeUri: String? = null

    private fun scanRecipientQr() {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(this, options)
                .startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue
                    if (raw.isNullOrBlank()) {
                        web.evaluateJavascript("window.qrScanError('The QR code did not contain readable payment data.')", null)
                    } else {
                        val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        web.evaluateJavascript("window.qrScanned(atob('$encoded'))", null)
                    }
                }
                .addOnCanceledListener {
                    web.evaluateJavascript("window.qrScanCancelled()", null)
                }
                .addOnFailureListener {
                    web.evaluateJavascript("window.qrScanError('Could not scan this QR code. Try a clear UPI QR.')", null)
                }
        } catch (_: Exception) {
            web.evaluateJavascript("window.qrScanError('QR scanner is unavailable on this device.')", null)
        }
    }

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
                text = if (pendingPhonePeUri != null)
                    "PALM VERIFY\nVerify palm before opening PhonePe"
                else
                    "PALM SCAN\nKeep your palm inside the frame"
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
                    val paymentUri = pendingPhonePeUri
                    pendingPhonePeUri = null
                    closeCamera(false)
                    setContentView(web)
                    web.evaluateJavascript("window.palmCaptured('data:image/jpeg;base64,$encoded')", null)
                    if (paymentUri != null) {
                        web.evaluateJavascript("window.palmPaymentVerified()", null)
                        openPhonePePayment(paymentUri)
                    }
                    if (rotated !== bitmap) rotated.recycle()
                    bitmap.recycle()
                }
            }
        } catch (_: Exception) {
            pendingPhonePeUri = null
            closeCamera(false)
            reportCameraError("Could not capture the palm.")
        }
    }

    private fun openPhonePePayment(uriString: String) {
        runOnUiThread {
            try {
                val uri = Uri.parse(uriString)

                // Prefer PhonePe when it is installed.
                val phonePeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.phonepe.app")
                }
                if (phonePeIntent.resolveActivity(packageManager) != null) {
                    startActivity(phonePeIntent)
                    return@runOnUiThread
                }

                // If PhonePe is unavailable, fall back to any installed UPI app.
                val genericIntent = Intent(Intent.ACTION_VIEW, uri)
                if (genericIntent.resolveActivity(packageManager) != null) {
                    startActivity(Intent.createChooser(genericIntent, "Choose an installed UPI app"))
                    return@runOnUiThread
                }

                web.evaluateJavascript(
                    "window.upiNoAppFound('No UPI app is installed. Install an authorised UPI app, then try again.')",
                    null
                )
            } catch (_: ActivityNotFoundException) {
                web.evaluateJavascript(
                    "window.upiNoAppFound('No compatible UPI app was found on this phone.')",
                    null
                )
            } catch (_: Exception) {
                web.evaluateJavascript(
                    "window.upiError('Could not open a UPI payment app.')",
                    null
                )
            }
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
            pendingPhonePeUri = null
            setContentView(web)
            web.evaluateJavascript("window.cameraCancelled()", null)
        }
    }

    private fun reportCameraError(message: String) {
        pendingPhonePeUri = null
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

        @JavascriptInterface fun scanQr() { runOnUiThread { scanRecipientQr() } }

        @JavascriptInterface fun openForPayment(uriString: String) {
            runOnUiThread {
                if (!uriString.startsWith("upi://pay")) {
                    web.evaluateJavascript("window.upiError('Invalid UPI payment request.')", null)
                    return@runOnUiThread
                }
                pendingPhonePeUri = uriString
                openPalmCamera()
            }
        }
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
