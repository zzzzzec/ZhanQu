package dev.zhanqu.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class GkdSnapshotImporterTest {
    private val minimal = """{"appId":"com.xingin.xhs","activityId":"test.NoteDetailActivity","screenWidth":1080,"screenHeight":2340,"nodes":[{"id":0,"pid":-1,"attr":{"vid":"imageListView","name":"android.widget.FrameLayout","left":0,"top":0,"right":1080,"bottom":2200}},{"id":1,"pid":0,"attr":{"id":"com.xingin.xhs:id/photoImageView","desc":"图片,第2张,共7张","left":0,"top":250,"right":1080,"bottom":2105}}]}"""
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
        return output.toByteArray()
    }
    private fun assertBaseline(imported: ImportedSnapshot) {
        val profile = BuiltInProfiles.xiaohongshu()
        assertEquals(192, imported.nodeCount)
        assertEquals(32, imported.textNodeCount)
        assertEquals("com.xingin.xhs", imported.snapshot.packageName)
        assertEquals("com.xingin.matrix.notedetail.NoteDetailActivity", imported.snapshot.activityName)
        assertEquals("9.49.2", imported.snapshot.appVersion)
        assertEquals(PageCounter(2, 7), profile.pageCounter(imported.snapshot))
        assertEquals(34, CommentParser.displayedCount(imported.snapshot, profile))
        for (locator in listOf("carousel", "currentImage", "commentList", "commentText", "title", "author", "body")) assertTrue(locator, profile.find(imported.snapshot, locator, false).isNotEmpty())
        val comments = CommentParser.extract(imported.snapshot, profile)
        assertEquals(4, comments.size)
        assertEquals(2, comments.count { !it.isReply })
        assertEquals(2, comments.count { it.isReply })
        assertTrue(comments.filter { it.isReply }.all { !it.parentUnresolved && it.parentId != null && it.parentRelation == "siblingGroup" })
        assertEquals(2, comments.filter { it.isReply }.map { it.parentId }.distinct().size)
        assertTrue(comments.all { !it.author.isNullOrEmpty() && !it.timeText.isNullOrEmpty() })
        assertTrue(comments.all { !it.visible })
        assertTrue(CommentParser.extract(imported.snapshot, profile, true).isEmpty())
    }

    @Test fun sanitized192NodeFixtureParsesPageCounterAndFourOffscreenComments() {
        val json = javaClass.getResourceAsStream("/fixtures/xhs-192-synthetic.json")!!.bufferedReader().use { it.readText() }
        assertBaseline(GkdSnapshotImporter.importJson(json))
    }

    @Test fun offscreenReplyLabelUsesClickableAncestorOnlyAfterVisible() {
        val json = javaClass.getResourceAsStream("/fixtures/xhs-192-synthetic.json")!!.bufferedReader().use { it.readText() }
        val snapshot = GkdSnapshotImporter.importJson(json).snapshot
        val label = snapshot.allNodes().single { it.shortViewId == "loadMoreTV" }
        assertFalse(label.clickable)
        assertEquals("load_more_tv_layout", snapshot.ancestorsOf(label.path).last { it.clickable }.shortViewId)
        assertTrue(BuiltInProfiles.xiaohongshu().find(snapshot, "expandReplies").isEmpty())
        assertFalse(BuiltInProfiles.xiaohongshu().find(snapshot, "noteScroll").isEmpty())
    }

    @Test fun originalPrivateZipRegressionWhenExplicitlyProvided() {
        val path = System.getenv("ZHANQU_REAL_SNAPSHOT_ZIP") ?: System.getProperty("zhanqu.realSnapshotZip")
        assumeTrue("Private user snapshot is optional and is never committed", !path.isNullOrBlank())
        File(path!!).inputStream().use { assertBaseline(GkdSnapshotImporter.importZip(it)) }
    }

    @Test fun importsZipWithoutExtractingImagesAndSelectsOnlyNodeJson() {
        val bytes = zip("metadata.json" to "{\"description\":\"synthetic\"}".toByteArray(), "snapshot.json" to minimal.toByteArray(), "snapshot.png" to byteArrayOf(1, 2, 3))
        val result = GkdSnapshotImporter.importZip(ByteArrayInputStream(bytes))
        assertEquals(2, result.nodeCount)
        assertEquals(PageCounter(2, 7), BuiltInProfiles.xiaohongshu().pageCounter(result.snapshot))
    }

    @Test fun acceptsGkdDuplicateMetadataKeysWithoutChangingTree() {
        val input = minimal.replace("\"nodes\":", "\"gkdAppInfo\":{\"mtime\":1,\"mtime\":2},\"nodes\":")
        val result = GkdSnapshotImporter.importJson(input)
        assertEquals(2, result.nodeCount); assertTrue(result.warnings.any { "重复键" in it })
    }

    @Test fun truncatedJsonIsRejectedInsteadOfInventingMissingNodes() {
        assertThrows(Exception::class.java) { GkdSnapshotImporter.importJson(minimal.dropLast(20)) }
        assertThrows(Exception::class.java) { GkdSnapshotImporter.importJson(minimal + " incomplete") }
    }

    @Test fun rejectsArchiveTraversalMultipleSnapshotsAndMissingSnapshots() {
        assertThrows(Exception::class.java) { GkdSnapshotImporter.importZip(ByteArrayInputStream(zip("../snapshot.json" to minimal.toByteArray()))) }
        assertThrows(Exception::class.java) { GkdSnapshotImporter.importZip(ByteArrayInputStream(zip("a.json" to minimal.toByteArray(), "b.json" to minimal.toByteArray()))) }
        assertThrows(Exception::class.java) { GkdSnapshotImporter.importZip(ByteArrayInputStream(zip("image.png" to byteArrayOf(1)))) }
    }

    @Test fun rejectsDuplicateNodeIdsAndCycles() {
        assertThrows(Exception::class.java) { GkdSnapshotImporter.importJson(minimal.replace("\"id\":1", "\"id\":0")) }
        assertThrows(Exception::class.java) { GkdSnapshotImporter.importJson(minimal.replace("\"pid\":-1", "\"pid\":1")) }
    }

    @Test fun passwordTextIsRemovedDuringImport() {
        val result = GkdSnapshotImporter.importJson(minimal.replace("\"desc\":\"图片,第2张,共7张\"", "\"password\":true,\"text\":\"synthetic-secret\",\"desc\":\"synthetic-secret\""))
        val password = result.snapshot.allNodes().single { it.password }
        assertNull(password.text); assertNull(password.description); assertTrue(result.snapshot.hasSensitiveFields())
    }

    @Test fun offscreenInvertedBoundsRemainEmptyRatherThanBecomingScreenshotRegions() {
        val result = GkdSnapshotImporter.importJson(minimal.replace("\"top\":250", "\"top\":3000"))
        assertNull(BuiltInProfiles.xiaohongshu().imageBounds(result.snapshot))
        assertEquals(2, result.nodeCount)
    }
}
