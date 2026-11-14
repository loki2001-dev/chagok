package io.chagok.app.data

import io.chagok.app.domain.*
import org.json.JSONObject
import java.net.URI
import java.net.HttpURLConnection
import java.time.Instant

/** Full, versioned snapshots. Bad or interrupted downloads cannot replace the last good catalogue. */
data class PlaceCatalogue(val version: Long, val publishedAt: Instant, val places: List<ServicePlace>) {
    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        fun parse(bytes: ByteArray): PlaceCatalogue {
            require(bytes.size <= MAX_BYTES) { "목록 파일이 너무 커요" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            require(json.getInt("schemaVersion") == 1) { "지원하지 않는 목록 형식" }
            val version = json.getLong("version"); require(version > 0)
            val date = Instant.parse(json.getString("publishedAt"))
            val rows = json.getJSONArray("places")
            require(rows.length() in 1..100_000) { "목록 크기를 확인해 주세요" }
            val places = (0 until rows.length()).map { Codec.place(rows.getJSONObject(it)) }
            require(places.map { it.id }.toSet().size == places.size) { "중복된 장소 ID" }
            return PlaceCatalogue(version, date, places)
        }
        fun validateUrl(value: String): String {
            val url = URI(value.trim())
            require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null && url.fragment == null) { "HTTPS 목록 주소를 입력해 주세요" }
            return url.toASCIIString()
        }
    }
}

data class CatalogueResponse(val status: Int, val etag: String?, val body: ByteArray)

class CatalogueSync(private val store: DiaryStore,
    private val fetch: (String, String?) -> CatalogueResponse = ::downloadCatalogue) {
    fun update(now: Instant, force: Boolean = false): String {
        val url = store.setting(URL)?.takeIf { it.isNotBlank() } ?: return "목록 주소를 설정하거나 파일을 가져오세요"
        PlaceCatalogue.validateUrl(url)
        val last = store.setting("places.checkedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if(!force && last != null && now >= last && now < last.plusSeconds(86400)) return "갱신 확인 대기"
        val revision = store.setting("places.revision")
        val sameSource = store.setting("places.source") == url
        val etag = if(sameSource && store.places().isNotEmpty()) store.setting("places.etag")?.takeIf { it.isNotBlank() } else null
        val response = fetch(url, etag)
        val parsed = if(response.status == 200) PlaceCatalogue.parse(response.body) else null
        require(response.status == 200 || (response.status == 304 && etag != null)) { "목록 서버 응답 ${response.status}" }
        return store.transaction {
            check(store.setting(URL) == url && store.setting("places.revision") == revision) { "목록 설정이 바뀌어 다운로드를 적용하지 않았어요" }
            if(parsed != null) {
                val oldVersion = if(sameSource) store.setting("places.version")?.toLongOrNull() ?: 0 else 0
                require(parsed.version >= oldVersion) { "이전 버전 목록은 적용하지 않아요" }
                if(!sameSource || parsed.version != oldVersion || store.places().isEmpty()) install(parsed, url)
                else require(store.places().toSet() == parsed.places.toSet()) { "같은 버전의 내용이 달라요" }
                store.saveSetting("places.etag", response.etag.orEmpty())
            }
            store.saveSetting("places.checkedAt", now.toString())
            "목록 확인 완료 · ${store.places().size}곳"
        }
    }
    fun install(catalogue: PlaceCatalogue, source: String) = store.transaction {
        store.replacePlaces(catalogue.places)
        store.saveSetting("places.version", catalogue.version.toString())
        store.saveSetting("places.publishedAt", catalogue.publishedAt.toString())
        store.saveSetting("places.source", source)
        store.saveSetting("places.etag", "")
    }
    companion object { const val URL = "places.url" }
}

private fun downloadCatalogue(url: String, etag: String?): CatalogueResponse {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        etag?.let { connection.setRequestProperty("If-None-Match", it) }
        val status = connection.responseCode
        val bytes = if(status == 200) connection.inputStream.use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while(true) {
                val count = input.read(buffer); if(count < 0) break
                require(result.size() + count <= PlaceCatalogue.MAX_BYTES) { "목록 파일이 너무 커요" }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        } else byteArrayOf()
        return CatalogueResponse(status, connection.getHeaderField("ETag"), bytes)
    } finally { connection.disconnect() }
}
