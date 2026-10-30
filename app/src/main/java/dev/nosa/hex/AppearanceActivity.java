package dev.nosa.hex;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Local, persistent appearance controls; the preview never opens a microphone. */
public class AppearanceActivity extends Activity {
  private LinearLayout column;
  private DotScopeView idle, recording;
  private SeekBar size, transparency;
  private TextView sizeLabel, transparencyLabel;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable animate =
      new Runnable() {
        public void run() {
          recording.level(.35f + .3f * (float) Math.sin(SystemClock.uptimeMillis() / 420.0));
          handler.postDelayed(this, 50);
        }
      };

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(0xff151515);
    scroll.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          var i = insets.getInsets(WindowInsets.Type.systemBars());
          v.setPadding(i.left, i.top, i.right, i.bottom);
          return insets;
        });
    column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(24), dp(12), dp(24), dp(32));
    scroll.addView(column);
    setContentView(scroll);
    button("‹  Hex", this::finish);
    label("B U B B L E   /   A P P E A R A N C E", 12, 0xffa7bfff);
    label("Make it yours.", 32, 0xfff6f4f0).setTypeface(null, Typeface.BOLD);
    label("A quiet blip. Your words, one tap away.", 16, 0xffaaa7a1);

    LinearLayout preview = new LinearLayout(this);
    preview.setOrientation(LinearLayout.VERTICAL);
    preview.setPadding(dp(12), dp(12), dp(12), dp(12));
    preview.setBackground(surface(0xff292929, 0xff414141));
    column.addView(preview, new LinearLayout.LayoutParams(-1, -2));
    TextView caption = new TextView(this);
    caption.setText("LIVE PREVIEW · NO MICROPHONE");
    caption.setTextColor(0xffb5b1a9);
    caption.setTextSize(11);
    preview.addView(caption);
    idle = new DotScopeView(this);
    idle.setContentDescription("Idle bubble preview");
    idle.setClickable(false);
    recording = new DotScopeView(this);
    recording.setContentDescription("Recording animation preview");
    recording.setClickable(false);
    recording.state("recording");
    preview.addView(idle, new LinearLayout.LayoutParams(-1, dp(84)));
    preview.addView(recording, new LinearLayout.LayoutParams(-1, dp(84)));

    sizeLabel = label("", 18, 0xfff6f4f0);
    size = slider("Bubble size", 75, 150, Prefs.bubbleSize(this), "bubbleSize");
    transparencyLabel = label("", 18, 0xfff6f4f0);
    transparency =
        slider("Bubble transparency", 0, 70, Prefs.bubbleTransparency(this), "bubbleTransparency");
    label(
        "Drag the bubble to move it. Size and transparency apply to the recording bar too. Changes"
            + " save automatically.",
        14,
        0xffaaa7a1);
    button(
        "Restore defaults",
        () -> {
          size.setProgress(100);
          transparency.setProgress(30);
        });
    updatePreview();
  }

  private SeekBar slider(String description, int min, int max, int value, String key) {
    SeekBar bar = new SeekBar(this);
    bar.setMin(min);
    bar.setMax(max);
    bar.setProgress(value);
    bar.setContentDescription(description);
    bar.setProgressTintList(ColorStateList.valueOf(0xffa7bfff));
    bar.setThumbTintList(ColorStateList.valueOf(0xffa7bfff));
    column.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
            Prefs.get(AppearanceActivity.this).edit().putInt(key, progress).apply();
            updatePreview();
          }

          public void onStartTrackingTouch(SeekBar view) {}

          public void onStopTrackingTouch(SeekBar view) {}
        });
    return bar;
  }

  private void updatePreview() {
    int percent = Prefs.bubbleSize(this), clear = Prefs.bubbleTransparency(this);
    idle.appearance(percent / 100f, 1 - clear / 100f);
    recording.appearance(percent / 100f, 1 - clear / 100f);
    sizeLabel.setText("Size  ·  " + percent + "%");
    transparencyLabel.setText("Transparency  ·  " + clear + "%");
  }

  @Override
  protected void onStart() {
    super.onStart();
    handler.post(animate);
  }

  @Override
  protected void onStop() {
    handler.removeCallbacks(animate);
    super.onStop();
  }

  private TextView label(String text, int size, int color) {
    TextView label = new TextView(this);
    label.setText(text);
    label.setTextSize(size);
    label.setTextColor(color);
    label.setPadding(0, dp(12), 0, dp(12));
    column.addView(label);
    return label;
  }

  private GradientDrawable surface(int fill, int border) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(fill);
    shape.setCornerRadius(dp(16));
    shape.setStroke(dp(1), border);
    return shape;
  }

  private void button(String text, Runnable action) {
    Button button = new Button(this);
    button.setAllCaps(false);
    button.setText(text);
    button.setTextColor(0xfff6f4f0);
    button.setBackground(surface(0xff303030, 0xff484848));
    column.addView(button, new LinearLayout.LayoutParams(-1, dp(48)));
    button.setOnClickListener(v -> action.run());
  }
}
