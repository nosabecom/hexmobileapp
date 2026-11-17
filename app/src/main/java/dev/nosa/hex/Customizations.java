package dev.nosa.hex;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.json.*;

/** NewEra is authoritative; phone settings never overwrite desktop settings. */
final class Customizations {
  record Result(String text, String mode, boolean failed) {}
  private static boolean syncing;
  private static long lastAttempt;
  private static String lastKey = "";

  static JSONObject snapshot(Context c) {
    try { return new JSONObject(Prefs.get(c).getString("desktopCustomizations", "{}")); }
    catch (JSONException e) { return new JSONObject(); }
  }

  static void accept(Context c, JSONObject snapshot, String server) throws JSONException {
    if (!server.equals(Prefs.endpoint(c))) return;
    if (!snapshot.getBoolean("configured")) return; // Keep the last usable offline snapshot.
    if (!"NewEra".equals(snapshot.getString("source"))) throw new JSONException("Unexpected settings source");
    JSONArray modes = snapshot.getJSONArray("modes");
    if (modes.length() == 0 || modes.length() > 129) throw new JSONException("Invalid modes");
    for (int i = 0; i < modes.length(); i++) {
      JSONObject mode = modes.getJSONObject(i);
      mode.getString("name");
      mode.getJSONArray("transformations");
      mode.getJSONArray("unsupported");
      JSONArray rules = mode.getJSONArray("replacements");
      for (int j = 0; j < rules.length(); j++) {
        rules.getJSONObject(j).getString("matched_phrase");
        rules.getJSONObject(j).getString("output");
      }
    }
    // One transaction keeps the cached rules and their server identity together.
    Prefs.get(c).edit().putString("desktopCustomizations", snapshot.toString())
        .putString("customizationServer", server).putLong("customizationsChecked", System.currentTimeMillis()).apply();
  }

  static boolean hasSnapshot(Context c) {
    return Prefs.endpoint(c).equals(Prefs.get(c).getString("customizationServer", ""))
        && snapshot(c).optBoolean("configured");
  }

  static boolean enabled(Context c) { return Prefs.get(c).getBoolean("desktopSync", true); }

  static synchronized void syncInBackground(Context context) {
    if (!enabled(context) || Prefs.endpoint(context).isBlank()) return;
    String key = Prefs.endpoint(context) + "/" + Prefs.token(context);
    long now = android.os.SystemClock.elapsedRealtime();
    if (syncing || (lastKey.equals(key) && now - lastAttempt < 30000)) return;
    syncing = true;
    lastKey = key;
    lastAttempt = now;
    Context app = context.getApplicationContext();
    new Thread(() -> {
      try { RemoteTranscriber.syncCustomizations(app); }
      catch (Exception ignored) {
        // Offline dictation keeps the last successful snapshot. The settings screen shows its age.
      } finally {
        synchronized (Customizations.class) { syncing = false; }
      }
    }, "hex-settings-sync").start();
  }

  static Result process(Context c, String text) {
    if (!enabled(c) || !hasSnapshot(c)) return new Result(text, "Unmodified", false);
    try {
      JSONObject saved = snapshot(c);
      JSONArray modes = saved.getJSONArray("modes");
      String selected = Prefs.get(c).getString("desktopMode", "");
      JSONObject mode = modes.getJSONObject(0);
      for (int i = 1; i < modes.length(); i++) {
        if (modes.getJSONObject(i).getString("name").equals(selected)) {
          mode = modes.getJSONObject(i);
          break;
        }
      }
      List<TextTransforms.Rule> rules = new ArrayList<>();
      JSONArray replacements = mode.getJSONArray("replacements");
      for (int i = 0; i < replacements.length(); i++) {
        JSONObject rule = replacements.getJSONObject(i);
        rules.add(new TextTransforms.Rule(rule.getString("matched_phrase"), rule.getString("output")));
      }
      List<String> transformations = new ArrayList<>();
      JSONArray chain = mode.getJSONArray("transformations");
      for (int i = 0; i < chain.length(); i++) transformations.add(chain.getString(i));
      String corrected = TextTransforms.replace(text, rules);
      String modeName = mode.getString("name");
      boolean custom = transformations.stream().anyMatch(id -> !java.util.Set.of(
          "lowercase", "spongebob-case", "no-trailing-punctuation").contains(id));
      if (custom) {
        try {
          return new Result(ScriptTransforms.apply(c, saved.optString("script"), transformations, corrected), modeName, false);
        } catch (Exception e) {
          return new Result(corrected, modeName, true); // Whole chain fails atomically, as on desktop.
        }
      }
      return new Result(TextTransforms.apply(corrected, List.of(), transformations), modeName, false);
    } catch (JSONException e) { return new Result(text, "Unmodified", true); }
  }
}
