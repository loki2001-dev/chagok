package io.chagok.app.ui

import android.graphics.*
import android.graphics.drawable.Drawable

/** One line weight and a 24-unit grid for all navigation and service icons. */
enum class Icon { HOME, JOURNAL, WRENCH, CHART, CAR, MORE, CHEVRON, BACK, PLUS, MIC, CALENDAR, CHECK, ALERT, FRIENDS, USER, SHARE, EXPORT, BOOK, BOLT, FUEL, SETTINGS, CLOCK }

class AppIcon(private val kind: Icon, private val tint: Int) : Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=tint;style=Paint.Style.STROKE;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        val save=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        fun line(vararg p:Float) { val path=Path();path.moveTo(p[0],p[1]);for(i in 2 until p.size step 2)path.lineTo(p[i],p[i+1]);canvas.drawPath(path,paint) }
        fun circle(x:Float,y:Float,r:Float)=canvas.drawCircle(x,y,r,paint)
        fun box(l:Float,t:Float,r:Float,b:Float,rad:Float=2f)=canvas.drawRoundRect(l,t,r,b,rad,rad,paint)
        when(kind) {
            Icon.HOME->{line(3f,10f,12f,3f,21f,10f);line(5f,9f,5f,21f,10f,21f,10f,15f,14f,15f,14f,21f,19f,21f,19f,9f)}
            Icon.JOURNAL,Icon.BOOK->{box(5f,3f,20f,21f);line(9f,3f,9f,21f);line(12f,8f,17f,8f);line(12f,12f,17f,12f);line(3f,7f,6f,7f);line(3f,16f,6f,16f)}
            Icon.WRENCH->{line(14f,3f,12f,6f,13f,10f,17f,11f,21f,8f);line(14f,3f,18f,3f,21f,6f,21f,8f);line(13f,10f,4f,19f,6f,21f,16f,11f)}
            Icon.CHART->{line(4f,3f,4f,21f,21f,21f);line(8f,16f,8f,12f);line(13f,16f,13f,7f);line(18f,16f,18f,4f)}
            Icon.CAR->{box(3f,10f,21f,18f);line(5f,10f,7f,5f,17f,5f,19f,10f);line(6f,18f,6f,21f);line(18f,18f,18f,21f);line(6f,14f,8f,14f);line(16f,14f,18f,14f)}
            Icon.MORE->{circle(5f,12f,1f);circle(12f,12f,1f);circle(19f,12f,1f)}
            Icon.CHEVRON->line(9f,5f,16f,12f,9f,19f)
            Icon.BACK->line(15f,5f,8f,12f,15f,19f)
            Icon.PLUS->{line(12f,4f,12f,20f);line(4f,12f,20f,12f)}
            Icon.MIC->{box(9f,3f,15f,14f,3f);canvas.drawArc(6f,7f,18f,18f,0f,180f,false,paint);line(12f,18f,12f,22f);line(9f,22f,15f,22f)}
            Icon.CALENDAR->{box(3f,5f,21f,21f);line(3f,10f,21f,10f);line(8f,3f,8f,7f);line(16f,3f,16f,7f);line(7f,14f,10f,14f);line(14f,14f,17f,14f)}
            Icon.CHECK->line(4f,12f,9f,17f,20f,6f)
            Icon.ALERT->{line(12f,3f,22f,21f,2f,21f,12f,3f);line(12f,9f,12f,14f);circle(12f,18f,.5f)}
            Icon.USER,Icon.FRIENDS->{circle(10f,7f,4f);canvas.drawArc(3f,14f,17f,27f,180f,180f,false,paint);if(kind==Icon.FRIENDS){line(18f,5f,21f,8f,21f,12f);canvas.drawArc(15f,15f,23f,27f,270f,90f,false,paint)}}
            Icon.SHARE->{circle(18f,5f,3f);circle(5f,12f,3f);circle(18f,19f,3f);line(8f,10f,15f,6f);line(8f,14f,15f,18f)}
            Icon.EXPORT->{line(5f,14f,5f,21f,19f,21f,19f,14f);line(12f,16f,12f,3f);line(7f,8f,12f,3f,17f,8f)}
            Icon.BOLT->line(14f,2f,5f,14f,11f,14f,10f,22f,20f,9f,14f,9f,14f,2f)
            Icon.FUEL->{box(4f,3f,14f,21f);box(6f,5f,12f,10f,0f);line(14f,12f,17f,12f,17f,18f,20f,18f,20f,7f,17f,4f);line(2f,21f,16f,21f)}
            Icon.SETTINGS->{circle(12f,12f,3f);circle(12f,12f,8f);for(i in 0..7){val a=i*Math.PI/4;line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat())}}
            Icon.CLOCK->{circle(12f,12f,9f);line(12f,6f,12f,12f,16f,14f)}
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int) { paint.alpha=alpha }
    override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
