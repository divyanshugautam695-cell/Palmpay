package com.palmpay.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import android.widget.Toast
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val cameraRequest = 1001
    private val cameraCapture = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(PalmCameraBridge(), "PalmCamera")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url?.startsWith("upi://") == true) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
            return
        }
        launchCamera()
    }

    private fun launchCamera() {
        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (intent.resolveActivity(packageManager) != null) {
                startActivityForResult(intent, cameraCapture)
            } else {
                web.evaluateJavascript("window.cameraError('No camera app is available on this phone.')", null)
            }
        } catch (e: Exception) {
            web.evaluateJavascript("window.cameraError('Unable to open the camera.')", null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                Toast.makeText(this, "Camera permission is needed for Palm Verify.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Android API  Activity Result APIs are preferred, but this keeps the prototype simple.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != cameraCapture) return

        if (resultCode == RESULT_OK) {
            val bitmap = data?.extras?.get("data") as? Bitmap
            if (bitmap != null) {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                web.evaluateJavascript("window.palmCaptured('data:image/jpeg;base64,$encoded')", null)
            } else {
                web.evaluateJavascript("window.cameraError('The camera did not return an image.')", null)
            }
        } else {
            web.evaluateJavascript("window.cameraCancelled()", null)
        }
    }

    inner class PalmCameraBridge {
        @JavascriptInterface
        fun open() {
            runOnUiThread { openPalmCamera() }
        }
    }
}
