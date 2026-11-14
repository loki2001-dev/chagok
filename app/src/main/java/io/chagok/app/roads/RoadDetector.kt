package io.chagok.app.roads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    // A failed install (low storage, corrupt copy) must not recopy 90 MB on every location fix.
    private val retry=RetryBackoff(SystemClock::elapsedRealtime) // worker only
    private val engine=RoadDetectionEngine(::lookup, { task -> worker.execute(task) },
        { task -> main.post(task) }, Instant::now, changed)
    fun sample(enabled: Boolean, fix: PlaceFix?, now: Instant, completed: (RoadGrade?) -> Unit) =
        engine.sample(enabled,fix,now,completed)
    private fun lookup(observation: PlaceFix): RoadMatch {
        if(pack == null) {
            check(retry.ready()) { "도로 데이터 준비 재시도 대기" }
            pack=try { install() } catch(e: Exception) { retry.failed(); throw e }
            retry.succeeded()
        }
        return RoadMatcher.match(observation,pack!!.nearby(observation.latitude,observation.longitude))
    }
    private fun install(): RoadPack {
        // Use the build's immutable asset fingerprint to avoid stale packs across upgrades.
        val hash=app.assets.open("roads/korea-v1.sha256").bufferedReader().use { it.readText().trim() }
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val destination=File(app.cacheDir,"roads-$hash.zip")
        // Older pack versions and copies interrupted by power-off would otherwise stay ~90 MB each.
        RoadPackFiles.removeStale(app.cacheDir,destination.name)
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
        return try { RoadPack(destination) } catch(e: Exception) { destination.delete(); throw e }
    }
    override fun close() {
        engine.close()
        worker.execute { pack?.close(); pack=null }
        worker.shutdown()
    }
    companion object { const val ENABLED="roads.enabled" }
}

/** Exponential wait after a failed attempt: 1 minute, doubling up to 30 minutes. */
internal class RetryBackoff(private val now: () -> Long,
    private val firstMillis: Long = 60_000, private val maxMillis: Long = 30 * 60_000) {
    private var waitMillis=0L
    private var retryAt: Long? = null
    fun ready() = retryAt?.let { now() >= it } ?: true
    fun failed() {
        waitMillis=if(waitMillis == 0L) firstMillis else minOf(waitMillis*2,maxMillis)
        retryAt=now()+waitMillis
    }
    fun succeeded() { waitMillis=0; retryAt=null }
}

internal object RoadPackFiles {
    /** Deletes every road pack copy and partial copy in [dir] except [keep]. */
    fun removeStale(dir: File, keep: String) {
        dir.listFiles { file -> file.isFile && file.name != keep && file.name.startsWith("roads-") &&
            (file.name.endsWith(".zip") || file.name.endsWith(".tmp")) }?.forEach { it.delete() }
    }
}
