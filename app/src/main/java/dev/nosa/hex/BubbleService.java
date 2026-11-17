package dev.nosa.hex;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.util.Objects;

public class BubbleService extends AccessibilityService {
  static volatile BubbleService instance;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private WindowManager wm;
  private LinearLayout overlay;
  private DotScopeView bubble;
  private RetryView retry;
  private WindowManager.LayoutParams params;
  private AccessibilityNodeInfo target;
  private String original;
  private int start, end;
  private int replaceStart, replaceEnd;
  private byte[] retryAudio;
  private String retryEngine;
  private AccessibilityNodeInfo retryTarget;
  private RetryEdit retryEdit;
  private EditorInput.Snapshot inputSnapshot;
  private boolean busy;
  private long generation;
  private boolean starting;
  private String bubbleState = "idle";
  private Runnable hold;
  private final SharedPreferences.OnSharedPreferenceChangeListener appearanceListener =
      (prefs, key) -> {
        if ("bubbleSize".equals(key) || "bubbleTransparency".equals(key))
          handler.post(this::refreshAppearance);
      };
  private final BroadcastReceiver screenOff =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          cancel();
          hide();
        }
      };
  private final Runnable refresh = this::updateFocus;

  @Override
  protected void onServiceConnected() {
    instance = this;
    if (Build.VERSION.SDK_INT >= 33) {
      var info = getServiceInfo();
      info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR;
      setServiceInfo(info);
    }
    registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    wm = getSystemService(WindowManager.class);
    Prefs.get(this).registerOnSharedPreferenceChangeListener(appearanceListener);
    updateFocus();
  }

  @Override
  @android.annotation.TargetApi(33)
  public android.accessibilityservice.InputMethod onCreateInputMethod() {
    return new EditorInput(this);
  }

  private EditorInput.Snapshot captureInput(AccessibilityNodeInfo node) {
    if (Build.VERSION.SDK_INT < 33 || !(getInputMethod() instanceof EditorInput input)) return null;
    return input.capture(node);
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent e) {
    handler.removeCallbacks(refresh);
    handler.postDelayed(refresh, 120);
  }

  private AccessibilityNodeInfo focused() {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) return null;
    AccessibilityNodeInfo result = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    root.recycle();
    return result;
  }

  private static int selection(AccessibilityNodeInfo node, boolean start) {
    return fieldText(node).isEmpty()
        ? 0
        : start ? node.getTextSelectionStart() : node.getTextSelectionEnd();
  }

  static String fieldText(AccessibilityNodeInfo node) {
    return node.isShowingHintText() || node.getText() == null ? "" : node.getText().toString();
  }

  static boolean eligible(AccessibilityNodeInfo node) {
    return node != null
        && node.isEditable()
        && node.isEnabled()
        && node.isVisibleToUser()
        && node.isFocused()
        && !node.isPassword()
        && !"dev.nosa.hex"
            .contentEquals(node.getPackageName() == null ? "" : node.getPackageName());
  }

  private void updateFocus() {
    if (getSystemService(KeyguardManager.class).isKeyguardLocked()) {
      cancel();
      hide();
      return;
    }
    if (busy) return;
    AccessibilityNodeInfo node = focused();
    boolean show = eligible(node);
    if (node != null) node.recycle();
    if (show) show();
    else hide();
  }

  private int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density);
  }

  private void show() {
    if (bubble != null) return;
    RemoteTranscriber.prepareInBackground(this);
    bubble = new DotScopeView(this);
    retry = new RetryView(this);
    retry.setVisibility(retryAudio == null ? View.GONE : View.VISIBLE);
    retry.setOnClickListener(v -> retryRecording());
    overlay = new LinearLayout(this);
    overlay.setOrientation(LinearLayout.HORIZONTAL);
    overlay.setGravity(Gravity.CENTER_VERTICAL);
    overlay.addView(retry, new LinearLayout.LayoutParams(bubbleDimension(48), bubbleDimension(56)));
    overlay.addView(bubble, new LinearLayout.LayoutParams(bubbleDimension(56), bubbleDimension(56)));
    bubbleState = "idle";
    bubble.appearance(Prefs.bubbleSize(this) / 100f, 1 - Prefs.bubbleTransparency(this) / 100f);
    retry.appearance(Prefs.bubbleSize(this) / 100f, 1 - Prefs.bubbleTransparency(this) / 100f);
    refreshRecovery();
    params =
        new WindowManager.LayoutParams(
            bubbleDimension(56) + (retryAudio == null ? 0 : bubbleDimension(48)),
            bubbleDimension(56),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.LEFT;
    var bounds = wm.getCurrentWindowMetrics().getBounds();
    params.x = Prefs.get(this).getInt("bubbleX", bounds.width() - bubbleDimension(56) - dp(12))
        - (retryAudio == null ? 0 : bubbleDimension(48));
    params.y = Prefs.get(this).getInt("bubbleY", bounds.height() / 3);
    clamp();
    bubble.setOnClickListener(
        v -> {
          if (busy) {
            if (starting) cancel();
            else DictationService.stopCapture();
          } else if (captureTarget()) {
            long session = generation;
            RemoteTranscriber.prepareInBackground(this);
            handler.postDelayed(
                () -> {
                  if (isStarting(session)) {
                    cancel();
                    message("Microphone did not start. Tap to retry.");
                  }
                },
                4000);
            try {
              startCapture(session);
            } catch (RuntimeException error) {
              fail("Could not start the microphone. Check microphone access in Hex.");
            }
          }
        });
    bubble.setOnLongClickListener(
        v -> {
          if (busy) cancel();
          else retryTranscript();
          v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
          return true;
        });
    bubble.setOnTouchListener(
        new View.OnTouchListener() {
          float x, y;
          int initialX, initialY;
          boolean moved, held;

          public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
              case MotionEvent.ACTION_DOWN:
                clearHold();
                x = event.getRawX();
                y = event.getRawY();
                initialX = params.x;
                initialY = params.y;
                moved = held = false;
                boolean cancelOnHold = busy;
                hold =
                    () -> {
                      held = true;
                      // A result can arrive while the finger is down. A cancellation gesture
                      // must never turn into a second paste of that newly delivered result.
                      if (cancelOnHold) {
                        cancel();
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                      } else v.performLongClick();
                    };
                handler.postDelayed(hold, 600);
                return true;
              case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getRawX() - x, event.getRawY() - y) > dp(8)) {
                  moved = true;
                  clearHold();
                }
                if (moved && !held) {
                  params.x = initialX + (int) (event.getRawX() - x);
                  params.y = initialY + (int) (event.getRawY() - y);
                  clamp();
                  wm.updateViewLayout(overlay, params);
                }
                return true;
              case MotionEvent.ACTION_UP:
                clearHold();
                if (moved && !held)
                  Prefs.get(BubbleService.this)
                      .edit()
                      .putInt("bubbleX", params.x + (retry.getVisibility() == View.VISIBLE ? bubbleDimension(48) : 0))
                      .putInt("bubbleY", params.y)
                      .apply();
                else if (!held && !moved) v.performClick();
                return true;
              case MotionEvent.ACTION_CANCEL:
                clearHold();
                return true;
            }
            return false;
          }
        });
    wm.addView(overlay, params);
  }

  private void clearHold() {
    if (hold != null) handler.removeCallbacks(hold);
    hold = null;
  }

  // The enabled accessibility service has the system binding's microphone capability.
  // Starting directly keeps the editor resumed; even a transparent activity pauses it.
  void startCapture(long session) {
    if (!isStarting(session)) return;
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      fail("Open Hex and allow microphone access first.");
      return;
    }
    startForegroundService(new Intent(this, DictationService.class).putExtra("session", session));
  }

  void refreshRecovery() {
    if (bubble == null) return;
    bubble.recoverable(MainActivity.pendingTranscript != null);
    if (!busy)
      bubble.setContentDescription(
          "Hex: tap to dictate; drag to move; hold to paste last transcript"
              + (MainActivity.pendingTranscript != null ? "; transcript waiting" : ""));
  }

  private void clamp() {
    var b = wm.getCurrentWindowMetrics().getBounds();
    params.x = Math.max(0, Math.min(params.x, b.width() - params.width));
    params.y =
        Math.max(
            dp(28), Math.min(params.y, b.height() - Math.max(dp(100), params.height + dp(28))));
  }

  private int bubbleDimension(int base) {
    return dp(Math.max(48, Math.round(base * Prefs.bubbleSize(this) / 100f)));
  }

  private void refreshAppearance() {
    if (bubble == null) return;
    int width =
        bubbleDimension(
            bubbleState.equals("recording") ? 156 : bubbleState.equals("processing") ? 112 : 56);
    boolean canRetry = !busy && retryAudio != null;
    int totalWidth = width + (canRetry ? bubbleDimension(48) : 0);
    params.x += params.width - totalWidth;
    params.width = totalWidth;
    params.height = bubbleDimension(56);
    bubble.setLayoutParams(new LinearLayout.LayoutParams(width, params.height));
    retry.setLayoutParams(new LinearLayout.LayoutParams(bubbleDimension(48), params.height));
    retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
    bubble.appearance(Prefs.bubbleSize(this) / 100f, 1 - Prefs.bubbleTransparency(this) / 100f);
    retry.appearance(Prefs.bubbleSize(this) / 100f, 1 - Prefs.bubbleTransparency(this) / 100f);
    clamp();
    wm.updateViewLayout(overlay, params);
  }

  boolean captureTarget() {
    return captureTarget(true);
  }

  private boolean captureTarget(boolean newRecording) {
    AccessibilityNodeInfo node = focused();
    if (!eligible(node)) {
      if (node != null) node.recycle();
      return false;
    }
    if (newRecording) clearRecording();
    clearTarget();
    target = node;
    original = fieldText(node);
    start = selection(node, true);
    end = selection(node, false);
    replaceStart = start;
    replaceEnd = end;
    if (!validSelection(original, start, end)) inputSnapshot = captureInput(node);
    busy = true;
    starting = true;
    generation = SystemClock.elapsedRealtimeNanos();
    state("starting");
    return true;
  }

  void retainRecording(byte[] audio, String engine) {
    retryAudio = audio;
    retryEngine = engine;
    if (retryTarget != null) retryTarget.recycle();
    retryTarget = target == null ? null : AccessibilityNodeInfo.obtain(target);
    retryEdit = RetryEdit.captured(original, start, end);
  }

  void retryRecording() {
    if (busy || retryAudio == null || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
    if (!captureTarget(false)) return;
    // Retry may finish after an edit or a focus change. Only replace the original words
    // if the same field still contains the exact text and cursor we last confirmed.
    if (retryTarget != null && retryTarget.equals(target) && retryEdit != null
        && retryEdit.matches(original, start, end)) {
      replaceStart = retryEdit.from();
      replaceEnd = retryEdit.to();
    } else {
      clearTarget();
      retryEdit = null;
    }
    starting = false;
    state("…");
    DictationService.retryRecording(this, generation, retryAudio, retryEngine);
  }

  private void clearRecording() {
    retryAudio = null;
    retryEngine = null;
    retryEdit = null;
    if (retryTarget != null) retryTarget.recycle();
    retryTarget = null;
  }

  long sessionId() {
    return generation;
  }

  boolean isCurrentSession(long session) {
    return busy
        && generation == session
        && !getSystemService(KeyguardManager.class).isKeyguardLocked();
  }

  boolean isStarting(long session) {
    return isCurrentSession(session) && starting;
  }

  void state(String text) {
    if (text.equals("■")) starting = false;
    if (bubble != null) {
      String state =
          text.equals("■")
              ? "recording"
              : text.equals("…") ? "processing" : text.equals("starting") ? "starting" : "idle";
      bubbleState = state;
      refreshAppearance();
      bubble.state(state);
      bubble.setContentDescription(
          "Hex: "
              + (state.equals("recording")
                  ? "recording, tap to stop"
                  : state.equals("processing")
                      ? "transcribing, hold to cancel"
                      : state.equals("starting")
                          ? "starting microphone, tap to cancel"
                          : "tap to dictate; hold to paste last transcript"));
      refreshRecovery();
    }
  }

  void level(float level) {
    if (bubble != null) bubble.level(level);
  }

  void deliver(String transcript) {
    if (!busy) return;
    boolean inserted = false;
    AccessibilityNodeInfo current = focused();
    if (transcript != null
        && !transcript.isBlank()
        && eligible(current)
        && target != null
        && target.refresh()
        && current.equals(target)
        && Objects.equals(original, fieldText(current))
        && selection(current, true) == start
        && selection(current, false) == end) {
      inserted = (validSelection(original, replaceStart, replaceEnd)
          ? insertAtSelection(current, replaceStart, replaceEnd, transcript)
          : insertWithInput(current, inputSnapshot, transcript)) == Insertion.CONFIRMED;
    }
    if (retryAudio != null && transcript != null && !transcript.isBlank())
      retryEdit = inserted && retryEdit != null ? retryEdit.inserted(transcript) : null;
    if (current != null) current.recycle();
    if (transcript == null || transcript.isBlank()) message("No speech detected. Try again.");
    else {
      MainActivity.lastTranscript = transcript;
      MainActivity.pendingTranscript = inserted ? null : transcript;
      if (!inserted) message("Transcript saved. Focus a text field, then hold Hex to paste it.");
    }
    reset();
  }

  enum Insertion {
    CONFIRMED,
    ACCEPTED,
    REJECTED
  }

  private static boolean validSelection(String text, int from, int to) {
    return from >= 0 && to >= 0 && from <= text.length() && to <= text.length();
  }

  private Insertion insertWithInput(
      AccessibilityNodeInfo node, EditorInput.Snapshot snapshot, String transcript) {
    if (Build.VERSION.SDK_INT < 33 || !(getInputMethod() instanceof EditorInput input))
      return Insertion.REJECTED;
    return input.insert(node, snapshot, transcript);
  }

  private Insertion insertAtCursor(AccessibilityNodeInfo node, String transcript) {
    String before = fieldText(node);
    int from = selection(node, true), to = selection(node, false);
    if (!validSelection(before, from, to))
      return insertWithInput(node, captureInput(node), transcript);
    return insertAtSelection(node, from, to, transcript);
  }

  private Insertion insertAtSelection(AccessibilityNodeInfo node, int from, int to, String transcript) {
    String before = fieldText(node);
    TextInsertion.Result edit = TextInsertion.insert(before, from, to, transcript);
    Bundle args = new Bundle();
    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.text());
    if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return Insertion.REJECTED;
    // Some web editors acknowledge the action without changing their text. Never paste a
    // second time automatically after an accepted action: it may still be applied asynchronously.
    if (!node.refresh() || !Objects.equals(edit.text(), fieldText(node))) return Insertion.ACCEPTED;
    Bundle cursor = new Bundle();
    cursor.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, edit.cursor());
    cursor.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, edit.cursor());
    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursor);
    return Insertion.CONFIRMED;
  }

  void retryTranscript() {
    if (busy || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
    String text = MainActivity.recoveryTranscript();
    if (text == null || text.isBlank()) {
      message("No transcript to paste yet. Tap Hex to dictate.");
      return;
    }
    AccessibilityNodeInfo current = focused();
    try {
      if (!eligible(current)) {
        message("Focus a text field, then hold Hex to paste.");
        return;
      }
      Insertion result = insertAtCursor(current, text);
      if (result == Insertion.CONFIRMED) {
        MainActivity.pendingTranscript = null;
        message("Transcript pasted.");
      } else {
        // Clipboard use is limited to this explicit recovery gesture, never automatic dictation.
        MainActivity.copyTranscript(this, text);
        boolean pasted =
            result == Insertion.REJECTED
                && current.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        MainActivity.pendingTranscript = text;
        message(
            pasted
                ? "Paste requested. Your transcript is also copied if needed."
                : "Transcript copied. Use Paste in this field.");
      }
      refreshRecovery();
    } finally {
      if (current != null) current.recycle();
    }
  }

  void fail(String message) {
    message(message);
    reset();
  }

  void cancel() {
    DictationService.cancelCapture();
    clearRecording();
    reset();
  }

  private void reset() {
    busy = false;
    starting = false;
    clearTarget();
    state("✦");
    handler.postDelayed(refresh, 150);
  }

  private void clearTarget() {
    if (target != null) target.recycle();
    target = null;
    original = null;
    inputSnapshot = null;
  }

  private void message(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }

  private void hide() {
    clearHold();
    if (bubble != null) {
      wm.removeView(overlay);
      bubble = null;
      retry = null;
      overlay = null;
    }
  }

  boolean isBubbleVisible() {
    return bubble != null;
  }

  @Override
  public void onInterrupt() {
    cancel();
    hide();
  }

  @Override
  public void onDestroy() {
    Prefs.get(this).unregisterOnSharedPreferenceChangeListener(appearanceListener);
    unregisterReceiver(screenOff);
    handler.removeCallbacksAndMessages(null);
    DictationService.cancelCapture();
    clearRecording();
    clearTarget();
    hide();
    instance = null;
    super.onDestroy();
  }
}
