package io.chagok.app.companion

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import io.chagok.app.domain.Wheel
import kotlin.math.*

/** A parked journal receipt rendered as one continuous shot from the street. */
internal class WorkshopView(context: Context, private val job: BayJob, private val skin: CarSkin,
                            wheels: Set<Wheel>, private val oilBrand: String? = null, private val oilLitres: Double? = null,
                            itemLabel: String = job.label, private val clock: () -> Long = SystemClock::uptimeMillis,
                            private val finished: () -> Unit) : View(context) {
    private val bay = ServiceBayRenderer(context)
    private val targets = wheels.sortedBy { it.ordinal }
    private val repairName = when(job) {
        BayJob.DRIVE_BELT -> "belt"
        BayJob.IGNITION -> "coil"
        BayJob.CABIN_FILTER -> "filter"
        BayJob.BATTERY_REPLACE -> "battery"
        BayJob.BRAKES -> "brakes"
        BayJob.BATTERY -> "meter"
        else -> null
    }
    private val approachRenderer = WorkshopApproach()
    private val sidePoses by lazy {
        val variant=if(skin==CarSkin.RACER) "racer" else "sedan"
        val original=image(if(skin==CarSkin.RACER) "racer-side.png" else "hyundai-side.png")
        listOf(original) + (0..2).map { image("workshop-turn-$variant-$it.webp") }
    }
    private val frameRect = Rect()
    private val workSeconds = if(repairName != null) 4.1f else if (job == BayJob.TIRE) 2.4f*targets.size.coerceAtLeast(1)+.45f else 3.15f
    private val redFluid = LightingColorFilter(0xffff5d42.toInt(),0)
    private val paleFluid = LightingColorFilter(0xffffe9ae.toInt(),0x00202014)
    private val images = mutableMapOf<String,Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val garage = RectF()
    private val body = RectF()
    private val route = Path()
    private val measure = PathMeasure()
    private val point = FloatArray(2)
    private val tangent = FloatArray(2)
    private var started = -1L
    private var done = false

    init {
        contentDescription = itemLabel + " 기록 저장 완료"
        setOnClickListener { finish() }
    }

    private fun finish() {
        if (!done) { done = true; finished() }
    }

    private fun image(name: String) = images.getOrPut(name) {
        context.assets.open("companion/$name").use { BitmapFactory.decodeStream(it) }
    }

    private fun smooth(value: Float): Float {
        val u=value.coerceIn(0f,1f)
        return u*u*(3f-2f*u)
    }

    override fun onDraw(c: Canvas) {
        val w=width.toFloat()
        val h=height.toFloat()
        if (w<=0 || h<=0) return
        val side = w >= h*1.2f
        val view = if(side) "side" else "top"
        val building = image("workshop-garage-$view.webp")
        val road = image(if(side) "city-side.png" else "city-dry.webp")
        val variant = if(skin==CarSkin.RACER) "racer" else "sedan"
        val wheelsSeparate = (job==BayJob.TIRE && targets.isNotEmpty()) || job==BayJob.ROTATION
        val car = image(if(wheelsSeparate) "workshop-body-$variant-$view.webp"
            else if(side && skin==CarSkin.HYUNDAI) "hyundai-side.png"
            else if(side) "racer-side.png" else if(skin==CarSkin.RACER) "racer.webp" else "hyundai-sedan.webp")
        if (started < 0) started = clock()
        val t = (clock()-started)/1000f
        val work = (t-ENTRY).coerceIn(0f,workSeconds)
        val exit = t-ENTRY-workSeconds
        val approach = if(exit<=0) smooth(t/ENTRY) else 1f-smooth(exit/EXIT)
        val working = t>=ENTRY && exit<=0
        val gw = if(side) min(w*.60f,h*.95f) else min(w*.60f,h*.51f)
        val gh = gw*building.height/building.width
        val gx = if(side) w*.65f else w-gw*.54f
        val bottom = if(side) h*.56f else h*.56f
        garage.set(gx-gw/2,bottom-gh,gx+gw/2,bottom)
        val dockY=garage.top+gh*(if(side) .81f else .56f)
        val startX=w*(if(side) .13f else .39f)
        val startY=h*(if(side) .76f else .86f)
        val thresholdX=if(side) gx-gw*.27f else gx
        route.reset()
        route.moveTo(startX,startY)
        if(side) {
            route.cubicTo(w*.36f,startY,thresholdX-gw*.12f,bottom+h*.11f,thresholdX,bottom)
            route.cubicTo(thresholdX+gw*.09f,bottom-h*.08f,gx-gw*.13f,dockY,gx,dockY)
        } else {
            route.cubicTo(startX,startY-h*.22f,gx,bottom+h*.16f,gx,bottom)
            route.lineTo(gx,dockY)
        }
        measure.setPath(route,false)
        measure.getPosTan(approach*measure.length,point,tangent)
        var reversing = false
        if (exit>0) {
            val laneX = if(side) gx-gw*.35f else w*.41f
            val laneY = if(side) startY else bottom+h*.20f
            val backing = exit < EXIT*.62f
            route.reset()
            if (backing) {
                reversing = true
                route.moveTo(gx,dockY)
                if(side) route.cubicTo(gx-gw*.30f,dockY,laneX+gw*.22f,laneY,laneX,laneY)
                else route.cubicTo(gx,bottom+h*.09f,laneX,laneY-h*.10f,laneX,laneY)
            } else {
                route.moveTo(laneX,laneY)
                if(side) route.lineTo(w*.95f,laneY) else route.lineTo(laneX,h*.16f)
            }
            measure.setPath(route,false)
            val fraction = if(backing) exit/(EXIT*.62f) else (exit/EXIT-.62f)/.38f
            measure.getPosTan(smooth(fraction)*measure.length,point,tangent)
        }
        val heading=(if(reversing) 180f else 0f) + Math.toDegrees(atan2(tangent[1],tangent[0]).toDouble()).toFloat() + if(side) 0f else 90f
        val workZoom = (if(side) 2.05f else 1.62f) * WORK_SCALE
        val zoom = 1f + smooth((approach-.48f)/.52f)*(workZoom-1f)
        c.save()
        val focusX=w*.5f+(gx-w*.5f)*approach
        val focusY=h*.5f+(dockY-h*.5f)*approach
        c.translate(w*.5f-focusX,h*.5f-focusY)
        c.scale(zoom,zoom,focusX,focusY)
        // Extend the original street beyond the camera boundary as it follows the car.
        paint.color=Color.WHITE
        val roadW=if(side) max(w,h*road.width/road.height) else w
        val roadH=if(side) h else max(h,w*road.height/road.width)
        for (i in -1..1) {
            val x=(w-roadW)/2+i*roadW
            c.drawBitmap(road,null,RectF(x,0f,x+roadW,roadH),paint)
        }
        c.drawBitmap(building,null,garage,paint)
        val cw=(if(side) 1.10f-.10f*approach else 1f)*gw*(if(side) .63f else .41f)
        val ch=cw*car.height/car.width
        // In side projection the path follows the midpoint of the two tire contacts.
        val sideAxle = if(skin==CarSkin.RACER) .4975f else .54f
        val centerX = if(side && !wheelsSeparate) point[0]+cw*(.5f-sideAxle) else point[0]
        val centerY = if(side) point[1]-ch*(if(wheelsSeparate) .49f else if(skin==CarSkin.RACER) .385f else .415f) else point[1]
        body.set(centerX-cw/2,centerY-ch/2,centerX+cw/2,centerY+ch/2)
        c.save()
        // In side projection the car steers into depth with little change in screen pitch.
        val bearing = (heading+540f)%360f-180f
        c.rotate(if(side) 0f else bearing,point[0],point[1])
        if(side && (!working || (!wheelsSeparate && job!=BayJob.WIPER))) {
            approachRenderer.draw(c,sidePoses,skin,abs(bearing),point[0],point[1],cw)
        } else {
            paint.color=0x550d1517
            c.drawOval(body.left+cw*.05f,body.bottom-ch*.12f,body.right-cw*.04f,body.bottom+ch*.04f,paint)
            bay.drawWorkshopCar(c,car,body,side,skin,job,targets,work,working)
        }
        c.restore()
        if(working && repairName==null) drawTools(c,side,work)
        c.restore()
        if(working && repairName!=null) drawRepair(c,w,h,repairName,work)
        if(working) {
            val caption = when(job) {
                BayJob.OIL -> oilDetails()
                BayJob.TIRE -> targets.getOrNull((work/2.4f).toInt().coerceAtMost(targets.lastIndex))?.label
                else -> null
            }
            if(!caption.isNullOrEmpty()) drawCaption(c,w,h,caption)
        }
        if(exit < EXIT+.15f) postInvalidateOnAnimation() else post { finish() }
    }

    private fun drawTools(c: Canvas, side: Boolean, time: Float) {
        val tool = when(job) {
            BayJob.TRANSMISSION_FLUID -> "transmission"
            BayJob.BRAKE_FLUID -> "brake-fluid"
            BayJob.OIL -> "oil"
            BayJob.WASHER -> "washer"
            BayJob.ROTATION -> "jack"
            BayJob.GENERAL -> "trolley"
            else -> return
        }
        val sprite=image("workshop-$tool.webp")
        val pour=job in listOf(BayJob.OIL,BayJob.WASHER,BayJob.TRANSMISSION_FLUID,BayJob.BRAKE_FLUID)
        val working=smooth(time/.55f)*smooth((workSeconds-time)/.55f)
        val size=body.width()*(if(side) .30f else .53f)
        val h=size*sprite.height/sprite.width
        val x=body.centerX()+.24f*body.width()+size*(1-working)*.55f
        val y=body.top+(if(side) .37f else .28f)*body.height()
        if(pour) {
            drawPour(c,tool,sprite,x,y,size,h,time,working)
            return
        }
        paint.color=Color.WHITE
        paint.alpha=(255*working).toInt()
        c.drawBitmap(sprite,null,RectF(x,y-h*.12f,x+size,y+h*.88f),paint)
        paint.color=Color.WHITE
    }

    private fun drawPour(c:Canvas,tool:String,jug:Bitmap,x:Float,y:Float,size:Float,h:Float,t:Float,amount:Float) {
        val mouthY = y+size*.34f
        val family = if(tool=="washer") "washer" else "oil"
        val funnel = image("workshop-funnel.webp")
        paint.color=Color.WHITE
        paint.alpha=(255*amount).toInt()
        c.drawBitmap(funnel,null,RectF(x-size*.14f,mouthY-size*.06f,x+size*.14f,mouthY+size*.15f),paint)
        // Pivot at the lip: the spout and the origin of the liquid stay registered.
        c.save(); c.rotate(-54f*amount,x,y)
        c.drawBitmap(jug,null,RectF(x-size*.016f,y-h*.048f,x+size*.984f,y+h*.952f),paint)
        c.restore()
        if(amount>.68f) {
            val period=if(tool=="washer") .29f else .43f
            val phase=(t%period)/period
            val stage=when { phase<.20f -> "bead";phase<.40f -> "thread";phase<.82f -> "drop";else -> "splash" }
            val fall=((phase-.20f)/.62f).coerceIn(0f,1f)
            val cy=y+(mouthY-y)*fall*fall
            val width=size*when(stage) { "splash" -> .145f; "thread" -> .031f; else -> .068f }
            val height=size*when(stage) { "bead" -> .095f; "thread" -> .18f; "drop" -> .09f; else -> .055f }
            val liquid=image("workshop-$family-$stage.webp")
            paint.colorFilter=when(job) {
                BayJob.TRANSMISSION_FLUID -> redFluid
                BayJob.BRAKE_FLUID -> paleFluid
                else -> null
            }
            c.drawBitmap(liquid,null,RectF(x-width/2,cy-height*.2f,x+width/2,cy+height*.8f),paint)
            paint.colorFilter=null
        }
        paint.color=Color.WHITE
    }

    /** Cut from the parked car to the mechanic's working area, then return to the same pose. */
    private fun drawRepair(c:Canvas,w:Float,h:Float,name:String,time:Float) {
        val opacity=smooth(time/.24f)*smooth((workSeconds-time)/.28f)
        if(opacity<=0f) return
        val atlas=image("repair-$name.webp")
        val cell=atlas.width/5
        val frame=((((time-.22f)/(workSeconds-.54f)).coerceIn(0f,1f))*18f).roundToInt()
        val left=frame%5*cell
        val top=frame/5*cell
        // Keep the mechanism centred and retain both hands on wide as well as narrow windows.
        val aspect=(w/h).coerceIn(.80f,1.45f)
        val sw=min(cell.toFloat(),cell*aspect)
        val sh=sw/aspect
        val panelH=min(h*.95f,w*.96f/aspect)*WORK_SCALE
        val panelW=panelH*aspect
        val panel=RectF((w-panelW)/2,(h-panelH)/2,(w+panelW)/2,(h+panelH)/2)
        frameRect.set(left+((cell-sw)/2).toInt()+1,top+((cell-sh)/2).toInt()+1,
            left+((cell+sw)/2).toInt()-1,top+((cell+sh)/2).toInt()-1)
        c.drawColor(Color.argb((145*opacity).toInt(),10,15,16))
        paint.color=Color.WHITE;paint.alpha=(255*opacity).toInt()
        c.drawBitmap(atlas,frameRect,panel,paint)
        paint.color=Color.WHITE
    }

    private fun oilDetails(): String {
        val brand=oilBrand?.trim().orEmpty()
        val litres=oilLitres?.takeIf { it.isFinite() && it>0 }?.let {
            java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()+" L"
        }
        return listOfNotNull(brand.takeIf { it.isNotEmpty() },litres).joinToString(" · ")
    }

    private fun drawCaption(c:Canvas,w:Float,h:Float,label:String) {
        paint.textSize=min(w,h)*.048f
        val shown=android.text.TextUtils.ellipsize(label,android.text.TextPaint(paint),w*.84f,
            android.text.TextUtils.TruncateAt.END).toString()
        val pad=paint.textSize*.5f
        paint.color=0xdd182326.toInt()
        c.drawRoundRect(pad,h-pad*4,pad*3+paint.measureText(shown),h-pad,pad,pad,paint)
        paint.color=Color.WHITE; c.drawText(shown,pad*2,h-pad*1.8f,paint)
    }

    companion object {
        private const val WORK_SCALE = .7f
        const val ENTRY=2.75f
        const val EXIT=2.8f
    }
}
