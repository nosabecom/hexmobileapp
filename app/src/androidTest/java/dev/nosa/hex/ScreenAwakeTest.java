package dev.nosa.hex;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Switch;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public class ScreenAwakeTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();

  private <T extends Activity> T open(Class<T> type) {
    return type.cast(instrumentation.startActivitySync(
        new Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
  }

  private Switch screenSwitch(View view) {
    if (view instanceof Switch toggle
        && "Keep screen on while using Hex".contentEquals(toggle.getText())) return toggle;
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        Switch result = screenSwitch(group.getChildAt(i));
        if (result != null) return result;
      }
    }
    return null;
  }

  private boolean awake(Activity activity) {
    return (activity.getWindow().getAttributes().flags
        & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
  }

  @Test
  public void togglePersistsAcrossScreensAndReopening() {
    boolean hadSetting = Prefs.get(context).contains("keepScreenOn");
    boolean oldSetting = Prefs.keepScreenOn(context);
    Activity[] current = {null};
    try {
      Prefs.get(context).edit().remove("keepScreenOn").commit();
      MainActivity main = open(MainActivity.class);
      current[0] = main;
      instrumentation.runOnMainSync(() -> {
        Switch toggle = screenSwitch(main.getWindow().getDecorView());
        assertNotNull(toggle);
        assertTrue(toggle.isChecked());
        assertTrue(awake(main));
        toggle.performClick();
        assertFalse(Prefs.keepScreenOn(context));
        assertFalse(awake(main));
        main.finish();
      });
      MainActivity reopened = open(MainActivity.class);
      current[0] = reopened;
      instrumentation.runOnMainSync(() -> {
        Switch toggle = screenSwitch(reopened.getWindow().getDecorView());
        assertFalse(toggle.isChecked());
        assertFalse(awake(reopened));
        toggle.performClick();
        assertTrue(awake(reopened));
        reopened.finish();
      });
      for (Class<? extends Activity> type : java.util.List.of(
          AppearanceActivity.class, HistoryActivity.class, CustomizationsActivity.class,
          ModelsActivity.class)) {
        Activity activity = open(type);
        current[0] = activity;
        instrumentation.runOnMainSync(() -> {
          assertTrue(type.getSimpleName(), awake(activity));
          activity.finish();
        });
      }
    } finally {
      if (current[0] != null) instrumentation.runOnMainSync(() -> current[0].finish());
      var edit = Prefs.get(context).edit();
      if (hadSetting) edit.putBoolean("keepScreenOn", oldSetting);
      else edit.remove("keepScreenOn");
      edit.commit();
    }
  }
}
