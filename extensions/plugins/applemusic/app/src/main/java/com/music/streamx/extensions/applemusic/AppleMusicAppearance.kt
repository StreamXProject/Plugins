package com.music.streamx.extensions.applemusic

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import org.json.JSONObject

/** Shared native settings styling, supplied by the selected StreamX settings skin. */
internal class AppleMusicAppearance(private val activity: Activity) {
    private val prefs=activity.getSharedPreferences("apple_music_ui",0)
    init {
        activity.intent.getStringExtra("streamx.ui.palette")?.let {if(it!=prefs.getString("palette",null) || prefs.getBoolean("material",false)!=activity.intent.getBooleanExtra("streamx.ui.material",false) || prefs.getInt("radius",22)!=activity.intent.getIntExtra("streamx.ui.cornerRadiusDp",22))prefs.edit().putString("palette",it)
            .putBoolean("material",activity.intent.getBooleanExtra("streamx.ui.material",false))
            .putInt("radius",activity.intent.getIntExtra("streamx.ui.cornerRadiusDp",22)).apply()}
    }
    private val palette=runCatching {JSONObject(prefs.getString("palette","{}").orEmpty())}.getOrDefault(JSONObject())
    private fun color(key: String,fallback: Int)=runCatching{Color.parseColor(palette.getString(key))}.getOrDefault(fallback)
    val bg=color("background",Color.BLACK)
    val surface=color("surfaceVariant",0xff1c1c1e.toInt())
    val ink=color("text",Color.WHITE)
    val muted=color("secondary",0xff909094.toInt())
    val accent=color("accent",Color.WHITE)
    val material=prefs.getBoolean("material",false)
    val radius=prefs.getInt("radius",22).coerceIn(0,36)
    val regular: Typeface=if(material)Typeface.create("sans-serif",Typeface.NORMAL) else activity.resources.getFont(R.font.sf_pro_display_regular)
    val bold: Typeface=if(material)Typeface.create("sans-serif-medium",Typeface.NORMAL) else activity.resources.getFont(R.font.sf_pro_display_bold)
    fun shape(color: Int,dpRadius: Int=radius)=GradientDrawable().apply {setColor(color);cornerRadius=dpRadius*activity.resources.displayMetrics.density}
    fun touch(color: Int=surface,radius: Int=this.radius)=RippleDrawable(
        ColorStateList.valueOf(((if(Color.luminance(color)>.5)Color.BLACK else ink) and 0xffffff) or 0x20000000),shape(color,radius),shape(Color.WHITE,radius))
}
