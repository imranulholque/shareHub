package com.sharehub.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast

val BG = Color.parseColor("#0F1115")
val CARD = Color.parseColor("#181B22")
val MUTED = Color.parseColor("#9AA0AA")
val ACCENT = Color.parseColor("#3B82F6")
val PURPLE = Color.parseColor("#8B5CF6")
val RED = Color.parseColor("#EF4444")
val GREY = Color.parseColor("#2A2F3A")

val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.toast(s: String) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

fun Context.rounded(color: Int, r: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(r).toFloat()
    return g
}

fun Context.pill(label: String, bg: Int, onClick: () -> Unit): TextView {
    val t = TextView(this)
    t.text = label
    t.setTextColor(Color.WHITE)
    t.textSize = 14f
    t.typeface = Typeface.DEFAULT_BOLD
    t.gravity = Gravity.CENTER
    t.setPadding(dp(14), dp(9), dp(14), dp(9))
    t.background = rounded(bg, 20)
    t.setOnClickListener { onClick() }
    return t
}
