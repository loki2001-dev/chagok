package io.chagok.app.companion

import kotlin.math.min

/** Uniform artwork scale, with independent asphalt and roadside widths. Coordinates are scene units. */
data class SceneLayout(val scale: Float, val width: Float, val height: Float) {
    val landscape get() = width * 5f >= height * 6f - .001f
    // Safe driving boundary; artwork also retains a small overhang beyond this line.
    val roadside = 112f * 320f / 480f
    val carWidth = 89.6f
    val carY get() = height * .61f
    val laneOffset get() = (width - roadside * 2) / 4 - 8f / 3
    val roadTop get() = height * .36f
    val roadBottom get() = height * .87f
    fun sideCar(steer:Float,acceleration:Float=0f):SideCar {
        val lane=steer.coerceIn(-1f,1f)
        val w=192f*(1f+lane*.075f)
        return SideCar(width*.39f+acceleration.coerceIn(-4f,4f)*2.6f,height*(.69f+lane*.095f),w)
    }
    data class SideCar(val x:Float,val groundY:Float,val width:Float)

    /** Keep the text ahead of the vehicle, with the tail pointing back to its hood. */
    fun speechBubble(carX:Float,carY:Float,carWidth:Float,carHeight:Float):SpeechBubble {
        val carTop=carY-carHeight/2
        val tipX=carX+if(landscape)carWidth*.38f else 0f
        val tipY=if(landscape)carY-carHeight*.18f else carTop+4f
        val left=(if(landscape)tipX-18f else carX-120f).coerceIn(14f,width-254f)
        val top=(tipY-158f).coerceIn(10f,height-150f)
        val baseX=tipX.coerceIn(left+18f,left+222f)
        return SpeechBubble(left,top,baseX,tipX,tipY)
    }
    data class SpeechBubble(val left:Float,val top:Float,val baseX:Float,val tipX:Float,val tipY:Float)

    companion object {
        fun fit(width: Int, height: Int): SceneLayout {
            val landscape=width.toLong()*5>=height.toLong()*6
            val scale = if(landscape)min(width.coerceAtLeast(1)/800f,height.coerceAtLeast(1)/400f)
                else min(width.coerceAtLeast(1) / 320f, height.coerceAtLeast(1) / 500f)
            return SceneLayout(scale, width.coerceAtLeast(1) / scale, height.coerceAtLeast(1) / scale)
        }
    }
}
