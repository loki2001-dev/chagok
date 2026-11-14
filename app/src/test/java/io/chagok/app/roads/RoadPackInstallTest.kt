package io.chagok.app.roads

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RoadPackInstallTest {
    @Test fun failedInstallWaitsBeforeRetryingAndBacksOffUpToThirtyMinutes() {
        var now = 0L
        val retry = RetryBackoff({ now })
        assertTrue(retry.ready())
        retry.failed()
        // Location fixes every few seconds must not each restart the 90 MB copy.
        now = 59_999; assertFalse(retry.ready())
        now = 60_000; assertTrue(retry.ready())
        retry.failed()
        now = 60_000 + 119_999; assertFalse(retry.ready())
        now = 60_000 + 120_000; assertTrue(retry.ready())
        repeat(10) { retry.failed(); now += 30 * 60_000 }
        retry.failed()
        now += 30 * 60_000 - 1; assertFalse(retry.ready())
        now += 1; assertTrue(retry.ready())
        retry.succeeded()
        retry.failed()
        now += 59_999; assertFalse(retry.ready())
    }

    @Test fun staleCopiesAndInterruptedTemporariesAreRemovedButCurrentPackAndOtherFilesStay() {
        val dir = Files.createTempDirectory("roads").toFile()
        try {
            val names = listOf("roads-current.zip", "roads-old.zip", "roads-123.tmp", "image-cache.bin", "roads-notes.txt")
            names.forEach { File(dir, it).writeText("x") }
            RoadPackFiles.removeStale(dir, "roads-current.zip")
            assertEquals(setOf("roads-current.zip", "image-cache.bin", "roads-notes.txt"), dir.list()!!.toSet())
        } finally { dir.deleteRecursively() }
    }
}
