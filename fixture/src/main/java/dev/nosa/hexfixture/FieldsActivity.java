package dev.nosa.hexfixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;

public class FieldsActivity extends Activity {
  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(32, 100, 32, 20);
    TextView title = new TextView(this);
    title.setText("Hex · cross-app test fields");
    title.setTextSize(24);
    layout.addView(title);
    EditText first = new EditText(this);
    first.setText("Hello world");
    first.setContentDescription("First field");
    layout.addView(first);
    EditText second = new EditText(this);
    second.setHint("Second field");
    second.setContentDescription("Second field");
    layout.addView(second);
    EditText password = new EditText(this);
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    password.setHint("Password — bubble must hide");
    password.setContentDescription("Password field");
    layout.addView(password);
    Button done = new Button(this);
    done.setText("Clear focus");
    done.setFocusableInTouchMode(true);
    done.setOnClickListener(
        v -> {
          done.requestFocus();
          getSystemService(android.view.inputmethod.InputMethodManager.class)
              .hideSoftInputFromWindow(done.getWindowToken(), 0);
        });
    layout.addView(done);
    setContentView(layout);
    first.requestFocus();
    first.setSelection(6, 11);
  }
}
