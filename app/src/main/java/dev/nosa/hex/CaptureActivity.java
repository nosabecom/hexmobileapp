package dev.nosa.hex;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.*;

/** One-pixel, non-focusable activity satisfies Android's visible microphone-start rule. */
public class CaptureActivity extends Activity {
  static CaptureActivity active;
  private boolean dispatched;
  private long session;

  static Intent intent(Context context, long session) {
    return new Intent(context, CaptureActivity.class)
        .putExtra("session", session)
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    session = getIntent().getLongExtra("session", -1);
    Window window = getWindow();
    window.addFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    window.setGravity(Gravity.TOP | Gravity.START);
    window.setLayout(1, 1);
    window.setWindowAnimations(0);
  }

  @Override
  protected void onResume() {
    super.onResume();
    active = this;
    BubbleService owner = BubbleService.instance;
    if (owner == null || !owner.isStarting(session)) {
      closeCapture();
      return;
    }
    if (dispatched) return;
    dispatched = true;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      owner.fail("Open Hex and allow microphone access first.");
      closeCapture();
      return;
    }
    getWindow()
        .getDecorView()
        .post(
            () -> {
              if (isFinishing() || isDestroyed() || !owner.isStarting(session)) {
                closeCapture();
                return;
              }
              try {
                startForegroundService(
                    new Intent(this, DictationService.class).putExtra("session", session));
              } catch (RuntimeException error) {
                if (owner.isCurrentSession(session))
                  owner.fail("Could not start the microphone. Tap to retry.");
                closeCapture();
              }
            });
  }

  static void dismiss() {
    if (active != null) active.closeCapture();
  }

  static void dismiss(long session) {
    if (active != null && active.session == session) active.closeCapture();
  }

  private void closeCapture() {
    if (isFinishing()) return;
    finishAndRemoveTask();
    overridePendingTransition(0, 0);
  }

  @Override
  protected void onStop() {
    super.onStop();
    BubbleService owner = BubbleService.instance;
    if (owner != null && owner.isStarting(session)) owner.cancel();
  }

  @Override
  protected void onDestroy() {
    if (active == this) active = null;
    super.onDestroy();
  }
}
