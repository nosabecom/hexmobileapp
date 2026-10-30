package dev.nosa.hex;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
  static SharedPreferences get(Context c) {
    return c.getSharedPreferences("hex", Context.MODE_PRIVATE);
  }

  static boolean local(Context c) {
    return get(c).getBoolean("local", true);
  }

  static String endpoint(Context c) {
    return get(c).getString("endpoint", "");
  }

  static String token(Context c) {
    return get(c).getString("token", "");
  }

  static int bubbleSize(Context c) {
    return Math.max(75, Math.min(150, get(c).getInt("bubbleSize", 100)));
  }

  static int bubbleTransparency(Context c) {
    return Math.max(0, Math.min(70, get(c).getInt("bubbleTransparency", 30)));
  }
}
