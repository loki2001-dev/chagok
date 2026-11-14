package io.chagok.app.companion

import android.content.Context
import android.graphics.*
import kotlin.math.*

/** Side-facing artwork in an orthographic road plane. Near and far layers scroll independently. */
internal class LandscapeSceneRenderer(private val context:Context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix=Matrix()
    private val rect=RectF()
    private val images=mutableMapOf<String,Bitmap>()
    private val roads=mutableMapOf<Scenery,List<BitmapShader>>()
    private val characters=mutableMapOf<String,Bitmap>()
    private fun image(name:String)=images.getOrPut(name) {
        context.assets.open("companion/$name.png").use { BitmapFactory.decodeStream(it)!! }
    }
    private fun trimmed(bitmap:Bitmap,area:Rect=Rect(0,0,bitmap.width,bitmap.height)):Bitmap {
        var left=area.right;var right=area.left;var top=area.bottom;var bottom=area.top
        val row=IntArray(area.width())
        for(y in area.top until area.bottom) {
            bitmap.getPixels(row,0,row.size,area.left,y,row.size,1)
            row.forEachIndexed { i,p -> if(Color.alpha(p)>20) {
                left=min(left,area.left+i);right=max(right,area.left+i);top=min(top,y);bottom=max(bottom,y)
            } }
        }
        check(right>left&&bottom>top) { "Empty landscape sprite" }
        return Bitmap.createBitmap(bitmap,left,top,right-left+1,bottom-top+1)
    }
    private val cars by lazy { CarSkin.entries.associateWith { trimmed(image(if(it==CarSkin.RACER)"racer-side" else "hyundai-side")) } }
    private fun road(scenery:Scenery)=roads.getOrPut(scenery) {
        val bitmap=image(if(scenery==Scenery.CITY)"city-side" else "country-side")
        val cuts=listOf(0,(bitmap.height*.36f).toInt(),(bitmap.height*.88f).toInt(),bitmap.height)
        (0..2).map { i -> BitmapShader(Bitmap.createBitmap(bitmap,0,cuts[i],bitmap.width,cuts[i+1]-cuts[i]),Shader.TileMode.MIRROR,Shader.TileMode.CLAMP) }
    }
    private fun character(id:String)=characters.getOrPut(id) {
        val atlas=image("encounters-side")
        // Tight cell bounds keep complete bicycle/cart/easel silhouettes, including the duckling at the grid edge.
        val areas=listOf(Rect(20,160,290,470),Rect(308,50,606,475),Rect(625,185,915,475),Rect(923,50,1208,475),Rect(1210,60,1536,480),
            Rect(0,540,307,1024),Rect(308,555,600,1024),Rect(600,665,922,925),Rect(923,540,1215,1024),Rect(1220,540,1536,1024))
        val a=areas[Encounters.all.indexOfFirst { it.id==id }]
        val sx=atlas.width/1536f;val sy=atlas.height/1024f
        trimmed(atlas,Rect((a.left*sx).toInt(),(a.top*sy).toInt(),(a.right*sx).toInt(),(a.bottom*sy).toInt()))
    }
    private fun backdrop(c:Canvas,l:SceneLayout,scenery:Scenery,travel:Double,alpha:Float) {
        val texture=image(if(scenery==Scenery.CITY)"city-side" else "country-side")
        val scale=l.height/texture.height
        val tile=texture.width*scale
        val cuts=listOf(0f,l.height*.36f,l.height*.88f,l.height)
        road(scenery).forEachIndexed { i,shader ->
            val rate=listOf(.72,1.0,1.18)[i]
            matrix.setScale(scale,scale);matrix.postTranslate(-((travel*rate)%(tile*2)).toFloat(),cuts[i])
            shader.setLocalMatrix(matrix);paint.shader=shader;paint.alpha=(alpha*255).toInt()
            c.drawRect(0f,cuts[i],l.width,cuts[i+1]+.5f,paint)
        }
        paint.shader=null;paint.alpha=255
    }
    private fun encounter(c:Canvas,l:SceneLayout,event:EncounterPass,near:Boolean) {
        if(event.scene.left==near)return
        val bitmap=character(event.scene.id)
        val small=event.scene.id in listOf("calico","corgi","ducklings")
        val h=(if(small)31f else 68f)*(if(near)1.12f else .86f)
        val w=h*bitmap.width/bitmap.height
        val x=l.width-(event.y+235f)
        val foot=l.height*(if(near).975f else .36f)
        paint.color=Color.argb(55,10,18,20)
        c.drawOval(x-w*.4f,foot-3,x+w*.4f,foot+3,paint)
        paint.alpha=255;rect.set(x-w/2,foot-h,x+w/2,foot);c.drawBitmap(bitmap,null,rect,paint)
    }
    data class Pose(val x:Float,val y:Float,val width:Float,val height:Float,val groundY:Float,val blink:Boolean)
    fun draw(c:Canvas,l:SceneLayout,skin:CarSkin,steer:Float,signal:Int,acceleration:Float,speed:Float,rain:Float,boost:Float,
             clock:Float,travel:Double,scenery:Scenery,previous:Scenery,blend:Float,event:EncounterPass?,allowed:Boolean,
             booster:Bitmap):Pose {
        if(allowed&&blend<1f) { backdrop(c,l,previous,travel,1f);backdrop(c,l,scenery,travel,blend) }
        else backdrop(c,l,scenery,if(allowed)travel else 0.0,1f)
        if(rain>0) { paint.color=Color.argb((rain*35).toInt(),20,43,60);c.drawRect(0f,0f,l.width,l.height,paint) }
        if(allowed&&event!=null)encounter(c,l,event,false)
        val position=l.sideCar(if(allowed)steer else 0f,if(allowed)acceleration else 0f)
        val car=cars.getValue(skin)
        val w=position.width;val h=w*car.height/car.width
        val x=position.x-w/2
        val bounce=if(allowed&&speed>.3f)sin(clock*8)*.45f else 0f
        val ground=position.groundY+bounce
        val y=ground-h
        paint.color=Color.argb(72,7,14,18);c.drawOval(x+w*.08f,ground-h*.1f,x+w*.94f,ground+h*.04f,paint)
        paint.alpha=255
        if(boost>.015f&&allowed&&skin==CarSkin.RACER) {
            // The existing exhaust sprite points downward; rotate its flame to the rear (left).
            for(level in listOf(.39f,.52f)) {
                val length=(28+boost*65)*(1+sin(clock*29)*.04f)
                c.save();c.translate(x+5,y+h*level);c.rotate(90f)
                rect.set(-7f,0f,7f,length);paint.alpha=(min(1f,boost*1.35f)*255).toInt();c.drawBitmap(booster,null,rect,paint);c.restore()
            }
        }
        paint.alpha=255;rect.set(x,y,x+w,y+h);c.drawBitmap(car,null,rect,paint)
        val blink=allowed&&signal!=0&&clock%.84f<.44f
        if(blink) {
            val far=signal<0
            val frontX=x+w*(if(far).91f else .95f);val rearX=x+w*(if(far).10f else .055f)
            val frontY=y+h*(if(far).48f else .68f);val rearY=y+h*(if(far).50f else .72f)
            paint.color=Color.argb(48,255,191,62)
            c.drawOval(frontX-10,frontY-7,frontX+10,frontY+7,paint);c.drawOval(rearX-8,rearY-6,rearX+8,rearY+6,paint)
            paint.color=Color.rgb(255,192,61);paint.strokeWidth=2.3f;paint.strokeCap=Paint.Cap.ROUND
            c.drawLine(frontX-4,frontY+1,frontX+4,frontY-1,paint);c.drawLine(rearX-3,rearY,rearX+3,rearY,paint)
        }
        if(allowed&&event!=null)encounter(c,l,event,true)
        if(rain>.01f) {
            for(i in 0 until ((18+rain*95)*l.width*l.height/(800*400)).toInt()) {
                val depth=if(i%3==0)1f else .63f
                val dx=((i*127+43-clock*(60+speed*2)*depth)%(l.width+40)+(l.width+40))%(l.width+40)-20
                val dy=((i*73+19+clock*260*depth)%(l.height+40))-20
                paint.color=Color.argb((rain*if(depth==1f)105 else 55).toInt(),206,231,242);paint.strokeWidth=depth
                c.drawLine(dx,dy,dx-5*depth,dy+11*depth,paint)
            }
        }
        paint.alpha=255
        return Pose(position.x,y+h/2,w,h,ground,blink)
    }
}
