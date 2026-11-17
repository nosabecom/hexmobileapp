package dev.nosa.hex;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.*;

public class DictationTest {
  private Instrumentation instrumentation;
  private UiAutomation ui;
  private Context context;

  @Before
  public void setup() throws Exception {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    String endpoint = InstrumentationRegistry.getArguments().getString("endpoint");
    if (endpoint != null) Prefs.get(context).edit().putString("endpoint", endpoint).commit();
    ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    var automationInfo = ui.getServiceInfo();
    automationInfo.flags |=
        android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
    ui.setServiceInfo(automationInfo);
    if (BubbleService.instance == null) {
      // Instrumentation restarts our process. Rebind only Hex, preserving other services.
      String configured =
          android.provider.Settings.Secure.getString(
              context.getContentResolver(),
              android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      java.util.List<String> others = new java.util.ArrayList<>();
      if (configured != null) {
        for (String component : configured.split(":")) {
          if (!component.startsWith("dev.nosa.hex/") && !component.isEmpty()) others.add(component);
        }
      }
      ui.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
      try {
        android.provider.Settings.Secure.putString(
            context.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            String.join(":", others));
        Thread.sleep(400);
        others.add("dev.nosa.hex/.BubbleService");
        android.provider.Settings.Secure.putString(
            context.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            String.join(":", others));
        android.provider.Settings.Secure.putInt(
            context.getContentResolver(),
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
            1);
      } finally {
        ui.dropShellPermissionIdentity();
      }
    }
    waitFor(() -> BubbleService.instance != null, 15000);
    instrumentation.runOnMainSync(
        () -> {
          BubbleService.instance.cancel();
          MainActivity.pendingTranscript = MainActivity.lastTranscript = null;
        });
    context.startActivity(
        new Intent()
            .setComponent(
                new ComponentName("dev.nosa.hexfixture", "dev.nosa.hexfixture.FieldsActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    waitFor(() -> find("First field") != null, 10000);
    Thread.sleep(600);
  }

  private void shell(String cmd) throws Exception {
    try (var fd = ui.executeShellCommand(cmd);
        var in = new java.io.FileInputStream(fd.getFileDescriptor())) {
      in.readAllBytes();
    }
  }

  private void waitFor(BooleanSupplier condition, long timeout) throws Exception {
    long until = SystemClock.uptimeMillis() + timeout;
    while (SystemClock.uptimeMillis() < until) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(100);
    }
    assertTrue("Condition timed out", condition.getAsBoolean());
  }

  private AccessibilityNodeInfo find(String description) {
    AccessibilityNodeInfo root = ui.getRootInActiveWindow();
    return find(root, description);
  }

  private AccessibilityNodeInfo find(AccessibilityNodeInfo root, String description) {
    if (root == null) return null;
    ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
    q.add(root);
    AccessibilityNodeInfo result = null;
    while (!q.isEmpty()) {
      AccessibilityNodeInfo n = q.remove();
      if (description.contentEquals(
          n.getContentDescription() == null ? "" : n.getContentDescription())) {
        result = AccessibilityNodeInfo.obtain(n);
      }
      for (int i = 0; i < n.getChildCount(); i++) {
        AccessibilityNodeInfo child = n.getChild(i);
        if (child != null) q.add(child);
      }
      n.recycle();
    }
    return result;
  }

  private AccessibilityNodeInfo retryButton() {
    AccessibilityNodeInfo result = null;
    for (var window : ui.getWindows()) {
      if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
        result = find(window.getRoot(), "Retry last transcription");
      window.recycle();
    }
    return result;
  }

  private void tapRetry() throws Exception {
    waitFor(() -> retryButton() != null, 3000);
    var button = retryButton();
    assertNotNull(button);
    assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    button.recycle();
  }

  private byte[] retainRecording() {
    byte[] audio = DictationService.wav(new byte[3200]);
    instrumentation.runOnMainSync(() -> BubbleService.instance.retainRecording(audio, "parakeet_unified_en"));
    return audio;
  }

  @Test
  public void retryAfterFailureReusesAudioAndModelAndCanFailAgain() throws Exception {
    try (RetryServer server = new RetryServer(503, 200)) {
      begin();
      byte[] audio = retainRecording();
      assertNull("Retry must be hidden while busy", retryButton());
      instrumentation.runOnMainSync(() -> BubbleService.instance.fail("Test failure"));
      String lifecycle = text("Lifecycle status");
      tapRetry();
      waitFor(() -> server.requests.size() == 1 && retryButton() != null, 5000);
      assertEquals("Hello world", text("First field"));
      tapRetry();
      waitFor(() -> text("First field").equals("Hello Retried words"), 5000);
      assertEquals(2, server.requests.size());
      for (byte[] request : server.requests) assertArrayEquals(audio, request);
      assertTrue(server.models.stream().allMatch("parakeet_unified_en"::equals));
      assertEquals(lifecycle, text("Lifecycle status"));
      assertNull(MainActivity.pendingTranscript);
      assertNotNull(retryButton());
    }
  }

  @Test
  public void retryReplacesCompletedTranscriptWithoutDuplicatingIt() throws Exception {
    try (RetryServer server = new RetryServer(200)) {
      begin();
      retainRecording();
      deliver("Nosa");
      assertEquals("Hello Nosa", text("First field"));
      tapRetry();
      waitFor(() -> text("First field").equals("Hello Retried words"), 5000);
      assertNull(MainActivity.pendingTranscript);
    }
  }

  @Test
  public void retryAfterFieldChangeKeepsResultForManualRecovery() throws Exception {
    try (RetryServer server = new RetryServer(200)) {
      begin();
      retainRecording();
      deliver("Nosa");
      focus("Second field");
      tapRetry();
      waitFor(() -> "Retried words".equals(MainActivity.pendingTranscript), 5000);
      assertEquals("Hello Nosa", text("First field"));
      assertEquals("", text("Second field"));
    }
  }

  @Test
  public void cancelledRetryCannotDeliverIntoANewSession() throws Exception {
    try (RetryServer server = new RetryServer(200)) {
      server.release = new java.util.concurrent.CountDownLatch(1);
      begin();
      retainRecording();
      deliver("Nosa");
      tapRetry();
      waitFor(() -> server.requests.size() == 1, 5000);
      instrumentation.runOnMainSync(() -> BubbleService.instance.cancel());
      begin();
      long fresh = BubbleService.instance.sessionId();
      server.release.countDown();
      server.thread.join(3000);
      Thread.sleep(300);
      assertEquals("Hello Nosa", text("First field"));
      assertTrue(BubbleService.instance.isStarting(fresh));
      assertNull(MainActivity.pendingTranscript);
      instrumentation.runOnMainSync(() -> BubbleService.instance.cancel());
      assertNull(retryButton());
    }
  }

  /** Loopback transcription responses: no microphone, private endpoint, or real model required. */
  private final class RetryServer implements AutoCloseable {
    final java.net.ServerSocket socket = new java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
    final List<byte[]> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<String> models = new java.util.concurrent.CopyOnWriteArrayList<>();
    final Map<String, ?> saved = new HashMap<>(Prefs.get(context).getAll());
    final Thread thread;
    volatile java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(0);
    volatile Throwable error;

    RetryServer(int... statuses) throws Exception {
      Prefs.get(context).edit().putString("endpoint", "http://127.0.0.1:" + socket.getLocalPort())
          .putString("model", "different_model").putBoolean("local", true).putBoolean("desktopSync", false).commit();
      socket.setSoTimeout(7000);
      thread = new Thread(() -> {
        try {
          for (int status : statuses) {
            try (java.net.Socket connection = socket.accept()) {
              connection.setSoTimeout(5000);
              var in = new java.io.DataInputStream(connection.getInputStream());
              StringBuilder headers = new StringBuilder();
              int next;
              while (!headers.toString().endsWith("\r\n\r\n") && (next = in.read()) != -1)
                headers.append((char) next);
              int length = 0;
              for (String line : headers.toString().split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                  length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                if (line.toLowerCase(Locale.ROOT).startsWith("x-hex-model:"))
                  models.add(line.substring(line.indexOf(':') + 1).trim());
              }
              byte[] body = new byte[length];
              in.readFully(body);
              requests.add(body);
              if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new java.io.IOException("Test response was never released");
              byte[] response = "{\"text\":\"Retried words\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
              var out = connection.getOutputStream();
              out.write(("HTTP/1.1 " + status + " Test\r\nContent-Type: application/json\r\nContent-Length: "
                  + response.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
              out.write(response);
              out.flush();
            }
          }
        } catch (Throwable failure) { if (!socket.isClosed()) error = failure; }
      }, "hex-test-retry-server");
      thread.start();
    }

    @Override public void close() throws Exception {
      release.countDown();
      socket.close();
      thread.join(3000);
      var editor = Prefs.get(context).edit();
      for (String key : List.of("endpoint", "model", "local", "desktopSync")) {
        Object value = saved.get(key);
        if (value instanceof String s) editor.putString(key, s);
        else if (value instanceof Boolean b) editor.putBoolean(key, b);
        else editor.remove(key);
      }
      editor.commit();
      if (error != null) throw new AssertionError("Retry test server failed", error);
    }
  }

  private void focus(String name) throws Exception {
    AccessibilityNodeInfo n = find(name);
    assertNotNull(n);
    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    n.recycle();
    Thread.sleep(500);
  }

  private String text(String name) {
    AccessibilityNodeInfo n = find(name);
    if (n == null) return "";
    String s = BubbleService.fieldText(n);
    n.recycle();
    return s;
  }

  private void begin() {
    instrumentation.runOnMainSync(() -> assertTrue(BubbleService.instance.captureTarget()));
  }

  private void deliver(String s) {
    instrumentation.runOnMainSync(() -> BubbleService.instance.deliver(s));
  }

  private void openEditorMode(String mode) throws Exception {
    context.startActivity(
        new Intent()
            .setComponent(
                new ComponentName("dev.nosa.hexfixture", "dev.nosa.hexfixture.FieldsActivity"))
            .putExtra("editorMode", mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    waitFor(() -> find("First field") != null, 4000);
    Thread.sleep(600);
  }

  private void holdBubble(boolean drag, boolean cancelTouch) throws Exception {
    waitFor(() -> overlayWidth() > 0, 4000);
    android.graphics.Rect bounds = new android.graphics.Rect();
    for (var window : ui.getWindows()) {
      if (window.getType()
          == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
        window.getBoundsInScreen(bounds);
      window.recycle();
    }
    long down = SystemClock.uptimeMillis();
    touch(down, android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY());
    if (drag)
      touch(down, android.view.MotionEvent.ACTION_MOVE, bounds.centerX() - 80, bounds.centerY());
    if (cancelTouch)
      touch(down, android.view.MotionEvent.ACTION_CANCEL, bounds.centerX(), bounds.centerY());
    Thread.sleep(850);
    if (!cancelTouch)
      touch(
          down,
          android.view.MotionEvent.ACTION_UP,
          bounds.centerX() - (drag ? 80 : 0),
          bounds.centerY());
    Thread.sleep(200);
  }

  private void touch(long down, int action, int x, int y) {
    var event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
    assertTrue(ui.injectInputEvent(event, true));
    event.recycle();
  }

  @Test
  public void holdRecoversIntoCurrentFieldWithoutStartingMicrophone() throws Exception {
    begin();
    focus("Second field");
    deliver("Recovered words");
    String lifecycle = text("Lifecycle status");
    holdBubble(false, false);
    assertEquals("Recovered words", text("Second field"));
    assertEquals("Hello world", text("First field"));
    assertNull(MainActivity.pendingTranscript);
    assertFalse(BubbleService.instance.isCurrentSession(BubbleService.instance.sessionId()));
    assertEquals(lifecycle, text("Lifecycle status"));
  }

  @Test
  public void holdFallsBackToEditorsPasteAction() throws Exception {
    openEditorMode("pasteOnly");
    begin();
    deliver("Nosa");
    assertEquals("Nosa", MainActivity.pendingTranscript);
    holdBubble(false, false);
    waitFor(() -> text("First field").equals("Hello Nosa"), 2000);
    assertFalse(BubbleService.instance.isCurrentSession(BubbleService.instance.sessionId()));
  }

  @Test
  public void failedRecoveryRetainsTranscriptAndDoesNotReplaceText() throws Exception {
    openEditorMode("reject");
    begin();
    deliver("Keep these words");
    holdBubble(false, false);
    assertEquals("Hello world", text("First field"));
    assertEquals("Keep these words", MainActivity.pendingTranscript);
    focus("Second field");
    holdBubble(false, false);
    assertEquals("Keep these words", text("Second field"));
    assertNull(MainActivity.pendingTranscript);
  }

  @Test
  public void acknowledgedButIgnoredEditCanStillBeRecovered() throws Exception {
    openEditorMode("ignore");
    begin();
    deliver("Saved words");
    assertEquals("Hello world", text("First field"));
    assertEquals("Saved words", MainActivity.pendingTranscript);
    focus("Second field");
    holdBubble(false, false);
    assertEquals("Saved words", text("Second field"));
  }

  @Test
  public void holdAfterSuccessfulInsertionCanPasteLastTranscriptElsewhere() throws Exception {
    begin();
    deliver("Nosa");
    assertNull(MainActivity.pendingTranscript);
    focus("Second field");
    holdBubble(false, false);
    assertEquals("Nosa", text("Second field"));
    assertEquals("Hello Nosa", text("First field"));
  }

  @Test
  public void dragAndCancelledTouchNeverPasteOrStartRecording() throws Exception {
    begin();
    focus("Second field");
    deliver("Waiting words");
    int x = Prefs.get(context).getInt("bubbleX", -1), y = Prefs.get(context).getInt("bubbleY", -1);
    try {
      holdBubble(true, false);
      holdBubble(false, true);
      assertEquals("", text("Second field"));
      assertEquals("Waiting words", MainActivity.pendingTranscript);
      assertFalse(BubbleService.instance.isCurrentSession(BubbleService.instance.sessionId()));
    } finally {
      Prefs.get(context).edit().putInt("bubbleX", x).putInt("bubbleY", y).commit();
    }
  }

  @Test
  public void holdToCancelCannotBecomePasteWhenResultArrives() throws Exception {
    begin();
    android.graphics.Rect bounds = new android.graphics.Rect();
    for (var window : ui.getWindows()) {
      if (window.getType()
          == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
        window.getBoundsInScreen(bounds);
      window.recycle();
    }
    long down = SystemClock.uptimeMillis();
    touch(down, android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY());
    deliver("Nosa");
    Thread.sleep(850);
    touch(down, android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY());
    assertEquals("Hello Nosa", text("First field"));
    assertNull(MainActivity.pendingTranscript);
    assertFalse(BubbleService.instance.isCurrentSession(BubbleService.instance.sessionId()));
  }

  @Test
  public void recoveryNeverWritesPasswordField() throws Exception {
    MainActivity.pendingTranscript = "Private words";
    focus("Password field");
    instrumentation.runOnMainSync(() -> BubbleService.instance.retryTranscript());
    assertEquals("Private words", MainActivity.pendingTranscript);
    assertEquals("", text("Password field"));
  }

  @Test
  public void appearanceControlsResizeOverlayAndPersist() throws Exception {
    int oldSize = Prefs.bubbleSize(context), oldTransparency = Prefs.bubbleTransparency(context);
    try {
      AppearanceActivity activity = openAppearance();
      setSlider("Bubble size", 75);
      setSlider("Bubble transparency", 55);
      instrumentation.runOnMainSync(activity::finish);
      waitFor(() -> overlayWidth() > 0, 4000);
      Thread.sleep(300);
      int small = overlayWidth();
      activity = openAppearance();
      assertEquals(75, (int) find("Bubble size").getRangeInfo().getCurrent());
      assertEquals(55, (int) find("Bubble transparency").getRangeInfo().getCurrent());
      setSlider("Bubble size", 150);
      instrumentation.runOnMainSync(activity::finish);
      waitFor(() -> overlayWidth() > small * 1.5, 4000);
      begin();
      instrumentation.runOnMainSync(() -> BubbleService.instance.state("■"));
      waitFor(() -> overlayWidth() > small * 3, 4000);
      instrumentation.runOnMainSync(() -> BubbleService.instance.cancel());
    } finally {
      Prefs.get(context)
          .edit()
          .putInt("bubbleSize", oldSize)
          .putInt("bubbleTransparency", oldTransparency)
          .commit();
    }
  }

  private AppearanceActivity openAppearance() throws Exception {
    AppearanceActivity activity =
        (AppearanceActivity)
            instrumentation.startActivitySync(
                new Intent(context, AppearanceActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    waitFor(() -> find("Bubble size") != null, 4000);
    return activity;
  }

  private void setSlider(String name, int value) throws Exception {
    AccessibilityNodeInfo node = find(name);
    assertNotNull(node);
    Bundle args = new Bundle();
    args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
    assertTrue(
        node.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args));
    node.recycle();
    Thread.sleep(200);
  }

  private int overlayWidth() {
    int width = 0;
    for (var window : ui.getWindows()) {
      if (window.getType()
          == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        window.getBoundsInScreen(bounds);
        width = bounds.width();
      }
      window.recycle();
    }
    return width;
  }

  private boolean overlayKeepsScreenOn() {
    boolean[] awake = {false};
    instrumentation.runOnMainSync(() -> {
      for (android.view.View view : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
        if (view.getLayoutParams() instanceof android.view.WindowManager.LayoutParams params
            && params.type == android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
          awake[0] |= (params.flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
      }
    });
    return awake[0];
  }

  @Test
  public void screenStaysAwakeOnlyDuringEnabledDictationAndReleasesAfterEveryOutcome() throws Exception {
    boolean hadSetting = Prefs.get(context).contains("keepScreenOn");
    boolean oldSetting = Prefs.keepScreenOn(context);
    try {
      Prefs.get(context).edit().remove("keepScreenOn").commit();
      assertTrue("Enabled by default for existing installs", Prefs.keepScreenOn(context));
      waitFor(() -> overlayWidth() > 0, 4000);
      assertFalse("An idle bubble must allow sleep", overlayKeepsScreenOn());
      String lifecycle = text("Lifecycle status");
      begin();
      assertTrue("Microphone startup must keep the screen on", overlayKeepsScreenOn());
      instrumentation.runOnMainSync(() -> BubbleService.instance.state("■"));
      assertTrue(overlayKeepsScreenOn());
      instrumentation.runOnMainSync(() -> BubbleService.instance.state("…"));
      assertTrue("Transcription must stay awake too", overlayKeepsScreenOn());
      Prefs.get(context).edit().putBoolean("keepScreenOn", false).commit();
      waitFor(() -> !overlayKeepsScreenOn(), 3000);
      Prefs.get(context).edit().putBoolean("keepScreenOn", true).commit();
      waitFor(this::overlayKeepsScreenOn, 3000);
      deliver("Awake words");
      assertFalse("Completion must release the screen", overlayKeepsScreenOn());
      begin();
      instrumentation.runOnMainSync(() -> BubbleService.instance.fail("Test failure"));
      assertFalse("Failure must release the screen", overlayKeepsScreenOn());
      begin();
      instrumentation.runOnMainSync(() -> BubbleService.instance.cancel());
      assertFalse("Cancellation must release the screen", overlayKeepsScreenOn());
      assertEquals("Screen control must preserve the editor lifecycle", lifecycle, text("Lifecycle status"));
      Prefs.get(context).edit().putBoolean("keepScreenOn", false).commit();
      begin();
      assertFalse("A disabled setting must allow sleep during dictation", overlayKeepsScreenOn());
    } finally {
      instrumentation.runOnMainSync(() -> BubbleService.instance.cancel());
      var edit = Prefs.get(context).edit();
      if (hadSetting) edit.putBoolean("keepScreenOn", oldSetting);
      else edit.remove("keepScreenOn");
      edit.commit();
    }
  }

  @Test
  public void serverTokenRemainsVisuallyMasked() throws Exception {
    MainActivity activity =
        (MainActivity)
            instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.runOnMainSync(
        () -> {
          android.widget.EditText field =
              activity.field(
                  "Secret",
                  "test-value",
                  android.text.InputType.TYPE_CLASS_TEXT
                      | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
          assertTrue(
              field.getTransformationMethod()
                  instanceof android.text.method.PasswordTransformationMethod);
          activity.finish();
        });
  }

  @Test
  public void replacesSelectionWithoutLosingSurroundingText() throws Exception {
    begin();
    deliver("Nosa");
    waitFor(() -> text("First field").equals("Hello Nosa"), 3000);
  }

  private void hiddenCursorEditor() throws Exception {
    Assume.assumeTrue(Build.VERSION.SDK_INT >= 33);
    openEditorMode("hiddenCursor");
    AccessibilityNodeInfo field = find("First field");
    assertEquals(-1, field.getTextSelectionStart());
    assertEquals(-1, field.getTextSelectionEnd());
    field.recycle();
    waitFor(() -> {
      boolean[] ready = {false};
      instrumentation.runOnMainSync(() -> {
        var input = BubbleService.instance.getInputMethod();
        ready[0] = input != null && input.getCurrentInputStarted()
            && input.getCurrentInputConnection() != null;
      });
      return ready[0];
    }, 4000);
  }

  private void selectFirst(int start, int end) throws Exception {
    AccessibilityNodeInfo field = find("First field");
    Bundle selection = new Bundle();
    selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
    selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
    assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection));
    field.recycle();
    Thread.sleep(200);
  }

  @Test
  public void hiddenCursorReplacesSelectionThroughInputConnection() throws Exception {
    hiddenCursorEditor();
    begin();
    deliver("Nosa 🌍");
    waitFor(() -> text("First field").equals("Hello Nosa 🌍"), 3000);
    assertNull(MainActivity.pendingTranscript);
  }

  @Test
  public void hiddenCursorInsertsInMiddleWithoutReplacingSurroundingText() throws Exception {
    hiddenCursorEditor();
    selectFirst(6, 6);
    begin();
    deliver("Nosa's ");
    waitFor(() -> text("First field").equals("Hello Nosa's world"), 3000);
    assertNull(MainActivity.pendingTranscript);
  }

  @Test
  public void hiddenCursorMovedDuringRecordingDoesNotReceiveTranscript() throws Exception {
    hiddenCursorEditor();
    begin();
    selectFirst(0, 0);
    deliver("Saved words");
    assertEquals("Hello world", text("First field"));
    assertEquals("Saved words", MainActivity.pendingTranscript);
  }

  @Test
  public void hiddenCursorChangedFieldDoesNotReceiveTranscript() throws Exception {
    hiddenCursorEditor();
    begin();
    focus("Second field");
    deliver("Saved words");
    assertEquals("Hello world", text("First field"));
    assertEquals("", text("Second field"));
    assertEquals("Saved words", MainActivity.pendingTranscript);
  }

  @Test
  public void hiddenCursorCanRecoverLastTranscript() throws Exception {
    hiddenCursorEditor();
    MainActivity.pendingTranscript = "Recovered words";
    instrumentation.runOnMainSync(() -> BubbleService.instance.retryTranscript());
    waitFor(() -> text("First field").equals("Hello Recovered words"), 3000);
    assertNull(MainActivity.pendingTranscript);
  }

  @Test
  public void hiddenCursorWithoutInputStillAllowsRecordingAndRetainsTranscript() throws Exception {
    openEditorMode("hiddenCursorNoInput");
    begin();
    deliver("Saved words");
    assertEquals("Hello world", text("First field"));
    assertEquals("Saved words", MainActivity.pendingTranscript);
  }

  @Test
  public void changedFieldNeverReceivesOldTranscript() throws Exception {
    begin();
    focus("Second field");
    deliver("private words");
    assertEquals("Hello world", text("First field"));
    assertEquals("", text("Second field"));
    assertEquals("private words", MainActivity.pendingTranscript);
  }

  @Test
  public void editedTextIsNotOverwritten() throws Exception {
    begin();
    AccessibilityNodeInfo n = find("First field");
    Bundle b = new Bundle();
    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "New typing");
    n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    n.recycle();
    Thread.sleep(300);
    deliver("old result");
    assertEquals("New typing", text("First field"));
    assertEquals("old result", MainActivity.pendingTranscript);
  }

  @Test
  public void emptyFieldDoesNotInsertHint() throws Exception {
    focus("Second field");
    begin();
    deliver("Hello");
    waitFor(() -> text("Second field").equals("Hello"), 3000);
  }

  @Test
  public void passwordHidesBubble() throws Exception {
    focus("Password field");
    waitFor(() -> !BubbleService.instance.isBubbleVisible(), 4000);
    instrumentation.runOnMainSync(() -> assertFalse(BubbleService.instance.captureTarget()));
  }

  @Test
  public void homeHidesBubble() throws Exception {
    ui.executeShellCommand("input keyevent KEYCODE_HOME").close();
    waitFor(() -> !BubbleService.instance.isBubbleVisible(), 4000);
  }

  @Test
  public void cancelDiscardsLateResult() throws Exception {
    begin();
    instrumentation.runOnMainSync(() -> BubbleService.instance.cancel());
    deliver("discard this");
    assertEquals("Hello world", text("First field"));
    assertNull(MainActivity.pendingTranscript);
  }

  @Test
  public void wavHeaderAndUnicodeInsertion() {
    byte[] wav = DictationService.wav(new byte[3200]);
    assertEquals(3244, wav.length);
    assertEquals('R', wav[0]);
    var r = TextInsertion.insert("Hi 🌍!", 3, 5, "Nosa");
    assertEquals("Hi Nosa!", r.text());
    assertEquals(7, r.cursor());
  }

  @Test
  public void bubbleKeepsOriginalAppWhenHexSettingsAreInBackground() throws Exception {
    MainActivity settings =
        (MainActivity)
            instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    context.startActivity(
        new Intent()
            .setComponent(
                new ComponentName("dev.nosa.hexfixture", "dev.nosa.hexfixture.FieldsActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    waitFor(() -> find("First field") != null, 4000);
    waitFor(() -> overlayWidth() > 0, 4000);
    Prefs.get(context).edit().putBoolean("local", false).commit();
    find("First field").performAction(AccessibilityNodeInfo.ACTION_CLICK);
    waitFor(() -> keyboardVisible(), 4000);
    String lifecycleBefore = text("Lifecycle status");
    try {
      android.graphics.Rect bounds = new android.graphics.Rect();
      for (var window : ui.getWindows()) {
        if (window.getType()
            == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
          window.getBoundsInScreen(bounds);
        window.recycle();
      }
      assertFalse("Bubble must be visible", bounds.isEmpty());
      long now = SystemClock.uptimeMillis();
      for (int action :
          new int[] {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
        var event =
            android.view.MotionEvent.obtain(
                now, SystemClock.uptimeMillis(), action, bounds.centerX(), bounds.centerY(), 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue(ui.injectInputEvent(event, true));
        event.recycle();
      }
      long until = SystemClock.uptimeMillis() + 1500;
      while (SystemClock.uptimeMillis() < until) {
        assertTrue("Keyboard disappeared during the microphone handoff", keyboardVisible());
        for (var window : ui.getWindows()) {
          if (window.isFocused()
              && window.getType()
                  == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
            var root = window.getRoot();
            if (root != null) {
              assertEquals(
                  "Recording stole window focus",
                  "dev.nosa.hexfixture",
                  String.valueOf(root.getPackageName()));
              root.recycle();
            }
          }
          window.recycle();
        }
        Thread.sleep(15);
      }
      assertNotNull("Tapping Hex must keep the original app in front", find("First field"));
      String services;
      try (var fd = ui.executeShellCommand("dumpsys activity services dev.nosa.hex");
          var in = new java.io.FileInputStream(fd.getFileDescriptor())) {
        services = new String(in.readAllBytes());
      }
      assertTrue("Microphone must be recording", services.contains("isForeground=true"));
      assertEquals(
          "Starting the microphone must not pause/resume the editor",
          lifecycleBefore,
          text("Lifecycle status"));
      instrumentation.runOnMainSync(() -> BubbleService.instance.cancel());
      assertEquals("Hello world", text("First field"));
      var field = find("First field");
      Bundle selection = new Bundle();
      selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 6);
      selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 11);
      field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
      field.recycle();
      Thread.sleep(100);
      begin();
      deliver("Nosa");
      waitFor(() -> text("First field").equals("Hello Nosa"), 3000);
    } finally {
      instrumentation.runOnMainSync(
          () -> {
            BubbleService.instance.cancel();
            settings.finish();
          });
    }
  }

  private boolean keyboardVisible() {
    boolean visible = false;
    for (var window : ui.getWindows()) {
      if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        visible = true;
      window.recycle();
    }
    return visible;
  }

  @Test
  public void cancelledStartupCannotStartMicrophoneOrCancelNewSession() throws Exception {
    begin();
    long stale = BubbleService.instance.sessionId();
    instrumentation.runOnMainSync(() -> BubbleService.instance.cancel());
    begin();
    long fresh = BubbleService.instance.sessionId();
    instrumentation.runOnMainSync(() -> BubbleService.instance.startCapture(stale));
    Thread.sleep(700);
    assertTrue(BubbleService.instance.isStarting(fresh));
    instrumentation.runOnMainSync(() -> BubbleService.instance.cancel());
    assertNotNull(find("First field"));
  }

  @Test
  public void microphoneStartsFromBubbleAndCancels() throws Exception {
    Prefs.get(context).edit().putBoolean("local", false).commit();
    begin();
    instrumentation.runOnMainSync(
        () -> BubbleService.instance.startCapture(BubbleService.instance.sessionId()));
    Thread.sleep(2500);
    String services =
        new String(
            new java.io.FileInputStream(
                    ui.executeShellCommand("dumpsys activity services dev.nosa.hex")
                        .getFileDescriptor())
                .readAllBytes());
    assertTrue(services, services.contains("isForeground=true"));
    instrumentation.runOnMainSync(() -> BubbleService.instance.cancel());
    Thread.sleep(600);
    assertEquals("Hello world", text("First field"));
  }

  @Test
  public void selectedModelLatencyOverPhoneConnection() throws Exception {
    byte[] audio;
    try (var in = instrumentation.getContext().getAssets().open("jfk.wav")) {
      audio = in.readAllBytes();
    }
    String model = Prefs.get(context).getString("model", "parakeet_unified_en");
    for (int attempt = 1; attempt <= 3; attempt++) {
      long started = SystemClock.elapsedRealtime();
      var conn =
          (java.net.HttpURLConnection)
              new java.net.URI(Prefs.endpoint(context) + "/transcribe").toURL().openConnection();
      conn.setConnectTimeout(10000);
      conn.setReadTimeout(90000);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Authorization", "Bearer " + Prefs.token(context));
      conn.setRequestProperty("X-Hex-Model", model);
      conn.setRequestProperty("Content-Type", "audio/wav");
      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(audio.length);
      try {
        try (var out = conn.getOutputStream()) {
          out.write(audio);
        }
        long uploaded = SystemClock.elapsedRealtime();
        assertEquals(200, conn.getResponseCode());
        org.json.JSONObject result;
        try (var in = conn.getInputStream()) {
          result =
              new org.json.JSONObject(
                  new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        Bundle timing = new Bundle();
        timing.putString(
            "stream",
            "\n"
                + model
                + " attempt="
                + attempt
                + " audio_bytes="
                + audio.length
                + " upload_call_ms="
                + (uploaded - started)
                + " request_ms="
                + (SystemClock.elapsedRealtime() - started)
                + " server_ms="
                + result.getLong("server_ms")
                + "\n");
        instrumentation.sendStatus(0, timing);
        assertTrue(result.getString("text").toLowerCase(Locale.US).contains("country"));
      } finally {
        conn.disconnect();
      }
    }
  }

  @Test
  public void realParakeetAndCohereTranscriptionAndInsertion() throws Exception {
    byte[] audio;
    try (var in = instrumentation.getContext().getAssets().open("jfk.wav")) {
      audio = in.readAllBytes();
    }
    String selected = Prefs.get(context).getString("model", "parakeet_unified_en");
    try {
      for (String model : new String[] {"parakeet_unified_en", "cohere_transcribe"}) {
        Prefs.get(context).edit().putString("model", model).commit();
        if (model.equals("parakeet_unified_en")) begin();
        long started = SystemClock.elapsedRealtime();
        String result = RemoteTranscriber.transcribe(context, audio);
        Bundle timing = new Bundle();
        timing.putString(
            "stream",
            "\n" + model + " request_ms=" + (SystemClock.elapsedRealtime() - started) + "\n");
        instrumentation.sendStatus(0, timing);
        assertTrue(model + ": " + result, result.toLowerCase(Locale.US).contains("country"));
        if (model.equals("parakeet_unified_en")) {
          deliver(result);
          waitFor(() -> text("First field").toLowerCase(Locale.US).contains("country"), 4000);
        }
      }
    } finally {
      Prefs.get(context).edit().putString("model", selected).commit();
      RemoteTranscriber.prepareInBackground(context);
    }
  }
}
