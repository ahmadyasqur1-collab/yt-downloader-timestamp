package com.ahmady.ytdownloader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppServer.uiGone = false
        window.statusBarColor = 0xFF12141D.toInt()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        web = WebView(this).apply {
            setBackgroundColor(0xFF12141D.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
        setContentView(web)

        startForegroundService(Intent(this, DownloadService::class.java))
        Thread {
            AppServer.start(applicationContext)
            runOnUiThread { web.loadUrl("http://127.0.0.1:${AppServer.PORT}/") }
        }.start()
    }

    override fun onDestroy() {
        AppServer.uiGone = true
        if (isFinishing) AppServer.maybeStopService()
        web.destroy()
        super.onDestroy()
    }
}
