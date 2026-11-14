package io.chagok.app.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/** Wheel contact coordinates measured on the original painted poses. */
internal class WorkshopApproach {
    private data class Contacts(val rearX:Float,val rearY:Float,val frontX:Float,val frontY:Float)
    private val racer = listOf(
        Contacts(.245f,.89f,.75f,.88f), Contacts(.32f,.98f,.86f,.90f),
        Contacts(.55f,.94f,.94f,.60f), Contacts(.75f,.96f,.93f,.46f))
    private val sedan = listOf(
        Contacts(.26f,.92f,.82f,.91f), Contacts(.32f,.99f,.88f,.90f),
        Contacts(.54f,.98f,.94f,.54f), Contacts(.79f,.96f,.96f,.42f))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()

    fun draw(c:Canvas,poses:List<Bitmap>,skin:CarSkin,yaw:Float,x:Float,ground:Float,width:Float) {
        val angle=yaw.coerceIn(0f,70f)
        val index=when { angle<15f -> 0; angle<40f -> 1; else -> 2 }
        val start=when(index) { 0 -> 0f; 1 -> 15f; else -> 40f }
        val end=when(index) { 0 -> 15f; 1 -> 40f; else -> 70f }
        // Hold each drawing, with a short registered dissolve at the change of pose.
        val mix=(((angle-start)/(end-start)-.38f)/.24f).coerceIn(0f,1f)
        val contacts=if(skin==CarSkin.RACER) racer else sedan
        val low=if(mix==1f) index+1 else index
        val high=if(mix==0f) index else index+1
        for(i in low..high) {
            val a=if(low==high) 1f else if(i==index) 1f-mix else mix
            val art=poses[i]
            val contact=contacts[i]
            val w=width*when(i) { 0,1 -> 1f; 2 -> .87f; else -> .61f }
            val h=w*art.height/art.width
            val left=x-w*(contact.rearX+contact.frontX)*.5f
            val top=ground-h*(contact.rearY+contact.frontY)*.5f
            bounds.set(left,top,left+w,top+h)
            val rx=left+contact.rearX*w; val ry=top+contact.rearY*h
            val fx=left+contact.frontX*w; val fy=top+contact.frontY*h
            paint.color=Color.argb((55*a).toInt(),19,18,16)
            c.save();c.rotate(Math.toDegrees(kotlin.math.atan2(fy-ry,fx-rx).toDouble()).toFloat(),x,ground)
            val span=kotlin.math.hypot(fx-rx,fy-ry)
            c.drawOval(x-span*.74f,ground-width*.037f,x+span*.74f,ground+width*.023f,paint)
            c.restore()
            paint.color=Color.argb((125*a).toInt(),16,17,17)
            for(j in 0..1) {
                val px=if(j==0) rx else fx; val py=if(j==0) ry else fy
                c.drawOval(px-width*.049f,py-width*.014f,px+width*.049f,py+width*.009f,paint)
            }
        }
        for(i in low..high) {
            val art=poses[i];val contact=contacts[i]
            val w=width*when(i){0,1->1f;2->.87f;else->.61f}
            val h=w*art.height/art.width
            val left=x-w*(contact.rearX+contact.frontX)*.5f
            val top=ground-h*(contact.rearY+contact.frontY)*.5f
            bounds.set(left,top,left+w,top+h)
            paint.color=Color.WHITE
            if(i!=low)paint.alpha=(255*mix).toInt()
            c.drawBitmap(art,null,bounds,paint)
        }
    }
}
