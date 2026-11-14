package io.chagok.app.companion

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import io.chagok.app.R
import io.chagok.app.domain.PlaceKind
import kotlin.math.*

/** Native portrait and horizontal 2.5D scenes, selected from the actual scene bounds. */
class CompanionSceneView(context: Context) : View(context) {
    val director = EncounterDirector()
    val serviceVisit = ServiceVisitAnimation()
    private val serviceRenderer = ServiceVisitRenderer()
    var motion = MotionSnapshot()
        set(value) { field = value; serviceVisit.advance(0f, value); invalidate() }
    var skin = CarSkin.RACER
    var preview = false
    var previewSpeed = 45f
    var previewTurn = 0
    var previewRain = 0f
    var previewAcceleration = 0f
    var question = ""
    var questionHint = ""
    var questionContext = "차곡"
    var seen: Set<String> = emptySet()
    var collect: (EncounterPass) -> Boolean = { false }
    var onEncounter: (Encounter) -> Unit = {}
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val tiles = mutableMapOf<String, Bitmap>()
    private val asphaltShaders = mutableMapOf<Bitmap, BitmapShader>()
    private val asphaltMatrix = Matrix()
    private val asphaltPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create(context.resources.getFont(R.font.chagok_sans),600,false) }
    private val rect = RectF()
    private val source = Rect()
    var sceneLayout = SceneLayout.fit(320, 500); private set
    private val bubbleTail = Path()
    private val bubbleShape = Path()
    private var active = false
    private var last = 0L
    private var clock = 0f
    private var steer = 0f
    private var boost = 0f
    private var pose = 0f
    private var previousScenery = Scenery.CITY
    private var blend = 1f
    private val landscapeRenderer by lazy { LandscapeSceneRenderer(context) }
    private var sideTravel=0.0
    var travel = 0f; private set
    data class RenderState(val carX: Float, val angle: Float, val width: Float, val signalOn: Boolean,
        val rain: Float, val boost: Float, val travel: Float, val encounter: String?, val scenery: Scenery, val frames: Long,
        val carY:Float=0f,val carHeight:Float=0f,val landscape:Boolean=false,val groundY:Float=0f,val horizontalTravel:Double=0.0)
    var renderState = RenderState(160f, 0f, 89.6f, false, 0f, 0f, 0f, null, Scenery.CITY, 0); private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        (listOf("city-dry", "forest-dry", "racer", "hyundai-sedan", "booster") + Encounters.all.map { it.id }).forEach { name ->
            bitmaps[name] = context.assets.open("companion/$name.webp").use { BitmapFactory.decodeStream(it) ?: error("Missing artwork: $name") }
            if (name !in listOf("racer", "hyundai-sedan", "booster")) {
                val bmp = Bitmap.createBitmap(480, 720, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp); c.drawBitmap(bitmaps.getValue(name), null, RectF(0f, 0f, 480f, 720f), paint)
                val event = Encounters.find(name) != null
                val mask = Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, 720f, intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, if (event) Color.TRANSPARENT else Color.BLACK), floatArrayOf(0f, 32f/480, 1-32f/480, 1f), Shader.TileMode.CLAMP)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                c.drawRect(0f, 0f, 480f, 720f, mask); tiles[name] = bmp
                // A shader repeats a continuous material without fractional-pixel bitmap edges.
                asphaltShaders[bmp] = BitmapShader(Bitmap.createBitmap(bmp,176,0,128,720),Shader.TileMode.MIRROR,Shader.TileMode.CLAMP)
            }
        }
    }
    fun artwork(id: String) = bitmaps.getValue(id)
    fun previewService(kind: PlaceKind): Boolean {
        if (!preview || !ServiceVisitAnimation.safe(motion)) return false
        serviceVisit.cancel()
        previewSpeed = 0f; previewTurn = 0; previewAcceleration = 0f
        steer = 0f; pose = 0f; boost = 0f
        return serviceVisit.begin("preview/${kind.name}", kind)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val old=director.viewportHeight
        sceneLayout = SceneLayout.fit(w, h)
        director.viewportHeight = if(sceneLayout.landscape)sceneLayout.width else sceneLayout.height
        if(oldw>0&&old>0)director.active?.let { it.y=(it.y+235f)/old*director.viewportHeight-235f }
    }
    fun restoreTravel(value: Float) { travel = if(value.isFinite()) value.coerceIn(0f,448f) else 0f;sideTravel=travel.toDouble() }
    fun begin(scene: Encounter, immediate: Boolean = false) {
        previousScenery = director.scenery
        director.begin(scene, if (immediate) -60f else travel - 896f)
        if (immediate) travel = 388f
        blend = if (active) 0f else 1f
        invalidate()
    }
    fun resume() { if (active) return; active = true; last = 0; post(frame) }
    fun pause() { active = false; removeCallbacks(frame); last = 0 }
    private val frame = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            val dt = if (last == 0L) 0f else ((now-last)/1000f).coerceIn(0f, .075f); last = now
            if (isShown && windowVisibility == VISIBLE) advance(dt)
            postDelayed(this, 33)
        }
    }
    fun advance(dt: Float) {
        serviceVisit.advance(dt, motion)
        val allowed = motion.animationAllowed && (!preview || !motion.restricted)
        val speed = if (preview) previewSpeed / 3.6f else motion.speedMps ?: 0f
        if (allowed) {
            clock += dt
            val turn = if (preview) previewTurn else motion.turn ?: 0
            steer += (turn - steer) * min(1f, dt*3.6f)
            val acceleration = if (preview) previewAcceleration else motion.acceleration
            pose += (acceleration-pose) * min(1f, dt*5)
            val target = if (skin == CarSkin.RACER && acceleration > 2.2f && speed > 1) 1f else 0f
            boost += (target - boost) * min(1f, dt*8)
            val pixels = min(speed*4.8f, 90f)*dt
            travel = (travel + pixels)%448f
            sideTravel += pixels
            val previous = director.active
            val scenery = director.scenery
            director.step(dt, pixels, speed > .3f, true, question.isNotEmpty() || serviceVisit.frame != null, seen) { event ->
                if (preview) true else collect(event).also { if (it) onEncounter(event.scene) }
            }
            if (previous == null && director.active != null) {
                director.active!!.y = travel - 896f
                previousScenery = scenery; blend = 0f
            }
            blend = min(1f, blend + dt*.6f)
        }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#1b2326"))
        if (width <= 0 || height <= 0) return
        val layout = sceneLayout
        canvas.save(); canvas.scale(layout.scale, layout.scale)
        canvas.clipRect(0f, 0f, layout.width, layout.height)
        val allowed = motion.animationAllowed && (!preview || !motion.restricted)
        if(layout.landscape) {
            val rain=if(allowed)(if(preview)previewRain else motion.rain)else 0f
            val flame=if(allowed&&skin==CarSkin.RACER)boost else 0f
            val car=landscapeRenderer.draw(canvas,layout,skin,steer,if(preview)previewTurn else motion.turn?:0,pose,
                if(preview)previewSpeed/3.6f else motion.speedMps?:0f,rain,flame,clock,sideTravel,director.scenery,previousScenery,blend,
                director.active,allowed,bitmaps.getValue("booster"))
            serviceVisit.frame?.let { serviceRenderer.draw(canvas, layout, car.x, car.y, car.width, car.height, car.groundY, it) }
            if(question.isNotEmpty())drawBubble(canvas,layout.speechBubble(car.x,car.y,car.width,car.height))
            canvas.restore()
            renderState=RenderState(car.x,0f,car.width,car.blink,rain,flame,travel,if(allowed)director.active?.scene?.id else null,
                director.scenery,renderState.frames+1,car.y,car.height,true,car.groundY,sideTravel)
            describe(allowed)
            return
        }
        val offset = if (allowed) travel else 0f
        fun road(scenery: Scenery, alpha: Float = 1f) {
            val bitmap = tiles.getValue(if (scenery == Scenery.CITY) "city-dry" else "forest-dry")
            paint.alpha = (alpha*255).toInt()
            var y = offset-896f
            while (y < layout.height) { drawRoadTile(canvas, bitmap, y); y += 448f }
            paint.alpha = 255
        }
        if (allowed && blend<1f) { road(previousScenery); road(director.scenery,blend) } else road(director.scenery)
        if (allowed) director.active?.let { event ->
            drawRoadTile(canvas, tiles.getValue(event.scene.id), event.y)
        }
        val rain = if (allowed) (if (preview) previewRain else motion.rain) else 0f
        if (rain>0) { paint.color=Color.argb((rain*40).toInt(),22,44,60); canvas.drawRect(0f,0f,layout.width,layout.height,paint) }
        val car=bitmaps.getValue(if (skin==CarSkin.RACER) "racer" else "hyundai-sedan")
        val w=layout.carWidth; val h=w*car.height/car.width
        val centerX=layout.width/2 + if (allowed) steer*layout.laneOffset else 0f
        val speed=if(preview)previewSpeed/3.6f else motion.speedMps?:0f
        val centerY=layout.carY-(if(allowed)pose.coerceIn(-4f,4f)*4.2f else 0f)+if(allowed&&speed>.3f) sin(clock*8)*.55f else 0f
        val x=centerX-w/2;val y=centerY-h/2
        val flame=if(allowed&&skin==CarSkin.RACER)boost else 0f
        // Rain tint uses a translucent paint; vehicle sprites must remain opaque.
        paint.alpha=255
        if(flame>.015f){
            val fh=(38+flame*65)*(1+sin(clock*29)*.045f);val fw=w*.25f
            paint.alpha=(min(1f,flame*1.35f)*255).toInt()
            for(side in listOf(-1,1)) { val fx=centerX+side*w*.125f-fw/2;rect.set(fx,y+h*.88f,fx+fw,y+h*.88f+fh);canvas.drawBitmap(bitmaps.getValue("booster"),null,rect,paint) }
            paint.alpha=255
        }
        rect.set(x,y,x+w,y+h);canvas.drawBitmap(car,null,rect,paint)
        val turn=if(preview)previewTurn else motion.turn?:0
        val blink=allowed&&turn!=0&&clock%.84f<.44f
        if(blink){
            paint.color=Color.rgb(255,188,61);paint.strokeWidth=2.2f;paint.strokeCap=Paint.Cap.ROUND
            if(skin==CarSkin.RACER){
                canvas.drawRect(x+w*if(turn<0).183f else .694f,y+h*.825f,x+w*if(turn<0).318f else .829f,y+h*.847f,paint)
                canvas.drawRect(x+w*if(turn<0).166f else .796f,y+h*.24f,x+w*if(turn<0).204f else .834f,y+h*.251f,paint)
            }else{
                val px=if(turn<0)x+w*.16f else x+w*.84f
                canvas.drawLine(px,y+h*.756f,px+if(turn<0)w*.10f else -w*.10f,y+h*.789f,paint)
                val fx=x+w*if(turn<0).117f else .813f;canvas.drawRect(fx,y+h*.277f,fx+w*.07f,y+h*.287f,paint)
            }
        }
        if(rain>.01f){
            for(i in 0 until ((12+rain*76)*layout.width*layout.height/(320*500)).toInt()){
                val depth=if(i%3==0)1f else .63f
                val dx=((i*73+19+clock*19*depth)%(layout.width+20))-10
                val dy=((i*127+37+clock*(260+speed*2)*depth)%(layout.height+50))-25
                paint.color=Color.argb((rain*if(depth==1f)97 else 51).toInt(),206,231,242);paint.strokeWidth=depth
                canvas.drawLine(dx,dy,dx+2.3f*depth,dy+(9+speed*.19f)*depth,paint)
            }
        }
        serviceVisit.frame?.let { serviceRenderer.draw(canvas, layout, centerX, centerY, w, h, centerY+h/2, it) }
        if(question.isNotEmpty()) drawBubble(canvas,layout.speechBubble(centerX,centerY,w,h))
        paint.alpha=255;canvas.restore()
        renderState=RenderState(centerX,0f,w,blink,rain,flame,travel,if(allowed)director.active?.scene?.id else null,director.scenery,renderState.frames+1,centerY,h)
        describe(allowed)
    }
    private fun describe(allowed:Boolean) {
        val description = (if(preview)"미리보기. " else "")+(if(allowed) "${if(skin==CarSkin.RACER)"네온 레이서" else "현대 세단"}. ${director.active?.scene?.name.orEmpty()}" else "자동 차량일지 기록 중. 주행 제한에 맞춰 연출을 쉬고 있어요.")+
            (if(question.isNotEmpty()) ". ${question.replace('\n',' ')} $questionHint" else "") +
            (serviceVisit.frame?.let { ". ${it.caption}. 방문 기념 연출" } ?: "")
        if(contentDescription!=description)contentDescription=description
    }
    /** Slice at the empty asphalt, so people, kerbs and buildings never stretch with window width. */
    private fun drawRoadTile(c: Canvas, bitmap: Bitmap, y: Float) {
        // Keep objects that overhang the kerb (the cat's tail and the flower cart) intact.
        val edge = 128f * 320f / 480f
        val right = sceneLayout.width - edge
        // Repeat only the clean centre: the outer asphalt contains kerb stains and drain covers.
        val shader=asphaltShaders.getValue(bitmap)
        asphaltMatrix.setScale(320f/480f,320f/480f);asphaltMatrix.postTranslate(edge,y)
        shader.setLocalMatrix(asphaltMatrix);asphaltPaint.shader=shader;asphaltPaint.alpha=paint.alpha
        c.drawRect(edge-.5f,y,right+.5f,y+480f,asphaltPaint)
        source.set(0, 0, 128, 720); rect.set(0f, y, edge, y + 480)
        c.drawBitmap(bitmap, source, rect, paint)
        source.set(352, 0, 480, 720); rect.set(right, y, sceneLayout.width, y + 480)
        c.drawBitmap(bitmap, source, rect, paint)
    }
    private fun drawBubble(c:Canvas,bubble:SceneLayout.SpeechBubble){
        val neon=skin==CarSkin.RACER
        val left=bubble.left;val top=bubble.top
        rect.set(left,top,left+240,top+140)
        val radius=if(neon)12f else 16f
        bubbleShape.reset();bubbleShape.addRoundRect(rect,radius,radius,Path.Direction.CW)
        bubbleTail.reset();bubbleTail.moveTo(bubble.baseX-7,top+139);bubbleTail.lineTo(bubble.baseX+11,top+139);bubbleTail.lineTo(bubble.tipX,bubble.tipY);bubbleTail.close()
        bubbleShape.op(bubbleTail,Path.Op.UNION)
        paint.color=Color.parseColor(if(neon)"#0c251c" else "#faf6e8");paint.style=Paint.Style.FILL;c.drawPath(bubbleShape,paint)
        if(neon){paint.style=Paint.Style.STROKE;paint.strokeWidth=1.2f;paint.color=Color.parseColor("#6cf9a2");c.drawPath(bubbleShape,paint);paint.style=Paint.Style.FILL}
        textPaint.color=Color.parseColor(if(neon)"#8ad9aa" else "#596974");textPaint.textSize=12f
        c.drawText(questionContext,left+18,top+24,textPaint)
        textPaint.color=Color.parseColor(if(neon)"#c5ffda" else "#243745");textPaint.textSize=20f
        question.lines().take(2).forEachIndexed{i,line->drawFitted(c,line,left+18,top+57+i*29,204f,textPaint)}
        textPaint.textSize=11f;textPaint.color=Color.parseColor(if(neon)"#8ad9aa" else "#596974")
        drawFitted(c,questionHint,left+18,top+122,204f,textPaint)
    }
    private fun drawFitted(c:Canvas,text:String,x:Float,y:Float,width:Float,p:Paint){
        val count=p.breakText(text,true,width,null);c.drawText(if(count<text.length)text.take((count-1).coerceAtLeast(0))+"…" else text,x,y,p)
    }
    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }
}
