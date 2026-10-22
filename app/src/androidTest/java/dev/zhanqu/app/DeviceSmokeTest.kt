package dev.zhanqu.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.app.KeyguardManager
import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.zhanqu.app.data.NoteDatabase
import dev.zhanqu.app.data.StoredNote
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Runs the actual app UI on a device. It must never target an installed production/preview app. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule val testName = TestName()

    private lateinit var context: Context
    private lateinit var device: UiDevice
    private lateinit var privacy: SharedPreferences
    private var activity: MainActivity? = null
    private var allowed = false
    private var hadDisclosure = false
    private var oldDisclosure = false
    private val fixtureId = "device-smoke-${UUID.randomUUID()}"
    private val fixtureTitle = "自动测试样本 ${fixtureId.takeLast(8)}"
    private val originalText = "自动测试原始正文 ${fixtureId.takeLast(8)}"
    private val editedText = "自动测试修改后的正文 ${fixtureId.takeLast(8)}"

    @Before fun prepare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        assertEquals("Use -PzhanquDeviceTest=true", "dev.zhanqu.app.devtest", context.packageName)
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        device = UiDevice.getInstance(instrumentation)
        assertTrue("Turn on the phone screen before testing", device.isScreenOn)
        assertFalse("Unlock the phone before testing", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        privacy = context.getSharedPreferences("privacy", Context.MODE_PRIVATE)
        hadDisclosure = privacy.contains("disclosure")
        oldDisclosure = privacy.getBoolean("disclosure", false)
        allowed = true
        assertTrue(privacy.edit().putBoolean("disclosure", false).commit())
        runBlocking {
            NoteDatabase.get(context).notes().save(StoredNote(
                fixtureId, fixtureTitle, "合成测试", context.packageName,
                System.currentTimeMillis(), "complete", "{}", originalText
            ))
        }
        launchActivity()
        assertTrue("App did not become visible", device.wait(Until.hasObject(By.pkg(context.packageName)), TIMEOUT))
    }

    @After fun cleanUp() {
        if (!allowed) return
        try {
            if (device.currentPackageName == context.packageName) {
                runCatching { screenshot("final") }
            }
        } finally {
            try {
                closeActivity()
            } finally {
                runBlocking { NoteDatabase.get(context).notes().delete(fixtureId) }
                val editor = privacy.edit()
                if (hadDisclosure) editor.putBoolean("disclosure", oldDisclosure) else editor.remove("disclosure")
                editor.commit()
            }
        }
    }

    @Test fun onboardingAndHelp() {
        text("开启页面采集")
        screenshot("onboarding")
        acceptDisclosure()
        assertTrue(privacy.getBoolean("disclosure", false))
        text("更多").click()
        text("使用帮助").click()
        text("使用粘取")
        text("知道了").click()
        assertTrue(device.wait(Until.gone(By.text("使用粘取")), TIMEOUT))
        screenshot("home")
    }

    @Test fun editingSurvivesActivityRestart() {
        acceptDisclosure()
        openFixture()
        input().text = editedText
        text("未保存的修改")
        text("保存").click()
        assertTrue(device.wait(Until.gone(By.text("未保存的修改")), TIMEOUT))
        val saved = runBlocking { NoteDatabase.get(context).notes().find(fixtureId) }
        assertEquals(editedText, saved?.editedText)
        assertEquals(true, saved?.userEdited)
        closeActivity()
        launchActivity()
        openFixture()
        assertEquals(editedText, input().text)
        screenshot("saved-and-reopened")
    }

    @Test fun unsavedEditCanContinueOrBeDiscarded() {
        acceptDisclosure()
        openFixture()
        input().text = editedText
        text("返回笔记").click()
        text("保留这次修改？")
        text("继续编辑").click()
        assertTrue(device.wait(Until.gone(By.text("保留这次修改？")), TIMEOUT))
        assertEquals(editedText, input().text)
        text("返回笔记").click()
        text("放弃修改").click()
        assertTrue(device.wait(Until.gone(By.text("保留这次修改？")), TIMEOUT))
        openFixture()
        assertEquals(originalText, input().text)
        assertEquals(false, runBlocking { NoteDatabase.get(context).notes().find(fixtureId) }?.userEdited)
        screenshot("discard-confirmed")
    }

    @Test fun exportOpensSystemPickerAndCanCancel() {
        acceptDisclosure()
        openFixture()
        text("导出").click()
        text("Markdown (.md)")
        text("完整数据 (.json)")
        screenshot("export-menu")
        val pickerIntent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val pickerPackage = context.packageManager.resolveActivity(pickerIntent, 0)?.activityInfo?.packageName
            ?: device.executeShellCommand("cmd package resolve-activity --brief -a android.intent.action.CREATE_DOCUMENT -c android.intent.category.OPENABLE -t '*/*'")
                .lineSequence().map { it.trim() }.firstOrNull { it.matches(Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+")) }
                ?.substringBefore('/')
            ?: error("No system document picker is available")
        text("纯文本 (.txt)").click()
        assertTrue("System document picker did not open", device.wait(Until.hasObject(By.pkg(pickerPackage)), TIMEOUT))
        device.pressBack()
        assertTrue("Did not return from document picker", device.wait(Until.hasObject(By.pkg(context.packageName)), TIMEOUT))
        text("返回笔记")
        assertEquals(originalText, input().text)
        screenshot("export-cancelled")
    }

    private fun acceptDisclosure() {
        text("同意并继续").click()
        assertTrue(device.wait(Until.gone(By.text("同意并继续")), TIMEOUT))
        text("更多")
    }

    private fun launchActivity() {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
    }

    private fun closeActivity() {
        val current = activity ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync { current.finish() }
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT
        while (!current.isDestroyed && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        activity = null
        assertTrue("Activity did not finish within the timeout", current.isDestroyed)
    }

    private fun openFixture() {
        input().text = fixtureTitle
        text(fixtureTitle, contains = true).click()
        text("返回笔记")
        input()
    }

    private fun input(): UiObject2 = device.wait(
        Until.findObject(By.pkg(context.packageName).clazz("android.widget.EditText")), TIMEOUT
    ) ?: error("No text input found")

    private fun text(value: String, contains: Boolean = false): UiObject2 {
        // The library search field also contains the fixture title; choose a label/card, not that input.
        val selector = By.pkg(context.packageName)
            .clazz(Pattern.compile("^(?!android\\.widget\\.EditText$).*$"))
        if (contains) selector.textContains(value) else selector.text(value)
        device.wait(Until.findObject(selector), 1000)?.let { return it }
        // Search vertically, including screens with larger fonts. No coordinate assumptions.
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            repeat(6) {
                device.findObject(selector)?.let { return it }
                val scrollable = device.findObject(By.pkg(context.packageName).scrollable(true))
                    ?: return@repeat
                scrollable.scroll(direction, 0.75f)
                device.wait(Until.findObject(selector), 400)?.let { return it }
            }
        }
        return device.wait(Until.findObject(selector), TIMEOUT)
            ?: error("UI text not found: $value")
    }

    private fun screenshot(label: String) {
        assertEquals("Only capture the test app", context.packageName, device.currentPackageName)
        device.waitForIdle()
        val runId = InstrumentationRegistry.getArguments().getString("runId", "manual")
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        val folder = File(context.filesDir, "device-tests/$runId").apply { mkdirs() }
        val target = File(folder, "${testName.methodName}-$label.png")
        assertTrue("Screenshot failed", device.takeScreenshot(target))
    }

    companion object { private const val TIMEOUT = 15_000L }
}
