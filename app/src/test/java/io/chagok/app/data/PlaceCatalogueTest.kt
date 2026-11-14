package io.chagok.app.data

import io.chagok.app.domain.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlaceCatalogueTest {
    private val now=Instant.parse("2026-11-14T00:00:00Z")
    private val place=ServicePlace("r","정비소",PlaceKind.REPAIR,37.5,127.0,"주소","fixture")
    private val store=MemoryStore().apply { saveSetting(CatalogueSync.URL,"https://example.com/places.json") }
    private fun bytes(version: Long = 1, places: List<ServicePlace> = listOf(place)) = JSONObject()
        .put("schemaVersion",1).put("version",version).put("publishedAt",now.toString())
        .put("places",JSONArray(places.map(Codec::place))).toString().toByteArray()
    @Test fun `first download installs valid snapshot and daily check skips unchanged period`() {
        var calls=0
        val sync=CatalogueSync(store) { _, tag -> calls++; assertNull(tag); CatalogueResponse(200,"v1",bytes()) }
        sync.update(now); sync.update(now.plusSeconds(86399))
        assertEquals(1,calls); assertEquals(listOf(place),store.places()); assertEquals("1",store.setting("places.version"))
    }
    @Test fun `conditional refresh sends etag and 304 preserves revision`() {
        CatalogueSync(store) { _,_ -> CatalogueResponse(200,"v1",bytes()) }.update(now)
        val revision=store.setting("places.revision")
        CatalogueSync(store) { _,tag -> assertEquals("v1",tag); CatalogueResponse(304,null,byteArrayOf()) }.update(now.plusSeconds(86400))
        assertEquals(revision,store.setting("places.revision"))
    }
    @Test fun `bad download never replaces existing data or success timestamp`() {
        CatalogueSync(store) { _,_ -> CatalogueResponse(200,"v1",bytes()) }.update(now)
        for(response in listOf(CatalogueResponse(200,null,"{".toByteArray()),CatalogueResponse(500,null,byteArrayOf()),CatalogueResponse(200,null,bytes(1,listOf(place.copy(name="changed")))))) {
            assertTrue(runCatching { CatalogueSync(store) { _,_ -> response }.update(now.plusSeconds(86400)) }.isFailure)
            assertEquals(listOf(place),store.places());assertEquals(now.toString(),store.setting("places.checkedAt"))
        }
    }
    @Test fun `new version replaces removed and changed places`() {
        CatalogueSync(store) { _,_ -> CatalogueResponse(200,"v1",bytes()) }.update(now)
        val changed=place.copy(id="new",name="새 정비소")
        CatalogueSync(store) { _,_ -> CatalogueResponse(200,"v2",bytes(2,listOf(changed))) }.update(now.plusSeconds(86400))
        assertEquals(listOf(changed),store.places())
    }
    @Test fun `stale version and unsolicited 304 are rejected`() {
        assertTrue(runCatching { CatalogueSync(store) { _,_ -> CatalogueResponse(304,null,byteArrayOf()) }.update(now) }.isFailure)
        CatalogueSync(store) { _,_ -> CatalogueResponse(200,"v2",bytes(2)) }.update(now)
        assertTrue(runCatching { CatalogueSync(store) { _,_ -> CatalogueResponse(200,"v1",bytes(1)) }.update(now,true) }.isFailure)
        assertEquals("2",store.setting("places.version"))
    }
    @Test fun `changing source or importing during download prevents stale overwrite`() {
        val sync=CatalogueSync(store) { _,_ ->
            store.replacePlaces(listOf(place.copy(id="import")))
            CatalogueResponse(200,"v1",bytes())
        }
        assertTrue(runCatching { sync.update(now) }.isFailure)
        assertEquals("import",store.places().single().id)
    }
    @Test fun `invalid empty duplicated snapshots and insecure urls are rejected`() {
        assertTrue(runCatching { PlaceCatalogue.parse(bytes(1,emptyList())) }.isFailure)
        assertTrue(runCatching { PlaceCatalogue.parse(bytes(1,listOf(place,place))) }.isFailure)
        for(url in listOf("http://example.com/a","file:///tmp/a","https://a:b@example.com/a"))
            assertTrue(runCatching { PlaceCatalogue.validateUrl(url) }.isFailure)
    }
}
