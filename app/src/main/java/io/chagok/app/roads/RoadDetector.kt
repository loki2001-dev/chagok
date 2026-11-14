package io.chagok.app.roads

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.chagok.app.domain.PlaceFix
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/** Local-only, opt-in. Disk/network-sized operations never run on the monitor's main thread. */
class RoadDetector(context: Context, changed: (String?) -> Unit) : AutoCloseable {
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var pack: RoadPack? = null // worker only
    private val engine=RoadDetectionEngine(::lookup, { task -> worker.execute(task) },
        { task -> main.post(task) }, Instant::now, changed)
    fun sample(enabled: Boolean, fix: PlaceFix?, now: Instant, completed: (RoadGrade?) -> Unit) =
        engine.sample(enabled,fix,now,completed)
    private fun lookup(observation: PlaceFix): RoadMatch {
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
        return RoadMatcher.match(observation,pack!!.nearby(observation.latitude,observation.longitude))
    }
    override fun close() {
        engine.close()
        worker.execute { pack?.close(); pack=null }
        worker.shutdown()
    }
    companion object { const val ENABLED="roads.enabled" }
}
