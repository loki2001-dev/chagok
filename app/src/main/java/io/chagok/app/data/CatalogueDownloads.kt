package io.chagok.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One download at a time. Each worker owns its SQLite connection. */
object CatalogueDownloads {
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    fun check(context: Context, force: Boolean = false, done: (String) -> Unit = {}) {
        if(!busy.compareAndSet(false, true)) { done("목록 다운로드 중"); return }
        val app = context.applicationContext
        worker.execute {
            val store = SqliteStore(app)
            val message = try {
                CatalogueSync(store).update(Instant.now(), force)
            } catch (_: Exception) { "갱신 실패 · 기존 목록 유지. 주소와 연결을 확인해 주세요" }
            finally { store.close(); busy.set(false) }
            Handler(Looper.getMainLooper()).post { done(message) }
        }
    }
}
