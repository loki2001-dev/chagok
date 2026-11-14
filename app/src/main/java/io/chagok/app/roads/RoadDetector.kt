package io.chagok.app.roads

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.chagok.app.domain.PlaceFix
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/** Local-only, opt-in. Disk/network-sized operations never run on the monitor's main thread. */
class RoadDetector(context: Context, private val changed: (RoadGrade?, String?) -> Unit) : AutoCloseable {
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val stability=RoadStability()
    private var pack: RoadPack? = null // worker only
    private var closed=false
    private var busy=false
    private var generation=0
    private var active=false
    private var last: Instant? = null
    private var label: String? = null
    private fun publish(value: String?, grade: RoadGrade? = null) { if(label != value) { label=value; changed(grade,value) } }
    fun sample(enabled: Boolean, fix: PlaceFix?, now: Instant) {
        if(closed) return
        if(enabled != active) { active=enabled; generation++; last=null; stability.reset() }
        if(!enabled) { publish(null); return }
        if(!RoadMatcher.fresh(fix, now)) {
            generation++; last=null; stability.reset(); publish("도로 알 수 없음 · 새 위치 대기"); return
        }
        val observation=fix!!
        if(busy || last == observation.at) return
        busy=true
        val token=generation
        worker.execute {
            val match=runCatching {
                if(pack == null) {
                    // Use the build's immutable asset fingerprint to avoid stale packs across upgrades.
                    val hash=app.assets.open("roads/korea-v1.sha256").bufferedReader().use { it.readText().trim() }
                    require(hash.matches(Regex("[a-f0-9]{64}")))
                    val destination=File(app.cacheDir,"roads-$hash.zip")
                    if(!destination.exists()) {
                        val temporary=File.createTempFile("roads-", ".tmp", app.cacheDir)
                        try {
                            val digest=java.security.MessageDigest.getInstance("SHA-256")
                            app.assets.open("roads/korea-v1.zip").use { input ->
                                java.security.DigestInputStream(input,digest).use { source -> temporary.outputStream().use { source.copyTo(it) } }
                            }
                            check(digest.digest().joinToString("") { "%02x".format(it) } == hash)
                            check(temporary.renameTo(destination))
                        } finally { temporary.delete() }
                    }
                    pack=try { RoadPack(destination) } catch(e: Exception) { destination.delete(); throw e }
                }
                RoadMatcher.match(observation, pack!!.nearby(observation.latitude, observation.longitude))
            }
            main.post {
                busy=false
                if(closed || token != generation) return@post
                if(!RoadMatcher.fresh(observation,Instant.now())) { stability.reset(); publish("도로 알 수 없음 · 새 위치 대기"); return@post }
                last=observation.at
                val result=match.fold({ stability.observe(it, observation.at) }, {
                    stability.reset(); RoadMatch(reason="도로 데이터 읽기 실패")
                })
                publish(result.label,result.grade)
            }
        }
    }
    override fun close() {
        closed=true; generation++
        worker.execute { pack?.close(); pack=null }
        worker.shutdown()
    }
    companion object { const val ENABLED="roads.enabled" }
}
