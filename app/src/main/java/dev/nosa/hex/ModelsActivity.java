package dev.nosa.hex;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.WindowInsets;
import android.widget.*;
import org.json.*;

/** A live catalog from the selected host; installed and active are distinct states. */
public class ModelsActivity extends Activity {
  private LinearLayout rows;
  private TextView status;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean visible, loading;
  private String rendered = "";
  private final Runnable refresh = () -> load();

  int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density);
  }

  TextView text(String value, int size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setPadding(0, dp(7), 0, dp(7));
    return t;
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(0xff151515);
    LinearLayout column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(24), dp(20), dp(24), dp(32));
    scroll.addView(column);
    setContentView(scroll);
    scroll.setOnApplyWindowInsetsListener(
        (v, i) -> {
          var bars = i.getInsets(WindowInsets.Type.systemBars());
          v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return i;
        });
    TextView back = text("‹   Hex", 16, 0xffa7bfff);
    back.setOnClickListener(v -> finish());
    column.addView(back);
    TextView title = text("Transcription models", 30, Color.WHITE);
    title.setTypeface(null, Typeface.BOLD);
    column.addView(title);
    column.addView(text("Choose the voice behind your words.", 16, 0xffaaaaaa));
    column.addView(text("ON YOUR SERVER", 12, 0xffaaaaaa));
    status = text("Connecting…", 14, 0xffa7bfff);
    column.addView(status);
    rows = new LinearLayout(this);
    rows.setOrientation(LinearLayout.VERTICAL);
    column.addView(rows);
    column.addView(
        text(
            "Downloads stay on your server. This first build transcribes after you stop recording;"
                + " live streaming is not yet enabled. Published desktop speed and accuracy figures"
                + " are not phone benchmarks.",
            13,
            0xff999999));
  }

  @Override
  public void onResume() {
    super.onResume();
    visible = true;
    load();
  }

  @Override
  public void onPause() {
    visible = false;
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  void load() {
    if (loading || !visible) return;
    loading = true;
    new Thread(
            () -> {
              try {
                JSONArray models = RemoteTranscriber.models(this);
                runOnUiThread(
                    () -> {
                      if (visible) render(models);
                    });
              } catch (Exception e) {
                runOnUiThread(
                    () ->
                        status.setText(
                            "Cannot reach your server. Save its URL and token in Hex settings, and"
                                + " check Tailscale."));
              } finally {
                runOnUiThread(
                    () -> {
                      loading = false;
                      if (visible) handler.postDelayed(refresh, 3000);
                    });
              }
            })
        .start();
  }

  void render(JSONArray models) {
    String key =
        models.toString()
            + Prefs.get(this).getString("model", "parakeet_unified_en")
            + Prefs.local(this);
    if (key.equals(rendered)) return;
    rendered = key;
    rows.removeAllViews();
    status.setText("Connected through your configured server");
    for (int i = 0; i < models.length(); i++) {
      JSONObject m = models.optJSONObject(i);
      if (m == null) continue;
      String id = m.optString("id");
      boolean installed = m.optBoolean("installed"),
          active =
              id.equals(Prefs.get(this).getString("model", "parakeet_unified_en"))
                  && !Prefs.local(this);
      LinearLayout card = new LinearLayout(this);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setPadding(dp(18), dp(12), dp(18), dp(14));
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(active ? 0xff2c2c2c : 0xff171717);
      bg.setCornerRadius(dp(14));
      bg.setStroke(dp(1), active ? 0xffa7bfff : 0xff303030);
      card.setBackground(bg);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.bottomMargin = dp(12);
      rows.addView(card, p);
      TextView name = text(m.optString("name"), 20, Color.WHITE);
      name.setTypeface(null, Typeface.BOLD);
      card.addView(name);
      card.addView(text(m.optString("detail"), 14, 0xffaaaaaa));
      double mb = m.optLong("bytes") / 1e6;
      String size =
          mb >= 1000
              ? String.format(java.util.Locale.US, "%.1f GB", mb / 1000)
              : String.format(java.util.Locale.US, "%.0f MB", mb);
      String progress = m.isNull("download") ? "" : m.optString("download");
      card.addView(
          text(
              size
                  + "  ·  "
                  + (installed ? "Installed" : progress.isBlank() ? "Not downloaded" : progress),
              14,
              0xffbbbbbb));
      Button action = new Button(this);
      action.setAllCaps(false);
      action.setText(active ? "●  Active" : installed ? "Use" : "Download");
      action.setEnabled(!active && !progress.startsWith("Downloading"));
      card.addView(action);
      action.setOnClickListener(
          v -> {
            if (installed) {
              Prefs.get(this).edit().putString("model", id).putBoolean("local", false).apply();
              RemoteTranscriber.prepareInBackground(this);
              render(models);
            } else {
              action.setEnabled(false);
              action.setText("Starting download…");
              new Thread(
                      () -> {
                        try {
                          RemoteTranscriber.download(this, id);
                        } catch (Exception e) {
                          runOnUiThread(
                              () ->
                                  Toast.makeText(
                                          this,
                                          "Download could not start. Check the connection.",
                                          Toast.LENGTH_LONG)
                                      .show());
                        }
                        runOnUiThread(
                            () -> {
                              handler.removeCallbacks(refresh);
                              load();
                            });
                      })
                  .start();
            }
          });
    }
  }
}
