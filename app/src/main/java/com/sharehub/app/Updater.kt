package com.sharehub.app

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    private const val PREFS = "settings"
    private var lastCheck = 0L

    fun isAuto(c: Context) = c.getSharedPreferences(PREFS, 0).getBoolean("auto_update", true)
    fun setAuto(c: Context, on: Boolean) = c.getSharedPreferences(PREFS, 0).edit().putBoolean("auto_update", on).apply()

    /** manual = true: always check and always tell the result. manual = false: silent unless an update exists. */
    fun check(a: Activity, manual: Boolean) {
        if (!manual) {
            if (!isAuto(a) || System.currentTimeMillis() - lastCheck < 60_000) return
        }
        lastCheck = System.currentTimeMillis()
        if (BuildConfig.GH_REPO.isEmpty()) {
            if (manual) a.toast("Update check works only for builds made by GitHub Actions")
            return
        }
        if (manual) a.toast("Checking for update…")
        Thread {
            try {
                val c = URL("https://api.github.com/repos/${BuildConfig.GH_REPO}/releases/latest")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val num = j.getString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
                val assets = j.getJSONArray("assets")
                var apk: String? = null
                for (i in 0 until assets.length()) {
                    val o = assets.getJSONObject(i)
                    if (o.getString("name").endsWith(".apk")) apk = o.getString("browser_download_url")
                }
                a.runOnUiThread {
                    if (apk != null && num > BuildConfig.VERSION_CODE) ask(a, num, apk)
                    else if (manual) a.toast("You have the latest version (build ${BuildConfig.VERSION_CODE})")
                }
            } catch (e: Exception) {
                if (manual) a.runOnUiThread { a.toast("Update check failed: ${e.message}") }
            }
        }.start()
    }

    private fun ask(a: Activity, num: Int, url: String) {
        if (a.isFinishing) return
        AlertDialog.Builder(a)
            .setTitle("Update available")
            .setMessage("New build $num is ready (you have ${BuildConfig.VERSION_CODE}).")
            .setPositiveButton("Update now") { _, _ -> download(a, url) }
            .setNegativeButton("Later", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun download(a: Activity, url: String) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            a.toast("Allow \"Install unknown apps\" for Share Hub, then tap Update again")
            a.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
            )
            return
        }
        val dlg = ProgressDialog(a)
        dlg.setMessage("Downloading update…")
        dlg.isIndeterminate = false
        dlg.max = 100
        dlg.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dlg.setCancelable(false)
        dlg.show()
        Thread {
            var err: String? = null
            val dir = File(a.cacheDir, "open").apply { mkdirs() }
            val f = File(dir, "update.apk")
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 20000
                val total = c.contentLengthLong
                c.inputStream.use { ins ->
                    f.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            got += n
                            if (total > 0) a.runOnUiThread { dlg.progress = (got * 100 / total).toInt() }
                        }
                    }
                }
            } catch (e: Exception) {
                err = e.message ?: "failed"
            }
            a.runOnUiThread {
                dlg.dismiss()
                if (err != null) {
                    a.toast("Download failed: $err")
                } else {
                    val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", f)
                    val i = Intent(Intent.ACTION_VIEW)
                    i.setDataAndType(uri, "application/vnd.android.package-archive")
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    a.startActivity(i)
                }
            }
        }.start()
    }
}
