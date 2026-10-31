package com.sharehub.app

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Site(val id: String, val name: String, val url: String)

object Store {
    const val CATEGORY = "com.sharehub.app.category.SHARE"
    private const val PREFS = "sites"

    fun all(ctx: Context): MutableList<Site> {
        val out = mutableListOf<Site>()
        try {
            val arr = JSONArray(ctx.getSharedPreferences(PREFS, 0).getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Site(o.getString("id"), o.getString("name"), o.getString("url")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun save(ctx: Context, list: List<Site>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url))
        }
        ctx.getSharedPreferences(PREFS, 0).edit().putString("list", arr.toString()).apply()
    }

    fun add(ctx: Context, site: Site) {
        val l = all(ctx)
        l.add(site)
        save(ctx, l)
        syncShortcuts(ctx)
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
        iconFile(ctx, id).delete()
        ctx.getSharedPreferences("folders", 0).edit().remove(id).apply()
        syncShortcuts(ctx)
        try {
            val sm = ctx.getSystemService(ShortcutManager::class.java)
            if (sm != null) {
                if (Build.VERSION.SDK_INT >= 30) sm.removeLongLivedShortcuts(listOf(id))
                sm.disableShortcuts(listOf("pin_$id"), "Removed")
            }
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------- icons
    fun iconFile(ctx: Context, id: String): File {
        val dir = File(ctx.filesDir, "icons")
        dir.mkdirs()
        return File(dir, "$id.png")
    }

    fun iconBitmap(ctx: Context, s: Site): Bitmap {
        val f = iconFile(ctx, s.id)
        if (f.exists()) {
            val b = BitmapFactory.decodeFile(f.path)
            if (b != null) return b
        }
        return letterIcon(s.name)
    }

    private fun letterIcon(name: String): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val hue = (Math.abs(name.hashCode()) % 360).toFloat()
        p.color = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.color = Color.WHITE
        p.textSize = 110f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val ch = name.trim().take(1).uppercase().ifEmpty { "?" }
        c.drawText(ch, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }

    /** Picks any image, crops it to a square and keeps it as this software's icon. */
    fun saveIcon(ctx: Context, id: String, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 600) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (src == null) {
                false
            } else {
                val side = minOf(src.width, src.height)
                val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                val out = Bitmap.createScaledBitmap(sq, 192, 192, true)
                iconFile(ctx, id).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------- shortcuts
    fun info(ctx: Context, s: Site, idPrefix: String, rank: Int): ShortcutInfo {
        val intent = Intent(ctx, SiteActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        intent.putExtra("site", s.id)
        val b = ShortcutInfo.Builder(ctx, idPrefix + s.id)
        b.setShortLabel(s.name)
        b.setLongLabel(s.name)
        b.setIcon(Icon.createWithBitmap(iconBitmap(ctx, s)))
        b.setIntent(intent)
        if (idPrefix.isEmpty()) {
            // dynamic "sharing shortcut": this is what shows up in the Share menu
            b.setCategories(setOf(CATEGORY))
            b.setRank(rank)
            if (Build.VERSION.SDK_INT >= 30) b.setLongLived(true)
        }
        return b.build()
    }

    fun syncShortcuts(ctx: Context) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        try {
            val sites = all(ctx).take(sm.maxShortcutCountPerActivity)
            sm.setDynamicShortcuts(sites.mapIndexed { i, s -> info(ctx, s, "", i) })
            // keep already-pinned home screen icons in step with the latest icon/name
            try {
                sm.updateShortcuts(sites.map { info(ctx, it, "pin_", 0) })
            } catch (e: Exception) {
            }
        } catch (e: Exception) {
        }
    }
}

object Net {
    /** Reads the <title> of the PC software's page, to use as its name. */
    fun title(url: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            val buf = ByteArray(24000)
            var n = 0
            c.inputStream.use { ins ->
                while (n < buf.size) {
                    val r = ins.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
            }
            val html = String(buf, 0, n, Charsets.UTF_8)
            val m = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            val t = m?.groupValues?.get(1)?.trim()
            if (t.isNullOrEmpty()) null else t
        } catch (e: Exception) {
            null
        }
    }
}
