package dev.nosa.hex;

import android.os.Bundle;
import android.widget.*;
import java.text.DateFormat;
import java.util.Date;
import org.json.*;

public class CustomizationsActivity extends SettingsScreen {
  private TextView status, details;
  private Button sync, mode;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    label("N E W E R A   /   S Y N C", 12, 0xffa7bfff);
    label("Your Hex, on your phone.", 30, 0xfff6f4f0);
    label("Pull word corrections and text transformations from Hex on NewEra. The phone uses a saved copy when offline. Changes on this phone do not change NewEra.", 15, 0xffb3adb9);
    Switch enabled = new Switch(this);
    enabled.setText("Use NewEra customizations");
    enabled.setTextColor(0xfff6f4f0);
    enabled.setChecked(Customizations.enabled(this));
    column.addView(enabled);
    enabled.setOnCheckedChangeListener((v, value) -> {
      Prefs.get(this).edit().putBoolean("desktopSync", value).apply();
      if (value) synchronize();
      refresh();
    });
    status = label("", 15, 0xffa7bfff);
    sync = button("Sync now", this::synchronize);
    mode = button("Choose dictation mode", this::chooseMode);
    details = label("", 15, 0xffb3adb9);
    label("Corrections run first, then transformations in desktop order. Your custom scripts run on the phone, including offline. Desktop app and website matching do not map to Android; choose the mode to use here. OpenCode rewrites need a provider and are not included.", 14, 0xffb3adb9);
    refresh();
    if (Customizations.enabled(this)) synchronize();
  }

  private void synchronize() {
    sync.setEnabled(false);
    status.setText("Checking NewEra customizations…");
    new Thread(() -> {
      String error = null;
      try { RemoteTranscriber.syncCustomizations(this); }
      catch (Exception e) { error = "Could not sync. Check the server connection. Saved customizations are still available offline."; }
      final String message = error;
      runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) return;
        sync.setEnabled(true);
        refresh();
        if (message != null) status.setText(message);
      });
    }, "hex-manual-sync").start();
  }

  private void refresh() {
    if (!Customizations.hasSnapshot(this)) {
      status.setText("Waiting for NewEra. Configure the private server in Hex and run the NewEra sync setup once.");
      details.setText("No desktop customizations saved yet.");
      mode.setEnabled(false);
      return;
    }
    JSONObject snapshot = Customizations.snapshot(this);
    String updated = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(new Date(snapshot.optLong("updated_at")));
    String checked = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(new Date(Prefs.get(this).getLong("customizationsChecked", 0)));
    status.setText((Customizations.enabled(this) ? "Using NewEra" : "Customizations paused")
        + "\nDesktop snapshot: " + updated + "\nPhone last checked: " + checked);
    mode.setEnabled(true);
    StringBuilder text = new StringBuilder();
    JSONArray modes = snapshot.optJSONArray("modes");
    if (modes != null) for (int i = 0; i < modes.length(); i++) {
      JSONObject m = modes.optJSONObject(i);
      if (m == null) continue;
      text.append(m.optString("name")).append("\n");
      JSONArray rules = m.optJSONArray("replacements");
      if (rules != null) for (int j = 0; j < rules.length(); j++) {
        JSONObject r = rules.optJSONObject(j);
        text.append("  ").append(r.optString("matched_phrase")).append(" → ")
            .append(r.optString("output").isEmpty() ? "(remove)" : r.optString("output")).append("\n");
      }
      text.append("  Transformations: ").append(m.optJSONArray("transformations")).append("\n");
      JSONArray unsupported = m.optJSONArray("unsupported");
      if (unsupported != null && unsupported.length() > 0)
        text.append("  Unavailable on mobile: ").append(unsupported).append("\n");
      text.append("\n");
    }
    details.setText(text.toString());
    mode.setText("Mode: " + Prefs.get(this).getString("desktopMode", "Global"));
  }

  private void chooseMode() {
    JSONArray modes = Customizations.snapshot(this).optJSONArray("modes");
    if (modes == null || modes.length() == 0) return;
    String[] names = new String[modes.length()];
    int selected = 0;
    for (int i = 0; i < names.length; i++) {
      names[i] = modes.optJSONObject(i).optString("name");
      if (names[i].equals(Prefs.get(this).getString("desktopMode", ""))) selected = i;
    }
    new android.app.AlertDialog.Builder(this).setTitle("Dictation mode")
        .setSingleChoiceItems(names, selected, (dialog, which) -> {
          Prefs.get(this).edit().putString("desktopMode", which == 0 ? "" : names[which]).apply();
          dialog.dismiss();
          refresh();
        }).setNegativeButton("Cancel", null).show();
  }
}
