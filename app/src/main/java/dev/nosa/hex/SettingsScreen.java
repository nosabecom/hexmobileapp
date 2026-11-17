package dev.nosa.hex;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.*;

/** Shared visual shell for the history and desktop-customization screens. */
abstract class SettingsScreen extends Activity {
  LinearLayout column;

  int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(0xff151515);
    scroll.setFillViewport(true);
    scroll.setOnApplyWindowInsetsListener((v, insets) -> {
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
  }

  TextView label(String text, int size, int color) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setPadding(0, dp(12), 0, dp(12));
    column.addView(view);
    return view;
  }

  Button button(String text, Runnable action) {
    Button button = new Button(this);
    button.setText(text);
    button.setAllCaps(false);
    button.setTextColor(0xfff6f4f0);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xff303030);
    background.setStroke(dp(1), 0xff484848);
    background.setCornerRadius(dp(16));
    button.setBackground(background);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
    params.topMargin = dp(8);
    column.addView(button, params);
    button.setOnClickListener(v -> action.run());
    return button;
  }
}
