package com.sharehub.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private var iconFor: String? = null

    companion object {
        const val REQ_ICON = 11
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(24), dp(18), dp(32))
        scroll.addView(col)

        val title = TextView(this)
        title.text = "Share Hub"
        title.textSize = 26f
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        col.addView(title)

        val sub = TextView(this)
        sub.text = "Scan the QR of your PC software once. It then shows up in the Share menu of every app."
        sub.textSize = 13f
        sub.setTextColor(MUTED)
        sub.setPadding(0, dp(6), 0, 0)
        col.addView(sub)

        val scanBtn = pill("＋  Scan QR", ACCENT) { startScan() }
        scanBtn.textSize = 17f
        scanBtn.setPadding(dp(20), dp(14), dp(20), dp(14))
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.topMargin = dp(18)
        slp.bottomMargin = dp(18)
        col.addView(scanBtn, slp)

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list)

        setContentView(scroll)
        // re-publish every share icon on each start, so the Share menu is always complete
        Thread { Store.syncShortcuts(this) }.start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val sites = Store.all(this)
        if (sites.isEmpty()) {
            val t = TextView(this)
            t.text = "No software added yet."
            t.setTextColor(MUTED)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(24), 0, 0)
            list.addView(t)
            return
        }
        for (s in sites) list.addView(row(s))
    }

    private fun row(s: Site): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(14), dp(14), dp(12))
        card.background = rounded(CARD, 16)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(12)
        card.layoutParams = lp

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setOnClickListener { openSite(s) }

        val img = ImageView(this)
        img.setImageBitmap(Store.iconBitmap(this, s))
        val r = dp(12).toFloat()
        img.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
        img.clipToOutline = true
        top.addView(img, LinearLayout.LayoutParams(dp(52), dp(52)))

        val tx = LinearLayout(this)
        tx.orientation = LinearLayout.VERTICAL
        tx.setPadding(dp(12), 0, dp(8), 0)
        val nm = TextView(this)
        nm.text = s.name
        nm.textSize = 17f
        nm.setTextColor(Color.WHITE)
        nm.typeface = Typeface.DEFAULT_BOLD
        val ur = TextView(this)
        ur.text = s.url
        ur.textSize = 12f
        ur.setTextColor(MUTED)
        tx.addView(nm)
        tx.addView(ur)
        top.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))

        val go = TextView(this)
        go.text = "Open ›"
        go.setTextColor(ACCENT)
        go.typeface = Typeface.DEFAULT_BOLD
        top.addView(go)
        card.addView(top)

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        val blp = LinearLayout.LayoutParams(MATCH, WRAP)
        blp.topMargin = dp(12)
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(btns)
        card.addView(hs, blp)
        val items = listOf(
            pill("Icon", PURPLE) { pickIcon(s) },
            pill("Home", GREY) { pinHome(s) },
            pill(if (s.keep) "Keep login: ON" else "Keep login: OFF", if (s.keep) ACCENT else GREY) { toggleKeep(s) },
            pill("Remove", RED) { confirmRemove(s) }
        )
        for (p in items) {
            val l = LinearLayout.LayoutParams(WRAP, WRAP)
            l.rightMargin = dp(8)
            btns.addView(p, l)
        }
        return card
    }

    private fun openSite(s: Site) {
        val i = Intent(this, SiteActivity::class.java)
        i.action = Intent.ACTION_VIEW
        i.putExtra("site", s.id)
        startActivity(i)
    }

    private fun toggleKeep(s: Site) {
        Store.setKeep(this, s.id, !s.keep)
        toast(if (s.keep) "Login will be asked every time" else "You will stay logged in")
        refresh()
    }

    private fun pickIcon(s: Site) {
        iconFor = s.id
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        startActivityForResult(i, REQ_ICON)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_ICON && res == RESULT_OK) {
            val uri = data?.data
            val id = iconFor
            if (uri != null && id != null && Store.saveIcon(this, id, uri)) {
                Store.syncShortcuts(this)
                refresh()
                toast("Icon updated")
            } else {
                toast("Could not read that image")
            }
        }
    }

    private fun pinHome(s: Site) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm != null && sm.isRequestPinShortcutSupported) {
            sm.requestPinShortcut(Store.info(this, s, "pin_", 0), null)
        } else {
            toast("Your launcher does not support pinning")
        }
    }

    private fun confirmRemove(s: Site) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${s.name}?")
            .setMessage("It will disappear from the Share menu. Your PC software is not touched.")
            .setPositiveButton("Remove") { _, _ ->
                Store.remove(this, s.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------- QR
    private fun startScan() {
        val opts = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { code -> onQr(code.rawValue ?: "") }
            .addOnFailureListener { e -> toast("Scan failed: ${e.message}") }
    }

    private fun onQr(raw: String) {
        val url = raw.trim().trimEnd('/')
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("This QR is not a web address")
            return
        }
        if (Store.all(this).any { it.url == url }) {
            toast("Already added")
            return
        }
        toast("Reading software name…")
        Thread {
            val title = Net.title(url)
            runOnUiThread {
                if (title == null) toast("Could not reach it (same Wi-Fi?). You can still add it.")
                askName(url, title ?: (Uri.parse(url).host ?: url))
            }
        }.start()
    }

    private fun askName(url: String, def: String) {
        val box = EditText(this)
        box.setText(def.take(30))
        box.setSingleLine()
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.addView(box)
        AlertDialog.Builder(this)
            .setTitle("Name")
            .setView(wrap)
            .setPositiveButton("Add") { _, _ ->
                val name = box.text.toString().trim().ifEmpty { def.take(30) }
                Store.add(this, Site(UUID.randomUUID().toString().take(8), name, url, false))
                refresh()
                toast("Added. It is now in the Share menu.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
