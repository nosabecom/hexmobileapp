package dev.nosa.hex;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.WindowManager;

/** Keeps Hex screens awake only while they are visible and the setting is enabled. */
abstract class HexActivity extends Activity {
  private final SharedPreferences.OnSharedPreferenceChangeListener screenListener =
      (prefs, key) -> {
        if ("keepScreenOn".equals(key)) runOnUiThread(this::refreshScreenAwake);
      };

  @Override
  protected void onStart() {
    super.onStart();
    Prefs.get(this).registerOnSharedPreferenceChangeListener(screenListener);
    refreshScreenAwake();
  }

  private void refreshScreenAwake() {
    if (Prefs.keepScreenOn(this))
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  protected void onStop() {
    Prefs.get(this).unregisterOnSharedPreferenceChangeListener(screenListener);
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onStop();
  }
}
