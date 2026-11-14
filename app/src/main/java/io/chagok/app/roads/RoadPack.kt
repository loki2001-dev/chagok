package io.chagok.app.roads

import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.floor
import org.json.JSONObject

/** Worker-thread only. Bounded tile cache; the entire national geometry never enters memory. */
class RoadPack(file: File) : AutoCloseable {
    private val zip = ZipFile(file)
    private val cache = object : LinkedHashMap<String, List<RoadSegment>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RoadSegment>>?) = size > 16
    }
    init {
        try {
            val metadata = zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).bufferedReader().use { JSONObject(it.readText()) }
            require(metadata.getInt("format") == 1 && metadata.getInt("tileScale") == 50 && metadata.getInt("recordBytes") == 25)
        } catch(e: Exception) { zip.close(); throw e }
    }
    fun nearby(lat: Double, lon: Double): List<RoadSegment> {
        // 100m envelope, comfortably exceeds the 35m ambiguity radius throughout Korea.
        if(lat !in 32.0..40.0 || lon !in 124.0..132.0) return emptyList()
        val result = ArrayList<RoadSegment>()
        for(y in floor((lat-.001)*50).toInt()..floor((lat+.001)*50).toInt())
            for(x in floor((lon-.0013)*50).toInt()..floor((lon+.0013)*50).toInt()) {
                val key="${y}_${x}.bin"
                result.addAll(cache.getOrPut(key) { read(key) })
            }
        return result
    }
    private fun read(key: String): List<RoadSegment> {
        val entry=zip.getEntry(key) ?: return emptyList()
        require(entry.size in 0..5_000_000 && entry.size % 25 == 0L)
        return DataInputStream(zip.getInputStream(entry).buffered()).use { input ->
            List((entry.size/25).toInt()) {
                RoadSegment(input.readLong(), RoadGrade.entries[input.readUnsignedByte()],
                    input.readInt()/1e6, input.readInt()/1e6, input.readInt()/1e6, input.readInt()/1e6)
            }
        }
    }
    override fun close() { cache.clear(); zip.close() }
}
