package io.chagok.app.companion

import org.junit.Assert.*
import org.junit.Test

class SceneLayoutTest {
    @Test fun projectionFollowsDrawableBounds() {
        for((w,h) in listOf(320 to 800,841 to 970,599 to 500))assertFalse(SceneLayout.fit(w,h).landscape)
        for((w,h) in listOf(600 to 500,800 to 400,1682 to 970,1400 to 430)) {
            val l=SceneLayout.fit(w,h)
            assertTrue(l.landscape);assertEquals(w.toFloat(),l.width*l.scale,.001f);assertEquals(h.toFloat(),l.height*l.scale,.001f)
        }
    }
    @Test fun leftIsFarUpperLaneAndRightIsNearLowerLane() {
        val l=SceneLayout.fit(1682,970)
        val left=l.sideCar(-1f);val center=l.sideCar(0f);val right=l.sideCar(1f)
        assertTrue(left.groundY<center.groundY&&center.groundY<right.groundY)
        assertTrue(left.width<center.width&&center.width<right.width)
        assertEquals(left.x,right.x,0f)
    }
    @Test fun everyLaneFitsTheRoadIncludingVeryWideWindows() {
        for((w,h) in listOf(600 to 500,800 to 400,1682 to 970,1400 to 430,2400 to 400)) {
            val l=SceneLayout.fit(w,h)
            for(turn in listOf(-1f,0f,1f)) for(accel in listOf(-20f,0f,20f)) {
                val car=l.sideCar(turn,accel)
                assertTrue(car.x-car.width/2>0&&car.x+car.width/2<l.width)
                assertTrue(car.groundY>l.roadTop&&car.groundY<l.roadBottom)
                assertTrue(car.width*l.scale<w*.3f)
            }
        }
    }
    @Test fun speechStaysAheadOfBothProjectionsAndInsideTheWindow() {
        for((w,h) in listOf(320 to 800,841 to 970,600 to 500,1682 to 970,1400 to 430)) {
            val l=SceneLayout.fit(w,h)
            for(turn in listOf(-1f,0f,1f)) {
                val car=l.sideCar(turn)
                val cw=if(l.landscape)car.width else l.carWidth
                val ch=if(l.landscape)cw*.45f else cw*2f
                val x=if(l.landscape)car.x else l.width/2+turn*l.laneOffset
                val y=if(l.landscape)car.groundY-ch/2 else l.carY
                val bubble=l.speechBubble(x,y,cw,ch)
                assertTrue(bubble.left>=0&&bubble.left+240<=l.width)
                assertTrue(bubble.top>=0&&bubble.top+140<=l.height)
                assertTrue(bubble.tipX in (x-cw/2)..(x+cw/2)&&bubble.tipY in (y-ch/2)..(y+ch/2))
                assertTrue(kotlin.math.abs(bubble.tipY-(bubble.top+140))<=18.01f)
                assertTrue(kotlin.math.abs(bubble.tipX-bubble.baseX)<=1f)
                if(l.landscape)assertTrue(bubble.left+120>x+cw/2)
                else assertTrue(bubble.top+140<y-ch/2)
            }
        }
    }
}
