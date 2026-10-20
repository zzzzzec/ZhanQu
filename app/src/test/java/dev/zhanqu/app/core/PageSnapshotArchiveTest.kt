package dev.zhanqu.app.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class PageSnapshotArchiveTest {
    private val area = UiBounds(0, 24, 1080, 2300)
    private fun snapshot() = UiSnapshot("example.app", null, 1080, 2400, listOf(
        UiNode("0", className = "android.widget.FrameLayout", bounds = area, children = listOf(
            UiNode("0/2", "example.app:id/title", "android.widget.TextView", "合成页面", "测试", UiBounds(10, 40, 500, 200),
                clickable = true, focusable = true, checkable = true, checked = true, longClickable = true)
        ))), "1.0")
    @Test fun syntheticZipRoundTripsThroughExistingImporter() {
        val source = snapshot()
        val json = PageSnapshotArchive.json(source, 1234, area)
        val output = ByteArrayOutputStream()
        PageSnapshotArchive.write(output, 1234, json, byteArrayOf(1, 2, 3))
        val imported = GkdSnapshotImporter.importZip(output.toByteArray().inputStream()).snapshot
        assertEquals(source.packageName, imported.packageName)
        assertNull(imported.activityName)
        assertEquals(source.appVersion, imported.appVersion)
        val actual = imported.roots.single().children.single()
        assertEquals("合成页面", actual.text)
        assertEquals("example.app:id/title", actual.viewId)
        assertTrue(actual.focusable); assertTrue(actual.longClickable); assertEquals(true, actual.checked)
        val parsed = JSONObject(json.toString(Charsets.UTF_8))
        assertEquals(2, parsed.getJSONArray("nodes").getJSONObject(1).getJSONObject("attr").getInt("index"))
        assertTrue(parsed.isNull("activityId")); assertTrue(parsed.isNull("gkdAppInfo"))
        assertTrue(parsed.getJSONObject("zhanqu").getBoolean("screenshotPresent"))
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertEquals("1234.json", zip.nextEntry.name); zip.closeEntry()
            assertEquals("1234.png", zip.nextEntry.name); zip.closeEntry(); assertNull(zip.nextEntry)
        }
    }
    @Test fun passwordContentExcludedEvenFromSyntheticWriterInput() {
        val value = UiSnapshot("example.app", null, 100, 100, listOf(UiNode("0", text = "SECRET123", description = "SECRET456", password = true)))
        val text = PageSnapshotArchive.json(value, 1, UiBounds(0,0,100,100)).toString(Charsets.UTF_8)
        assertFalse(text.contains("SECRET"))
        assertTrue(GkdSnapshotImporter.importJson(text).snapshot.roots.single().password)
    }
    @Test fun archiveRejectsMissingImageAndUnsafeId() {
        assertThrows(IllegalArgumentException::class.java) { PageSnapshotArchive.write(ByteArrayOutputStream(), -1, byteArrayOf(1), byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { PageSnapshotArchive.write(ByteArrayOutputStream(), 1, byteArrayOf(1), byteArrayOf()) }
    }
    @Test fun rejectsExcessDepthRatherThanSilentlyTruncating() {
        var n = UiNode("0")
        repeat(82) { n = UiNode("0", children = listOf(n)) }
        assertThrows(IllegalArgumentException::class.java) { PageSnapshotArchive.json(UiSnapshot("example", null, 100,100,listOf(n)), 1, UiBounds(0,0,100,100)) }
    }
}
