package dev.nosa.hex;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import android.speech.*;
import java.io.*;
import java.nio.*;
import java.util.*;

public class DictationService extends Service implements RecognitionListener {
  private static DictationService instance;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private volatile boolean recording, cancelled;
  private SpeechRecognizer recognizer;
  private AudioRecord recorder;
  private boolean finishing;
  private long session;
  private BubbleService owner;
  private final Runnable timeout = () -> stopRecording();

  static void stopCapture() {
    if (instance != null) instance.stopRecording();
  }

  static void cancelCapture() {
    if (instance != null) instance.cancel();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null && "cancel".equals(intent.getAction())) {
      if (BubbleService.instance != null) BubbleService.instance.cancel();
      else cancel();
      return START_NOT_STICKY;
    }
    long requested = intent == null ? -1 : intent.getLongExtra("session", -1);
    BubbleService requestedOwner = BubbleService.instance;
    if (requestedOwner == null || !requestedOwner.isStarting(requested)) {
      if (instance == null) stopSelf(id);
      return START_NOT_STICKY;
    }
    if (instance != null) {
      if (session != requested) requestedOwner.fail("Finishing the previous recording. Tap again.");
      return START_NOT_STICKY;
    }
    session = requested;
    owner = requestedOwner;
    instance = this;
    try {
      NotificationManager nm = getSystemService(NotificationManager.class);
      nm.createNotificationChannel(
          new NotificationChannel(
              "dictation", "Dictation microphone", NotificationManager.IMPORTANCE_LOW));
      PendingIntent cancel =
          PendingIntent.getService(
              this,
              1,
              new Intent(this, DictationService.class).setAction("cancel"),
              PendingIntent.FLAG_IMMUTABLE);
      Notification n =
          new Notification.Builder(this, "dictation")
              .setSmallIcon(R.drawable.ic_hex)
              .setContentTitle("Hex is listening")
              .setContentText("Tap the bubble to finish, or cancel here.")
              .setOngoing(true)
              .addAction(new Notification.Action.Builder(null, "Cancel", cancel).build())
              .build();
      startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      if (Prefs.local(this)) startLocal();
      else startRemote();
    } catch (Exception e) {
      fail("Could not start recording. Check microphone access and the selected recognition mode.");
    }
    return START_NOT_STICKY;
  }

  private void startLocal() {
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
      fail("No on-device recognizer. Select Godseye in Hex settings.");
      return;
    }
    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
    recognizer.setRecognitionListener(this);
    Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
    i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
    recording = true;
    recognizer.startListening(i);
    state("■");
    handler.postDelayed(timeout, 120000);
  }

  private void startRemote() throws IOException {
    if (Prefs.endpoint(this).isBlank()) {
      fail("Configure your Godseye server in Hex first.");
      return;
    }
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != android.content.pm.PackageManager.PERMISSION_GRANTED)
      throw new SecurityException("Microphone access revoked");
    int buffer =
        Math.max(
            6400,
            AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
    recorder =
        new AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            buffer);
    if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
      throw new IOException("Microphone unavailable");
    recorder.startRecording();
    recording = true;
    state("■");
    handler.postDelayed(timeout, 120000);
    new Thread(
            () -> {
              try {
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                byte[] buf = new byte[3200];
                while (recording && pcm.size() < 16000 * 2 * 120) {
                  int count = recorder.read(buf, 0, buf.length);
                  if (count < 0) {
                    if (!recording) break;
                    throw new IOException("Microphone stopped unexpectedly");
                  }
                  if (count > 0) {
                    pcm.write(buf, 0, count);
                    double energy = 0;
                    for (int j = 0; j + 1 < count; j += 2) {
                      short sample = (short) ((buf[j] & 255) | (buf[j + 1] << 8));
                      energy += (double) sample * sample;
                    }
                    float db =
                        (float)
                            (20
                                * Math.log10(
                                    Math.max(1e-6, Math.sqrt(energy / (count / 2)) / 32768)));
                    float level = Math.max(0, Math.min(1, (db + 40) / 30));
                    handler.post(
                        () -> {
                          if (!cancelled && current()) owner.level(level);
                        });
                  }
                }
                recording = false;
                synchronized (this) {
                  if (recorder != null) {
                    try {
                      recorder.stop();
                    } catch (IllegalStateException ignored) {
                    }
                    recorder.release();
                    recorder = null;
                  }
                }
                if (cancelled) return;
                handler.post(
                    () -> {
                      state("…");
                      stopForeground(STOP_FOREGROUND_REMOVE);
                    });
                byte[] data = pcm.toByteArray();
                if (data.length < 3200) {
                  handler.post(() -> fail("Recording too short. Please try again."));
                  return;
                }
                String text = RemoteTranscriber.transcribe(this, wav(data));
                handler.post(() -> complete(text));
              } catch (Exception e) {
                handler.post(
                    () ->
                        fail("Transcription failed. Check your server connection and try again."));
              }
            },
            "hex-audio")
        .start();
  }

  static byte[] wav(byte[] pcm) {
    ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes())
        .putInt(36 + pcm.length)
        .put("WAVEfmt ".getBytes())
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(16000)
        .putInt(32000)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes())
        .putInt(pcm.length)
        .put(pcm);
    return b.array();
  }

  private void stopRecording() {
    if (!recording) return;
    recording = false;
    handler.removeCallbacks(timeout);
    state("…");
    if (recognizer != null) {
      recognizer.stopListening();
      handler.postDelayed(() -> fail("Recognition timed out. Try again."), 15000);
    }
  }

  private void complete(String text) {
    if (cancelled || finishing || !current()) return;
    finishing = true;
    owner.deliver(text);
    stopSelf();
  }

  private void fail(String message) {
    if (cancelled || finishing || !current()) return;
    finishing = true;
    owner.fail(message);
    stopSelf();
  }

  private void state(String s) {
    if (current() && !cancelled) owner.state(s);
  }

  private boolean current() {
    return owner != null && BubbleService.instance == owner && owner.isCurrentSession(session);
  }

  private void cancel() {
    cancelled = true;
    recording = false;
    if (recognizer != null) recognizer.cancel();
    stopSelf();
  }

  @Override
  public void onDestroy() {
    cancelled = true;
    recording = false;
    handler.removeCallbacksAndMessages(null);
    if (recognizer != null) {
      recognizer.destroy();
      recognizer = null;
    }
    synchronized (this) {
      if (recorder != null) {
        try {
          recorder.stop();
        } catch (IllegalStateException ignored) {
        }
      }
    }
    stopForeground(STOP_FOREGROUND_REMOVE);
    if (instance == this) instance = null;
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onReadyForSpeech(Bundle b) {}

  @Override
  public void onBeginningOfSpeech() {}

  @Override
  public void onRmsChanged(float f) {
    if (current() && !cancelled) owner.level(Math.max(0, Math.min(1, (f + 2) / 12)));
  }

  @Override
  public void onBufferReceived(byte[] b) {}

  @Override
  public void onEndOfSpeech() {
    recording = false;
    state("…");
  }

  @Override
  public void onError(int error) {
    fail(
        switch (error) {
          case SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
              "No speech detected. Try again.";
          case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                  SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
              "Download an offline language in your phone's speech settings, or select Godseye.";
          default -> "Offline recognition failed (" + error + "). Try again or select Godseye.";
        });
  }

  @Override
  public void onResults(Bundle result) {
    ArrayList<String> text = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    complete(text == null || text.isEmpty() ? "" : text.get(0));
  }

  @Override
  public void onPartialResults(Bundle b) {}

  @Override
  public void onEvent(int n, Bundle b) {}
}
