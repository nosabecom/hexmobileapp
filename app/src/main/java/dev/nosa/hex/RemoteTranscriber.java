package dev.nosa.hex;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class RemoteTranscriber {
  private static boolean preparing;
  private static long lastPrepare;
  private static String preparedKey = "";

  static synchronized void prepareInBackground(Context context) {
    if (Prefs.local(context) || Prefs.endpoint(context).isBlank()) return;
    Context app = context.getApplicationContext();
    String model = Prefs.get(app).getString("model", "parakeet_unified_en");
    String key = Prefs.endpoint(app) + "/" + model;
    long now = android.os.SystemClock.elapsedRealtime();
    if (preparing || (key.equals(preparedKey) && now - lastPrepare < 15000)) return;
    preparing = true;
    preparedKey = key;
    lastPrepare = now;
    new Thread(
            () -> {
              boolean success = false;
              try {
                HttpURLConnection conn = connect(app, "/prepare");
                try {
                  conn.setRequestMethod("POST");
                  conn.setRequestProperty("X-Hex-Model", model);
                  read(conn);
                  success = true;
                } finally {
                  conn.disconnect();
                }
              } catch (Exception ignored) {
                // A preflight must never delay recording or present an error over another app.
              } finally {
                synchronized (RemoteTranscriber.class) {
                  preparing = false;
                  if (!success) lastPrepare = 0;
                }
              }
            },
            "hex-prepare")
        .start();
  }

  private static HttpURLConnection connect(Context c, String path) throws Exception {
    if (Prefs.endpoint(c).isBlank())
      throw new IOException("Configure your Godseye URL in Hex first.");
    URL url = new URI(Prefs.endpoint(c) + path).toURL();
    if (!url.getProtocol().equals("https")
        && !(url.getProtocol().equals("http")
            && java.util.Set.of("127.0.0.1", "localhost", "10.0.2.2").contains(url.getHost())))
      throw new IOException("Use HTTPS for your server.");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setInstanceFollowRedirects(false);
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(90000);
    conn.setRequestProperty("Authorization", "Bearer " + Prefs.token(c));
    return conn;
  }

  static org.json.JSONArray models(Context c) throws Exception {
    HttpURLConnection conn = connect(c, "/models");
    try {
      return new JSONObject(read(conn)).getJSONArray("models");
    } finally {
      conn.disconnect();
    }
  }

  static void download(Context c, String id) throws Exception {
    if (!id.matches("[a-z0-9_]+")) throw new IOException("Invalid model");
    HttpURLConnection conn = connect(c, "/models/" + id + "/download");
    try {
      conn.setRequestMethod("POST");
      read(conn);
    } finally {
      conn.disconnect();
    }
  }

  static String health(Context c) throws Exception {
    HttpURLConnection conn = connect(c, "/health");
    try {
      return "Connected · " + new JSONObject(read(conn)).getString("model");
    } finally {
      conn.disconnect();
    }
  }

  static String transcribe(Context c, byte[] wav) throws Exception {
    HttpURLConnection conn = connect(c, "/transcribe");
    try {
      conn.setRequestMethod("POST");
      conn.setRequestProperty(
          "X-Hex-Model", Prefs.get(c).getString("model", "parakeet_unified_en"));
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "audio/wav");
      conn.setFixedLengthStreamingMode(wav.length);
      try (OutputStream out = conn.getOutputStream()) {
        out.write(wav);
      }
      return new JSONObject(read(conn)).getString("text");
    } finally {
      conn.disconnect();
    }
  }

  private static String read(HttpURLConnection conn) throws Exception {
    if (conn.getResponseCode() != 200)
      throw new IOException(
          "Server returned " + conn.getResponseCode() + ". Check the server and access token.");
    try (InputStream in = conn.getInputStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        if (out.size() + n > 65536) throw new IOException("Server response too large.");
        out.write(buf, 0, n);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
