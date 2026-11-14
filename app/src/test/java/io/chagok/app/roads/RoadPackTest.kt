package io.chagok.app.roads

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class RoadPackTest {
    @Test fun queriesNeighbourTileAcrossBoundaryAndReadsSignedCoordinates() {
        val file=Files.createTempFile("roads-test", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write("""{"format":1,"tileScale":50,"recordBytes":25}""".toByteArray()); zip.closeEntry()
                val bytes=ByteArrayOutputStream()
                DataOutputStream(bytes).use { it.writeLong(7);it.writeByte(0);it.writeInt(37399990);it.writeInt(127100000);it.writeInt(37400000);it.writeInt(127100000) }
                zip.putNextEntry(ZipEntry("1869_6355.bin")); zip.write(bytes.toByteArray()); zip.closeEntry()
            }
            RoadPack(file).use { pack ->
                assertEquals(1,pack.nearby(37.40001,127.1).size)
                assertEquals(RoadGrade.MOTORWAY,pack.nearby(37.40001,127.1).first().grade)
                assertTrue(pack.nearby(34.0,126.0).isEmpty())
                assertTrue(pack.nearby(Double.NaN,127.1).isEmpty())
            }
        } finally { file.delete() }
    }
}
