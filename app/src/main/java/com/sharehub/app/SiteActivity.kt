package com.sharehub.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SiteActivity : Activity() {

    class SharedFile(val uri: Uri, val name: String, val type: String)
    class Dl(val url: String, val name: String, val mime: String, val ua: String, val len: Long)
    class Target(val os: OutputStream, val discard: () -> Unit)

    companion object {
        const val REQ_CHOOSE = 21
        const val REQ_TREE = 22
        const val PREFIX = "/__sharehub__/"

        // Runs inside the PC software's page: finds its "choose file" input, fills it with the
        // shared files (streamed from the phone) and fires the page's own change handler,
        // so the page's normal upload code (progress bar, PIN session, ...) does the rest.
        const val INJECT_JS = """
(function(){
  if (window.__shTry) return;
  window.__shBusy = false; window.__shDone = false;
  window.__shTry = function(files){
    if (window.__shDone) return 'done';
    if (window.__shBusy) return 'busy';
    var inputs = Array.prototype.slice.call(document.querySelectorAll('input[type=file]'));
    var inp = null;
    for (var i = 0; i < inputs.length; i++) {
      var p = inputs[i].parentElement;
      if (p && p.getClientRects().length > 0) { inp = inputs[i]; break; }
    }
    if (!inp) return 'wait';
    window.__shBusy = true;
    Promise.all(files.map(function(f){
      return fetch(f.u).then(function(r){ return r.blob(); }).then(function(b){
        return new File([b], f.n, {type: f.t || b.type});
      });
    })).then(function(fs){
      var dt = new DataTransfer();
      fs.forEach(function(f){ dt.items.add(f); });
      inp.files = dt.files;
      inp.dispatchEvent(new Event('change', {bubbles: true}));
      window.__shDone = true; window.__shBusy = false;
      AndroidShare.done(fs.length, '');
    }).catch(function(e){
      window.__shBusy = false;
      AndroidShare.done(0, String(e));
    });
    return 'started';
  };
})();
"""
    }

    private lateinit var web: WebView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var site: Site? = null
    private var shared: List<SharedFile> = emptyList()
    private var injected = false
    private var pollStart = 0L
    private var chooserCb: ValueCallback<Array<Uri>>? = null
    private var pendingDl: Dl? = null

    // ------------------------------------------------------------ setup
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        banner = LinearLayout(this)
        banner.orientation = LinearLayout.HORIZONTAL
        banner.gravity = Gravity.CENTER_VERTICAL
        banner.setBackgroundColor(Color.parseColor("#1E3A8A"))
        banner.setPadding(dp(14), dp(10), dp(14), dp(10))
        banner.visibility = View.GONE
        bannerText = TextView(this)
        bannerText.setTextColor(Color.WHITE)
        bannerText.textSize = 13f
        banner.addView(bannerText, LinearLayout.LayoutParams(0, WRAP, 1f))
        val close = TextView(this)
        close.text = "✕"
        close.setTextColor(Color.WHITE)
        close.setPadding(dp(12), 0, 0, 0)
        close.setOnClickListener {
            injected = true
            banner.visibility = View.GONE
        }
        banner.addView(close)
        root.addView(banner, LinearLayout.LayoutParams(MATCH, WRAP))

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        setupWeb()
        handleIntent(intent)
    }

    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.setBackgroundColor(BG)
        web.addJavascriptInterface(Bridge(), "AndroidShare")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                site?.let { if (keepOn(it)) persistCookies(it) }
            }

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                val mine = site?.let { Uri.parse(it.url).host }
                if ((u.scheme == "http" || u.scheme == "https") && u.host != mine) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: Exception) {
                    }
                    return true
                }
                return false
            }

            // serves the shared files to the page (same origin, so no CORS trouble)
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val p = r.url.path ?: return null
                if (!p.startsWith(PREFIX)) return null
                val idx = p.removePrefix(PREFIX).toIntOrNull() ?: return null
                val f = shared.getOrNull(idx) ?: return null
                return try {
                    val ins = contentResolver.openInputStream(f.uri) ?: return null
                    WebResourceResponse(
                        f.type, null, 200, "OK",
                        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), ins
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams
            ): Boolean {
                chooserCb?.onReceiveValue(null)
                chooserCb = cb
                return try {
                    startActivityForResult(p.createIntent(), REQ_CHOOSE)
                    true
                } catch (e: Exception) {
                    chooserCb = null
                    false
                }
            }
        }

        web.setDownloadListener { url, ua, cd, mime, len -> onDownload(url, ua, cd, mime, len) }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    // ----------------------------------------------------------- intents
    private fun handleIntent(i: Intent) {
        val isShare = i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE
        val sites = Store.all(this)
        if (!isShare) {
            val s = sites.firstOrNull { it.id == i.getStringExtra("site") }
            if (s == null) {
                finish()
                return
            }
            openSite(s, emptyList())
            return
        }
        val files = readShared(i)
        if (files.isEmpty()) {
            toast("Only files can be shared here")
            finish()
            return
        }
        if (sites.isEmpty()) {
            toast("Open Share Hub and scan your PC software's QR first")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val sid = i.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
        val direct = sites.firstOrNull { it.id == sid }
        when {
            direct != null -> openSite(direct, files)
            sites.size == 1 -> openSite(sites[0], files)
            else -> showPicker(sites, files)
        }
    }

    /** Lists EVERY scanned software (with its icon), however many there are. */
    private fun showPicker(sites: List<Site>, files: List<SharedFile>) {
        val adapter = object : ArrayAdapter<Site>(this, 0, sites) {
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
                val s = getItem(pos)!!
                val row = LinearLayout(context)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(20), dp(10), dp(20), dp(10))
                val img = ImageView(context)
                img.setImageBitmap(Store.iconBitmap(context, s))
                row.addView(img, LinearLayout.LayoutParams(dp(44), dp(44)))
                val tv = TextView(context)
                tv.text = s.name
                tv.textSize = 17f
                tv.setPadding(dp(14), 0, 0, 0)
                row.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Send ${files.size} file(s) to…")
            .setAdapter(adapter) { _, w -> openSite(sites[w], files) }
            .setOnCancelListener { finish() }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun readShared(i: Intent): List<SharedFile> {
        val uris = LinkedHashSet<Uri>()
        val cd = i.clipData
        if (cd != null) {
            for (k in 0 until cd.itemCount) {
                val u = cd.getItemAt(k).uri
                if (u != null) uris.add(u)
            }
        }
        try {
            val one = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (one != null) uris.add(one)
        } catch (e: Exception) {
        }
        try {
            val many = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (many != null) uris.addAll(many)
        } catch (e: Exception) {
        }
        val out = ArrayList<SharedFile>()
        for (u in uris) {
            var name = ""
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: ""
                }
            } catch (e: Exception) {
            }
            if (name.isEmpty()) name = u.lastPathSegment ?: "file"
            val type = contentResolver.getType(u) ?: i.type ?: "application/octet-stream"
            if (!name.contains('.')) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
                if (ext != null) name = "$name.$ext"
            }
            out.add(SharedFile(u, name, type))
        }
        return out
    }

    // ------------------------------------------------------ open a site
    private fun openSite(s: Site, files: List<SharedFile>) {
        site = s
        shared = files
        injected = false
        if (files.isNotEmpty()) Store.reportUsed(this, s.id)
        handler.removeCallbacksAndMessages(null)
        if (!Store.keepAsked(this, s.id) && !keepOn(s)) handler.postDelayed(keepWatch, 2000)
        if (files.isEmpty()) {
            banner.visibility = View.GONE
            web.loadUrl(s.url)
            return
        }
        showBanner("Sending ${files.size} file(s) to ${s.name}. Enter the PIN if asked…")
        pollStart = System.currentTimeMillis()
        if (keepOn(s)) {
            // "keep me logged in" was chosen: reuse the saved login, no PIN
            web.loadUrl(s.url)
        } else {
            // otherwise start fresh, so the PIN is asked on every share
            CookieManager.getInstance().removeAllCookies { web.loadUrl(s.url) }
        }
        handler.postDelayed(poll, 1500)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (injected || shared.isEmpty()) return
            if (System.currentTimeMillis() - pollStart > 15 * 60 * 1000) {
                showBanner("Timed out. Share the files again.")
                return
            }
            val arr = JSONArray()
            shared.forEachIndexed { i, f ->
                arr.put(JSONObject().put("u", PREFIX + i).put("n", f.name).put("t", f.type))
            }
            web.evaluateJavascript(INJECT_JS + "window.__shTry(" + arr.toString() + ");", null)
            handler.postDelayed(this, 700)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun done(n: Int, err: String) {
            runOnUiThread {
                injected = true
                handler.removeCallbacks(poll)
                site?.let { if (keepOn(it)) persistCookies(it) }
                if (err.isEmpty()) {
                    showBanner("✓ $n file(s) handed to the page", 4000)
                } else {
                    showBanner("Could not hand over the files: $err")
                }
            }
        }
    }

    // --------------------------------------------------- keep me logged in
    /** Read fresh from storage, so the button in the main screen takes effect at once. */
    private fun keepOn(s: Site): Boolean = Store.all(this).firstOrNull { it.id == s.id }?.keep == true

    /** Makes the login cookie long-lived, so it survives closing the app. */
    private fun persistCookies(s: Site) {
        try {
            val cm = CookieManager.getInstance()
            val ck = cm.getCookie(s.url) ?: return
            for (part in ck.split(";")) {
                val kv = part.trim()
                if (kv.contains("=")) cm.setCookie(s.url, "$kv; Max-Age=31536000; Path=/")
            }
            cm.flush()
        } catch (e: Exception) {
        }
    }

    // After the first successful PIN login the page gets a cookie: that is the moment to ask, once.
    private val keepWatch = object : Runnable {
        override fun run() {
            val s = site ?: return
            if (Store.keepAsked(this@SiteActivity, s.id) || keepOn(s)) return
            val ck = CookieManager.getInstance().getCookie(s.url)
            if (!ck.isNullOrEmpty() && !isFinishing) {
                askKeep(s)
                return
            }
            handler.postDelayed(this, 1500)
        }
    }

    private fun askKeep(s: Site) {
        AlertDialog.Builder(this)
            .setTitle("Keep me logged in?")
            .setMessage("You will not need the PIN again on this phone, until you turn it off or remove ${s.name}.")
            .setCancelable(false)
            .setPositiveButton("Keep me logged in") { _, _ ->
                Store.setKeep(this, s.id, true)
                persistCookies(s)
            }
            .setNegativeButton("No, ask every time") { _, _ ->
                // only remember the answer; the current session keeps working
                Store.setKeep(this, s.id, false, false)
            }
            .show()
    }

    override fun onPause() {
        site?.let { if (keepOn(it)) persistCookies(it) }
        super.onPause()
    }

    private fun showBanner(t: String, hideAfter: Long = 0) {
        bannerText.text = t
        banner.visibility = View.VISIBLE
        if (hideAfter > 0) handler.postDelayed({ banner.visibility = View.GONE }, hideAfter)
    }

    // ------------------------------------------------------- downloads
    private fun fmt(n: Long): String {
        if (n <= 0) return ""
        val u = arrayOf("B", "KB", "MB", "GB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < 3) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, u[i])
    }

    /** File name exactly as the PC sent it (never changes the extension). */
    private fun cdName(cd: String?): String? {
        if (cd == null) return null
        val ic = RegexOption.IGNORE_CASE
        val star = Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", ic).find(cd)
        if (star != null) return Uri.decode(star.groupValues[1].trim().trim('"'))
        val quoted = Regex("filename\\s*=\\s*\"([^\"]+)\"", ic).find(cd)
        if (quoted != null) return quoted.groupValues[1]
        val plain = Regex("filename\\s*=\\s*([^;]+)", ic).find(cd)
        if (plain != null) return plain.groupValues[1].trim()
        return null
    }

    private fun fileName(url: String, cd: String?, mime: String?): String {
        var n = cdName(cd)
        if (n.isNullOrBlank()) n = Uri.parse(url).lastPathSegment
        // last resort only; guessFileName may change the extension, so no mime is passed
        if (n.isNullOrBlank()) n = URLUtil.guessFileName(url, null, null)
        val clean = (n ?: "file").substringAfterLast('/').substringAfterLast('\\')
        return clean.replace(Regex("[:*?\"<>|]"), "_").ifBlank { "file" }
    }

    private fun realMime(name: String, mime: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (m != null) return m
        }
        if (mime != null && mime.isNotBlank() && mime != "application/octet-stream") return mime
        return "application/octet-stream"
    }

    private fun isStream(d: Dl) = d.mime.startsWith("video/") || d.mime.startsWith("audio/")

    private fun viewIntent(d: Dl, uri: Uri): Intent {
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, d.mime)
        return i
    }

    private fun canOpen(d: Dl): Boolean {
        val probe = if (isStream(d)) Uri.parse(d.url)
        else Uri.parse("content://$packageName.files/open/" + Uri.encode(d.name))
        return packageManager.queryIntentActivities(viewIntent(d, probe), 0).isNotEmpty()
    }

    private fun onDownload(url: String, ua: String, cd: String?, mime: String?, len: Long) {
        val name = fileName(url, cd, mime)
        val d = Dl(url, name, realMime(name, mime), ua, len)
        val viewable = canOpen(d)
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (viewable) {
            labels.add("Open with…")
            acts.add { openFile(d) }
        }
        labels.add("Save")
        acts.add { save(d, false) }
        labels.add("Save to folder…")
        acts.add { save(d, true) }

        var title = name
        if (len > 0) title += "\n" + fmt(len)
        if (!viewable) title += "\nNo installed app can open this file type"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, w -> acts[w]() }
            .show()
    }

    private fun openFile(d: Dl) {
        if (isStream(d)) {
            // video/audio: hand the link to the player, nothing is downloaded
            chooseView(viewIntent(d, Uri.parse(d.url)), d)
            return
        }
        val dir = File(cacheDir, "open")
        dir.deleteRecursively()
        dir.mkdirs()
        val out = File(dir, d.name)
        runDownload(d, "Preparing ${d.name}…", { Target(FileOutputStream(out)) { out.delete() } }) { ok ->
            if (ok) {
                val u = FileProvider.getUriForFile(this, "$packageName.files", out)
                val i = viewIntent(d, u)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.clipData = ClipData.newRawUri(d.name, u)
                chooseView(i, d)
            }
        }
    }

    private fun chooseView(i: Intent, d: Dl) {
        try {
            startActivity(Intent.createChooser(i, "Open ${d.name} with"))
        } catch (e: Exception) {
            toast("No app can open this file")
        }
    }

    private fun save(d: Dl, pick: Boolean) {
        val sid = site?.id ?: return
        if (pick) {
            pendingDl = d
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            i.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            startActivityForResult(i, REQ_TREE)
            return
        }
        val saved = getSharedPreferences("folders", 0).getString(sid, null)
        if (saved != null) {
            val tree = Uri.parse(saved)
            val ok = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
            if (ok) {
                saveToTree(d, tree)
                return
            }
        }
        saveToDownloads(d)
    }

    private fun saveToDownloads(d: Dl) {
        val folder = (site?.name ?: "ShareHub").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        runDownload(d, "Saving ${d.name}…", {
            val v = ContentValues()
            v.put(MediaStore.Downloads.DISPLAY_NAME, d.name)
            v.put(MediaStore.Downloads.MIME_TYPE, d.mime)
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("Cannot create the file")
            val os = contentResolver.openOutputStream(uri) ?: throw IOException("Cannot write the file")
            Target(os) { contentResolver.delete(uri, null, null) }
        }) { ok -> if (ok) toast("Saved in Download/$folder") }
    }

    private fun saveToTree(d: Dl, tree: Uri) {
        runDownload(d, "Saving ${d.name}…", {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(contentResolver, parent, d.mime, d.name)
                ?: throw IOException("Cannot create the file in that folder")
            val os = contentResolver.openOutputStream(doc) ?: throw IOException("Cannot write the file")
            Target(os) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, doc)
                } catch (e: Exception) {
                }
            }
        }) { ok -> if (ok) toast("Saved in the chosen folder") }
    }

    /** Streams the file from the PC to [opener]'s target, with a progress window and Cancel. */
    private fun runDownload(d: Dl, title: String, opener: () -> Target, done: (Boolean) -> Unit) {
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.max = 100
        pb.isIndeterminate = d.len <= 0
        val pad = FrameLayout(this)
        pad.setPadding(dp(20), dp(10), dp(20), dp(4))
        pad.addView(pb)
        val cancel = AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(pad)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancel.set(true) }
            .create()
        dlg.show()

        Thread {
            var ok = false
            var err = ""
            var tgt: Target? = null
            try {
                val c = URL(d.url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", d.ua)
                val ck = CookieManager.getInstance().getCookie(d.url)
                if (ck != null) c.setRequestProperty("Cookie", ck)
                if (c.responseCode !in 200..299) throw IOException("Server said " + c.responseCode)
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else d.len
                if (total > 0) runOnUiThread { pb.isIndeterminate = false }
                c.inputStream.use { ins ->
                    val t = opener()
                    tgt = t
                    t.os.use { os ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        var last = -1
                        while (true) {
                            if (cancel.get()) throw IOException("Cancelled")
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            got += n
                            if (total > 0) {
                                val pct = (got * 100 / total).toInt()
                                if (pct != last) {
                                    last = pct
                                    runOnUiThread { pb.progress = pct }
                                }
                            }
                        }
                    }
                }
                ok = true
            } catch (e: Exception) {
                err = e.message ?: "Failed"
                try {
                    tgt?.let { it.discard() }
                } catch (x: Exception) {
                }
            }
            runOnUiThread {
                try {
                    dlg.dismiss()
                } catch (e: Exception) {
                }
                if (!ok) toast("Not saved: $err")
                done(ok)
            }
        }.start()
    }

    // --------------------------------------------------- activity results
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_CHOOSE) {
            chooserCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data))
            chooserCb = null
        } else if (req == REQ_TREE) {
            val d = pendingDl
            pendingDl = null
            val tree = data?.data
            if (res == RESULT_OK && tree != null && d != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                }
                getSharedPreferences("folders", 0).edit().putString(site?.id ?: "", tree.toString()).apply()
                saveToTree(d, tree)
            }
        }
    }
}
