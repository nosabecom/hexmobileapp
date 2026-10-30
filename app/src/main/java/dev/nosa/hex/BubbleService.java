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
  private DotScopeView bubble;
  private WindowManager.LayoutParams params;
  private AccessibilityNodeInfo target;
  private String original;
  private int start, end;
  private boolean busy;
  private long generation;
  private boolean starting;
  private String bubbleState = "idle";
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
    registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    wm = getSystemService(WindowManager.class);
    Prefs.get(this).registerOnSharedPreferenceChangeListener(appearanceListener);
    updateFocus();
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
    bubbleState = "idle";
    bubble.appearance(Prefs.bubbleSize(this) / 100f, 1 - Prefs.bubbleTransparency(this) / 100f);
    bubble.setContentDescription("Hex: tap to dictate; drag to move; hold to cancel");
    params =
        new WindowManager.LayoutParams(
            bubbleDimension(56),
            bubbleDimension(56),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.LEFT;
    var bounds = wm.getCurrentWindowMetrics().getBounds();
    params.x = Prefs.get(this).getInt("bubbleX", bounds.width() - params.width - dp(12));
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
              startActivity(CaptureActivity.intent(this, session));
            } catch (RuntimeException error) {
              fail("Could not open the microphone. Tap to retry.");
            }
          }
        });
    bubble.setOnTouchListener(
        new View.OnTouchListener() {
          float x, y;
          int initialX, initialY;
          long down;
          boolean moved;

          public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
              case MotionEvent.ACTION_DOWN:
                x = event.getRawX();
                y = event.getRawY();
                initialX = params.x;
                initialY = params.y;
                down = SystemClock.elapsedRealtime();
                moved = false;
                return true;
              case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getRawX() - x, event.getRawY() - y) > dp(8)) moved = true;
                if (moved) {
                  params.x = initialX + (int) (event.getRawX() - x);
                  params.y = initialY + (int) (event.getRawY() - y);
                  clamp();
                  wm.updateViewLayout(bubble, params);
                }
                return true;
              case MotionEvent.ACTION_UP:
                if (moved)
                  Prefs.get(BubbleService.this)
                      .edit()
                      .putInt("bubbleX", params.x)
                      .putInt("bubbleY", params.y)
                      .apply();
                else if (SystemClock.elapsedRealtime() - down > 600) cancel();
                else v.performClick();
                return true;
              case MotionEvent.ACTION_CANCEL:
                return true;
            }
            return false;
          }
        });
    wm.addView(bubble, params);
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
    params.x += params.width - width;
    params.width = width;
    params.height = bubbleDimension(56);
    bubble.appearance(Prefs.bubbleSize(this) / 100f, 1 - Prefs.bubbleTransparency(this) / 100f);
    clamp();
    wm.updateViewLayout(bubble, params);
  }

  boolean captureTarget() {
    AccessibilityNodeInfo node = focused();
    if (!eligible(node)) {
      if (node != null) node.recycle();
      return false;
    }
    clearTarget();
    target = node;
    original = fieldText(node);
    start = selection(node, true);
    end = selection(node, false);
    if (start < 0 || end < 0) {
      clearTarget();
      message("This field does not expose its cursor position.");
      return false;
    }
    busy = true;
    starting = true;
    generation = SystemClock.elapsedRealtimeNanos();
    state("starting");
    return true;
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
                          : "tap to dictate"));
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
      TextInsertion.Result edit = TextInsertion.insert(original, start, end, transcript);
      Bundle args = new Bundle();
      args.putCharSequence(
          AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.text());
      inserted = current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
      if (inserted) {
        Bundle selection = new Bundle();
        selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, edit.cursor());
        selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, edit.cursor());
        current.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
      }
    }
    if (current != null) current.recycle();
    if (transcript == null || transcript.isBlank()) message("No speech detected. Try again.");
    else if (!inserted) {
      MainActivity.pendingTranscript = transcript;
      message("Field changed or insertion unsupported. Recover your transcript in Hex.");
    }
    reset();
  }

  void fail(String message) {
    message(message);
    reset();
  }

  void cancel() {
    CaptureActivity.dismiss();
    DictationService.cancelCapture();
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
  }

  private void message(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }

  private void hide() {
    if (bubble != null) {
      wm.removeView(bubble);
      bubble = null;
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
    clearTarget();
    hide();
    instance = null;
    super.onDestroy();
  }
}
