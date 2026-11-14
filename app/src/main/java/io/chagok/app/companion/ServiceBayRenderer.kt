package io.chagok.app.companion

import android.content.Context
import android.graphics.*
import io.chagok.app.domain.Item
import io.chagok.app.domain.Wheel
import kotlin.math.*

internal enum class BayJob(val label: String) {
    TIRE("타이어 교체"), WIPER("와이퍼 점검"), OIL("엔진오일 교환"),
    ROTATION("타이어 위치 교환"), BRAKES("브레이크 패드 점검"), BATTERY("12V 배터리 점검"),
    WASHER("워셔액 보충"), GENERAL("기타 정비"),
    DRIVE_BELT("드라이브 벨트 교체"), TRANSMISSION_FLUID("변속기 오일 교환"),
    BRAKE_FLUID("브레이크 오일 교환"), IGNITION("점화플러그·코일 교체"),
    CABIN_FILTER("에어컨 필터 교체"), BATTERY_REPLACE("배터리 교체");

    companion object {
        fun forItem(item: Item) = when (item) {
            Item.TIRE_REPLACE -> TIRE
            Item.WIPER_CHECK, Item.WIPER_REPLACE -> WIPER
            Item.ENGINE_OIL -> OIL
            Item.DRIVE_BELT -> DRIVE_BELT
            Item.TRANSMISSION_OIL -> TRANSMISSION_FLUID
            Item.BRAKE_FLUID -> BRAKE_FLUID
            Item.SPARK_PLUG, Item.IGNITION_COIL -> IGNITION
            Item.CABIN_FILTER -> CABIN_FILTER
            Item.BATTERY_12V_REPLACE -> BATTERY_REPLACE
            Item.TIRE_ROTATION -> ROTATION
            Item.BRAKE_PAD_CHECK, Item.BRAKE_PAD_REPLACE -> BRAKES
            Item.BATTERY_12V_CHECK -> BATTERY
            Item.WASHER_REFILL -> WASHER
            Item.COOLANT, Item.OTHER -> GENERAL
            Item.SYMPTOM_NOTE, Item.REFUEL, Item.CHARGE -> null
        }
    }
}

/** A visit is visual only. The diary owns detection, confirmation and persistence. */
internal class FuelVisit {
    var position = 0f
        private set
    var parkedAtPump = false
        private set
    private var lastVisit: String? = null

    fun observe(id: String?, motion: MotionSnapshot) {
        if (!motion.animationAllowed || motion.parkGear == null || motion.speedMps == null) {
            parkedAtPump = false
            position = 0f
            return
        }
        if (!motion.parkGear || abs(motion.speedMps) > .3f) parkedAtPump = false
        else if (id != null && id != lastVisit && !motion.restricted) {
            parkedAtPump = true
            lastVisit = id
        }
    }

    fun advance(seconds: Float) {
        position = (position + seconds * (if (parkedAtPump) .65f else -.8f)).coerceIn(0f, 1f)
    }
}

internal class ServiceBayRenderer(private val context: Context) {
    private val stationTop by lazy { context.assets.open("companion/station-top.webp").use { BitmapFactory.decodeStream(it) } }
    private val stationSide by lazy { context.assets.open("companion/station-side.webp").use { BitmapFactory.decodeStream(it) } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val hose = Path()
    private val wheelArt = mutableMapOf<Pair<CarSkin,Boolean>, Bitmap>()

    fun drawFuel(c: Canvas, w: Float, h: Float, side: Boolean, car: Bitmap, skin: CarSkin,
             time: Float, progress: Float, roadX: Float, roadY: Float, roadWidth: Float,
             departing: Boolean = false): RectF {
        val p = progress * progress * (3f - 2f * progress)
        val stationWidth = if (side) min(w*.30f,h*.65f) else min(w*.26f,h*.21f)
        val stationLeft = if (side) w*.55f-stationWidth*.5f +
            (1-p)*(if (departing) -w*.85f else w*.75f) else w-stationWidth
        val station = if(side) stationSide else stationTop
        val stationHeight = stationWidth * station.height/station.width
        val stationTopY = if (side) h*.43f-stationHeight else
            h*.57f-stationHeight*.5f + (1-p)*(if (departing) h*.85f else -h*.9f)
        paint.color = Color.WHITE
        dst.set(stationLeft,stationTopY,stationLeft+stationWidth,stationTopY+stationHeight)
        c.drawBitmap(station,null,dst,paint)
        val width = roadWidth*(1f-(if(side) .14f else .06f)*p)
        val height = width*car.height/car.width
        val arc = sin(p * Math.PI).toFloat() * (if (departing) 1f else -1f)
        val x = (if (side) arc*w*.055f else 0f) + roadX + ((if(!side) w-stationWidth-width*.49f else w*.58f)-roadX)*p
        val y = (if (side) 0f else -arc*h*.12f) + roadY + (h*(if(side) .47f else .60f)-roadY)*p
        val body = RectF(x-width/2,y-height/2,x+width/2,y+height/2)
        if (side) {
            paint.color=Color.argb(72,7,14,18)
            c.drawOval(body.left+width*.08f,body.bottom-height*.1f,body.right-width*.06f,body.bottom+height*.04f,paint)
        }
        val fueling = p > .82f
        var nozzleX = 0f
        var nozzleY = 0f
        if (fueling) {
            val reach=((p-.82f)/.18f).coerceIn(0f,1f)
            val ax=stationLeft + stationWidth*(if(side) .245f else .16f)
            val ay=stationTopY + stationHeight*(if(side) .76f else .54f)
            val bx=ax+((if(side) body.left+width*.15f else body.right-width*.07f)-ax)*reach
            val by=ay+(body.top+height*.72f-ay)*reach
            nozzleX = bx; nozzleY = by
            hose.reset(); hose.moveTo(ax,ay); hose.quadTo(ax,(ay+by)/2+width*.25f,bx,by)
            paint.style=Paint.Style.STROKE; paint.strokeCap=Paint.Cap.ROUND
            paint.color=0xff302923.toInt(); paint.strokeWidth=width*.027f; c.drawPath(hose,paint)
            paint.color=0xff93917a.toInt(); paint.strokeWidth=width*.008f; c.drawPath(hose,paint)
            paint.style=Paint.Style.FILL
            paint.color=0xffdeb660.toInt()
            val dialX = stationLeft + stationWidth*(if(side) .318f else .24f)
            val dialY = stationTopY + stationHeight*(if(side) .509f else .411f)
            val lit = (time*5).toInt()%5
            for (i in 0 until 5) {
                paint.color = if (i <= lit) 0xffe9bd6d.toInt() else 0xff564a37.toInt()
                val dx = dialX + stationWidth*(i-2)*.009f
                c.drawRect(dx,dialY,dx+stationWidth*.006f,dialY+stationHeight*.012f,paint)
            }
        }
        paint.color=Color.WHITE
        c.save()
        if (!side) c.rotate(arc*9f,x,y)
        c.drawBitmap(car,null,body,paint)
        c.restore()
        if (fueling) {
            paint.color=0xff413d36.toInt(); paint.strokeWidth=width*.031f
            c.drawLine(nozzleX-width*.036f,nozzleY+width*.025f,nozzleX,nozzleY,paint)
            paint.color=0xffb86a3e.toInt(); paint.strokeWidth=width*.019f
            c.drawLine(nozzleX-width*.027f,nozzleY+width*.02f,nozzleX,nozzleY,paint)
        }
        return body
    }

    fun drawWorkshopCar(c: Canvas, car: Bitmap, body: RectF, side: Boolean, skin: CarSkin,
                        job: BayJob, targets: List<Wheel>, time: Float, working: Boolean) {
        val sequence = (time.coerceAtLeast(0f)/2.4f).coerceAtMost(targets.size.coerceAtLeast(1)-.0001f)
        val wheel = targets.getOrNull(sequence.toInt()) ?: Wheel.FRONT_LEFT
        val mirrored = side && targets.isNotEmpty() && job == BayJob.TIRE && (wheel == Wheel.FRONT_RIGHT || wheel == Wheel.REAR_RIGHT)
        paint.color = Color.WHITE
        c.save()
        if (mirrored) c.scale(-1f,1f,body.centerX(),body.centerY())
        c.drawBitmap(car,null,body,paint)
        c.restore()
        when (job) {
            BayJob.TIRE, BayJob.ROTATION -> if(job == BayJob.TIRE && targets.isEmpty()) {
                if (working) {
                    val variant = if(skin == CarSkin.RACER) "racer" else "sedan"
                    val art = wheelArt.getOrPut(skin to true) {
                        context.assets.open("companion/workshop-wheel-$variant-side.webp").use { BitmapFactory.decodeStream(it) }
                    }
                    val size = body.width()*.22f
                    drawWheel(c,art,body.centerX()+size*sin(time*2f),body.bottom+size*.44f,size,size,time*85f)
                }
            } else tire(c,body,side,skin,wheel,sequence%1f,mirrored,
                if(job==BayJob.ROTATION && working) (time/2.4f).coerceIn(0f,1f) else null)
            BayJob.WIPER -> if (working) wipers(c,body,side,skin,time)
            else -> Unit
        }
    }

    private fun wipers(c: Canvas, b: RectF, side: Boolean, skin: CarSkin, time: Float) {
        val blade = b.width()*(if(side) .105f else .255f)
        val angle = -(.14f + (1-cos(time*4))*.5f*1.02f)
        paint.strokeCap=Paint.Cap.ROUND
        for (i in 0..(if(side) 0 else 1)) {
            val x=b.left+b.width()*(if(side) .68f else .32f+i*.3f)
            val y=b.top+b.height()*(if(side) .42f else if(skin==CarSkin.RACER) .41f else .35f)
            val endX=x + cos(angle)*blade*(if(side) -1f else 1f)
            val endY=y + sin(angle)*blade*(if(side) .62f else 1f)
            paint.color=0xff272c31.toInt();paint.strokeWidth=blade*.115f
            c.drawLine(x,y,endX,endY,paint)
            paint.color=0xffadc2c4.toInt();paint.strokeWidth=blade*.035f
            c.drawLine(x,y-1,endX,endY-1,paint)
        }
    }

    private fun tire(c: Canvas, body: RectF, side: Boolean, skin: CarSkin,
                     current: Wheel, phase: Float, mirrored: Boolean, rotation: Float?) {
        val art = wheelArt.getOrPut(skin to side) {
            val variant = if (skin == CarSkin.RACER) "racer" else "sedan"
            val view = if (side) "side" else "top"
            context.assets.open("companion/workshop-wheel-$variant-$view.webp").use { BitmapFactory.decodeStream(it) }
        }
        val racer = skin == CarSkin.RACER
        for (wheel in Wheel.entries) {
            val front = wheel == Wheel.FRONT_LEFT || wheel == Wheel.FRONT_RIGHT
            val left = wheel == Wheel.FRONT_LEFT || wheel == Wheel.REAR_LEFT
            if (side && left == mirrored) continue
            val hubX = if (side) {
                if (front) if (racer) .765f else .837f else if (racer) .22f else .243f
            } else if (front) {
                if (left) .12f else .88f
            } else if (left) .035f else .965f
            val hubY = if (side) .88f else if (front) if (racer) .29f else .32f else .86f
            val x = body.left + body.width() * (if (mirrored) 1f-hubX else hubX)
            val y = body.top + body.height()*hubY
            val height = if (side) body.width()*.145f else body.height()*.145f
            val width = height * art.width / art.height
            if (rotation != null) {
                val axleSpan = if(side) body.width()*(if(racer) .545f else .594f)
                    else body.height()*(if(racer) .57f else .54f)
                val arc = sin(rotation*Math.PI).toFloat()
                val swap = axleSpan*rotation*(if(front) -1f else 1f)
                val dx = if(side) swap else arc*height*(if(left) -1f else 1f)*(if(front) 1.2f else .55f)
                val dy = if(side) arc*height*(if(front) 1.15f else .45f) else -swap
                drawWheel(c,art,x+dx,y+dy,width,height,if(side) arc*110f else 0f)
                continue
            }
            if (wheel != current || phase == 0f) {
                drawWheel(c,art,x,y,width,height,0f)
                continue
            }
            // Leave the rotor visible while the old tire rolls away and the replacement comes in.
            val old = phase < .5f
            val distance = if (old) ((phase-.12f)/.3f).coerceIn(0f,1f)
                else ((.88f-phase)/.30f).coerceIn(0f,1f)
            val eased = distance*distance*(3f-2f*distance)
            val outward = if (side) { if(old) -1f else 1f } else if(left) -1f else 1f
            val dx = eased * height * outward * (if(side) 1.65f else .85f)
            val dy = eased * height * (if(side) .51f else if(old) -.65f else .65f)
            val alpha = when {
                old -> ((.5f-phase)/.09f).coerceIn(0f,1f)
                else -> ((phase-.5f)/.09f).coerceIn(0f,1f)
            }
            paint.alpha=(255*alpha).toInt()
            drawWheel(c,art,x+dx,y+dy,width,height,if(side) eased*outward*155f else 0f)
            paint.alpha=255
        }
    }

    private fun drawWheel(c: Canvas, art: Bitmap, x: Float, y: Float,
                          width: Float, height: Float, angle: Float) {
        dst.set(x-width/2,y-height/2,x+width/2,y+height/2)
        c.save()
        c.rotate(angle,x,y)
        c.drawBitmap(art,null,dst,paint)
        c.restore()
    }
}
