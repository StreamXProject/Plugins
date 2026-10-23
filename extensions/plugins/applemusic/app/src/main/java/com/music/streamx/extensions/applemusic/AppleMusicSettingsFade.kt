package com.music.streamx.extensions.applemusic

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/** Lightweight counterpart of the host's rotated BottomFadeScrim. Touches pass through. */
internal class AppleMusicSettingsFade(context: Context, private val color: Int): View(context) {
    private val paint=Paint()
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;isFocusable=false}
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(h<=0)return
        val positions=FloatArray(16){it/15f}
        val colors=IntArray(16){index->
            // Compose's EaseInCubic is CubicBezierEasing(.32, 0, .67, 0).
            val x=1f-positions[index]
            var lo=0f;var hi=1f
            repeat(24){val t=(lo+hi)/2f;val u=1f-t;val at=3f*u*u*t*.32f+3f*u*t*t*.67f+t*t*t;if(at<x)lo=t else hi=t}
            val t=(lo+hi)/2f
            (color and 0x00ffffff) or ((255f*t*t*t).toInt().coerceIn(0,255) shl 24)
        }
        paint.shader=LinearGradient(0f,0f,0f,h.toFloat(),colors,positions,Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)}
}
