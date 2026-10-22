package dev.zhanqu.uidriver;

import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.ArrayDeque;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** A separate process observes capture without restarting the app or suppressing accessibility. */
@RunWith(AndroidJUnit4.class)
public class ControlTest {
 private static final Set<String> ALLOWED = Set.of("com.xingin.xhs", "com.zhihu.android", "dev.zhanqu.app.devtest", "com.android.settings",
     "com.google.android.documentsui", "com.android.documentsui",
     "com.google.android.permissioncontroller", "com.android.permissioncontroller");

 @Test public void control() throws Exception {
  Bundle args = InstrumentationRegistry.getArguments();
  Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  assertFalse("Unlock the phone first", context.getSystemService(KeyguardManager.class).isKeyguardLocked());
  Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  Configurator.getInstance().setWaitForIdleTimeout(1000);
  UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
  assertTrue("Foreground app is outside the test scope: " + device.getCurrentPackageName(), ALLOWED.contains(device.getCurrentPackageName()));
  String target = args.getString("target", device.getCurrentPackageName());
  assertTrue("Target package is outside test scope", ALLOWED.contains(target));
  String mode = args.getString("mode", "dump");
  String value = args.getString("value", "");
  if (mode.equals("tapDesc") && target.equals("dev.zhanqu.app.devtest")) {
   assertTrue("Overlay action did not become available", clickOverlayDescription(value));
  } else if (mode.equals("tapText") || mode.equals("tapContains") || mode.equals("tapDesc") || mode.equals("tapId") || mode.equals("input") || mode.equals("inputId")) {
   BySelector selector = By.pkg(target);
   switch (mode) {
    case "tapText": selector.text(value); break;
    case "tapContains": selector.textContains(value); break;
    case "tapDesc": selector.desc(value); break;
    case "tapId": case "inputId": selector.res(value); break;
    case "input": selector.clazz("android.widget.EditText"); break;
    default: throw new IllegalArgumentException(mode);
   }
   // Obtain the actual result in the wait: capture briefly hides its overlay between frames.
   List<UiObject2> objects = device.wait(Until.findObjects(selector), 10000);
   assertNotNull("Element did not appear", objects);
   assertEquals("Ambiguous selector; inspect the tree first", 1, objects.size());
   if (mode.startsWith("input")) objects.get(0).setText(args.getString("text", ""));
   else objects.get(0).click();
  } else if (mode.equals("tap")) {
   int x = Integer.parseInt(args.getString("x"));
   int y = Integer.parseInt(args.getString("y"));
   assertTrue(x >= 0 && y >= 0 && x < device.getDisplayWidth() && y < device.getDisplayHeight());
   assertTrue(device.click(x, y));
  } else if (mode.equals("swipe")) {
   int x1 = Integer.parseInt(args.getString("x1")), y1 = Integer.parseInt(args.getString("y1"));
   int x2 = Integer.parseInt(args.getString("x2")), y2 = Integer.parseInt(args.getString("y2"));
   assertTrue(x1 >= 0 && x2 >= 0 && y1 >= 0 && y2 >= 0 && x1 < device.getDisplayWidth() && x2 < device.getDisplayWidth() && y1 < device.getDisplayHeight() && y2 < device.getDisplayHeight());
   assertTrue(device.swipe(x1, y1, x2, y2, 35));
  } else if (mode.equals("back")) {
   device.pressBack();
  } else if (mode.equals("enter")) {
   device.pressKeyCode(KeyEvent.KEYCODE_ENTER);
  } else if (!mode.equals("dump")) throw new IllegalArgumentException(mode);
  device.waitForIdle(1500);
  assertTrue("Unexpected foreground app; no screenshot taken", ALLOWED.contains(device.getCurrentPackageName()));
  String run = args.getString("runId", "manual").replaceAll("[^A-Za-z0-9_-]", "_");
  File directory = new File(context.getFilesDir(), "commands/" + run);
  assertTrue(directory.isDirectory() || directory.mkdirs());
  device.dumpWindowHierarchy(new File(directory, "tree.xml"));
  assertTrue(device.takeScreenshot(new File(directory, "screen.png")));
  JSONArray geometry = new JSONArray();
  AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
  ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
  if (root != null) queue.add(root);
  int visited = 0;
  while (!queue.isEmpty() && visited++ < 6000) {
   AccessibilityNodeInfo node = queue.removeFirst();
   String id = node.getViewIdResourceName();
   if (id != null && (id.endsWith("/imageListView") || id.endsWith("/photoImageView"))) {
    JSONObject entry = new JSONObject();
    entry.put("id", id);
    Rect screen = new Rect(), parent = new Rect();
    node.getBoundsInScreen(screen); node.getBoundsInParent(parent);
    entry.put("screen", screen.toShortString()); entry.put("parent", parent.toShortString());
    entry.put("availableExtraData", new JSONArray(node.getAvailableExtraData()));
    entry.put("refreshed", node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY, new Bundle()));
    AccessibilityNodeInfo.ExtraRenderingInfo rendering = node.getExtraRenderingInfo();
    if (rendering != null && rendering.getLayoutSize() != null) {
     entry.put("layoutWidth", rendering.getLayoutSize().getWidth());
     entry.put("layoutHeight", rendering.getLayoutSize().getHeight());
    }
    geometry.put(entry);
   }
   for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(child); }
   node.recycle();
  }
  while (!queue.isEmpty()) queue.removeFirst().recycle();
  try (FileOutputStream stream = new FileOutputStream(new File(directory, "geometry.json"))) { stream.write(geometry.toString(2).getBytes(StandardCharsets.UTF_8)); }
  System.out.println("PHONE_DRIVER_OK " + run + " " + device.getCurrentPackageName());
 }

 private boolean clickOverlayDescription(String description) {
  UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  long deadline = SystemClock.uptimeMillis() + 10000;
  do {
   for (AccessibilityWindowInfo window : automation.getWindows()) {
    AccessibilityNodeInfo root = window.getRoot();
    if (root == null) continue;
    if (!"dev.zhanqu.app.devtest".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) { root.recycle(); continue; }
    ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>(); pending.add(root);
    boolean clicked = false;
    int visited = 0;
    while (!pending.isEmpty() && visited++ < 1000) {
     AccessibilityNodeInfo node = pending.removeFirst();
     if (description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()) && node.isVisibleToUser() && node.isEnabled() && node.isClickable()) {
      clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
     }
     if (!clicked) for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) pending.add(child); }
     node.recycle();
     if (clicked) break;
    }
    while (!pending.isEmpty()) pending.removeFirst().recycle();
    if (clicked) return true;
   }
   SystemClock.sleep(50);
  } while (SystemClock.uptimeMillis() < deadline);
  return false;
 }
}
