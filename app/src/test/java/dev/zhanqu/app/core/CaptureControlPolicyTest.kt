package dev.zhanqu.app.core

import org.junit.Assert.*
import org.junit.Test

class CaptureControlPolicyTest {
    @Test fun `empty selection and duplicate start are disabled`() {
        assertFalse(CaptureControlPolicy.canStart(false, false, false))
        assertFalse(CaptureControlPolicy.canStart(true, true, true))
        assertTrue(CaptureControlPolicy.canStart(false, true, false))
        assertTrue(CaptureControlPolicy.canStart(false, false, true))
    }
    @Test fun `stopping blocks resume and repeated stop`() {
        assertFalse(CaptureControlPolicy.canControl(true, "stopping"))
        assertFalse(CaptureControlPolicy.canControl(false, "finished"))
        assertTrue(CaptureControlPolicy.canControl(true, "comments"))
    }
    @Test fun `saving status takes priority over paused`() {
        assertEquals("正在保存", CaptureControlPolicy.status(true, true, "stopping"))
        assertEquals("已暂停", CaptureControlPolicy.status(true, true, "images"))
        assertEquals("采集中", CaptureControlPolicy.status(true, false, "images"))
    }
    @Test fun `completed session is not mislabeled paused`() {
        assertEquals("已保留结果", CaptureControlPolicy.status(false, true, "finished"))
        assertEquals("准备就绪", CaptureControlPolicy.status(false, false, "idle"))
    }
}
