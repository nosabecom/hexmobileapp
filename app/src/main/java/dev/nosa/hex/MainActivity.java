package dev.nosa.hex;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.net.URI;

public class MainActivity extends Activity {
  private LinearLayout column;
  private TextView status;
  private EditText endpoint, token;
  private Switch local;
  static volatile String pendingTranscript, lastTranscript;

  static String recoveryTranscript() {
    return pendingTranscript != null ? pendingTranscript : lastTranscript;
  }

  static void copyTranscript(Context context, String text) {
    ClipData clip = ClipData.newPlainText("Hex transcript", text);
    android.os.PersistableBundle extras = new android.os.PersistableBundle();
    extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
    clip.getDescription().setExtras(extras);
    context.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
  }

  private void clearRecovery() {
    pendingTranscript = lastTranscript = null;
    if (BubbleService.instance != null) BubbleService.instance.refreshRecovery();
  }

  int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    ScrollView scroll = new ScrollView(this);
    column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(24), dp(32), dp(24), dp(32));
    scroll.addView(column);
    scroll.setBackgroundColor(Color.rgb(21, 21, 21));
    scroll.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          var i = insets.getInsets(WindowInsets.Type.systemBars());
          v.setPadding(i.left, i.top, i.right, i.bottom);
          return insets;
        });
    setContentView(scroll);
    label("H E X   /   M O B I L E", 13, 0xffa7bfff);
    label("Speak.\nIt appears.", 36, Color.WHITE).setTypeface(null, Typeface.BOLD);
    label(
        "Tap a text field. Tap Hex. Speak.\nYour words appear where you were typing.",
        16,
        0xffb3adb9);
    status = label("", 14, 0xffa7bfff);
    button(
        "1   Allow microphone",
        () ->
            requestPermissions(
                Build.VERSION.SDK_INT >= 33
                    ? new String[] {
                      Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS
                    }
                    : new String[] {Manifest.permission.RECORD_AUDIO},
                1));
    button(
        "2   Enable the floating bubble",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Let Hex insert your words")
                .setMessage(
                    getString(R.string.accessibility_description)
                        + "\n\n"
                        + "On the next screen, select Hex Mobile and enable it. You can turn it off"
                        + " there at any time.")
                .setPositiveButton(
                    "Open accessibility settings",
                    (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Cancel", null)
                .show());
    button("Transcription models", () -> startActivity(new Intent(this, ModelsActivity.class)));
    button("Bubble appearance", () -> startActivity(new Intent(this, AppearanceActivity.class)));
    label("TRANSCRIPTION", 12, 0xffb3adb9);
    local = new Switch(this);
    local.setText("On this phone · offline");
    local.setTextColor(Color.WHITE);
    local.setChecked(Prefs.local(this));
    column.addView(local);
    boolean available = SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
    label(
        available
            ? "Android reports an on-device recognizer. A downloaded language may still be needed."
            : "No on-device recognizer detected here. Use Godseye below. There is no automatic"
                + " cloud fallback.",
        14,
        0xffb3adb9);
    local.setOnCheckedChangeListener(
        (b, v) -> Prefs.get(this).edit().putBoolean("local", v).apply());
    endpoint =
        field(
            "Godseye URL · https://…",
            Prefs.endpoint(this),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    token =
        field(
            "Server access token",
            Prefs.token(this),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    button(
        "Save server settings",
        () -> {
          try {
            String url = endpoint.getText().toString().trim().replaceAll("/+$", "");
            URI uri = URI.create(url);
            boolean loopback =
                "127.0.0.1".equals(uri.getHost())
                    || "10.0.2.2".equals(uri.getHost())
                    || "localhost".equals(uri.getHost());
            if ((!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) throw new IllegalArgumentException();
            Prefs.get(this)
                .edit()
                .putString("endpoint", url)
                .putString("token", token.getText().toString().trim())
                .apply();
            toast("Server saved");
          } catch (Exception e) {
            toast("Enter an HTTPS URL, or a loopback URL for emulator testing.");
          }
        });
    label(
        "Godseye mode sends only your recording to your server. Keep Godseye awake and connected."
            + " No subscription or paid API is required.",
        14,
        0xffb3adb9);
    button(
        "Test server connection",
        () ->
            new Thread(
                    () -> {
                      try {
                        String result = RemoteTranscriber.health(this);
                        runOnUiThread(() -> toast(result));
                      } catch (Exception e) {
                        runOnUiThread(
                            () -> toast("Cannot reach server. Check URL, token and connection."));
                      }
                    })
                .start());
    button(
        "Recover last transcript",
        () -> {
          String text = recoveryTranscript();
          if (text == null) {
            toast("No transcript to recover");
            return;
          }
          new AlertDialog.Builder(this)
              .setTitle("Your transcript")
              .setMessage(text)
              .setPositiveButton(
                  "Copy",
                  (d, w) -> {
                    copyTranscript(this, text);
                    clearRecovery();
                  })
              .setNegativeButton("Discard", (d, w) -> clearRecovery())
              .show();
        });
    label(
        "EARLY ANDROID BUILD\n"
            + "An independent companion inspired by Hex. Password fields are excluded. Some apps do"
            + " not expose editable fields to Android accessibility.",
        12,
        0xff89818f);
  }

  @Override
  public void onResume() {
    super.onResume();
    if (local != null) local.setChecked(Prefs.local(this));
    if (status != null)
      status.setText(
          (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                      == PackageManager.PERMISSION_GRANTED
                  ? "Mic ready"
                  : "Mic permission needed")
              + "  ·  "
              + (BubbleService.instance != null ? "Bubble enabled" : "Bubble disabled"));
  }

  TextView label(String text, int size, int color) {
    TextView v = new TextView(this);
    v.setText(text);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setPadding(0, dp(12), 0, dp(12));
    column.addView(v);
    return v;
  }

  EditText field(String hint, String text, int type) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setText(text);
    e.setTextSize(16);
    e.setTextColor(Color.WHITE);
    e.setHintTextColor(0xff89818f);
    e.setInputType(type);
    e.setSingleLine(true);
    if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) {
      e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
    }
    column.addView(e);
    return e;
  }

  void button(String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextColor(Color.WHITE);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xff303030);
    bg.setStroke(dp(1), 0xff484848);
    bg.setCornerRadius(dp(16));
    b.setBackground(bg);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
    p.topMargin = dp(12);
    p.bottomMargin = dp(4);
    column.addView(b, p);
    b.setOnClickListener(v -> action.run());
  }

  void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }
}
