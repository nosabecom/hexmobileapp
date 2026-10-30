package dev.nosa.hex;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * A visible activity starts the microphone service, satisfying Android's while-in-use rule. It has
 * no task affinity: bringing the settings task forward would steal the dictation target.
 */
public class CaptureActivity extends Activity {
  static CaptureActivity active;
  private boolean started;

  @Override
  protected void onResume() {
    super.onResume();
    active = this;
    if (started) return;
    started = true;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      if (BubbleService.instance != null)
        BubbleService.instance.fail("Open Hex and allow microphone access first.");
      closeCapture();
      return;
    }
    getWindow()
        .getDecorView()
        .post(
            () -> {
              if (isFinishing() || isDestroyed()) return;
              try {
                startForegroundService(new Intent(this, DictationService.class));
              } catch (RuntimeException e) {
                if (BubbleService.instance != null)
                  BubbleService.instance.fail(
                      "Android could not start the microphone. Open Hex and try again.");
                closeCapture();
              }
            });
  }

  static void dismiss() {
    if (active != null) active.closeCapture();
  }

  private void closeCapture() {
    finishAndRemoveTask();
    overridePendingTransition(0, 0);
  }

  @Override
  protected void onDestroy() {
    if (active == this) active = null;
    super.onDestroy();
  }
}
