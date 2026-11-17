package dev.nosa.hex;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;
import java.text.DateFormat;
import java.util.Date;

public class HistoryActivity extends SettingsScreen {
  private TranscriptHistory history;
  private LinearLayout entries;
  private EditText search;
  private int limit = 100;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    history = new TranscriptHistory(this);
    label("H I S T O R Y", 12, 0xffa7bfff);
    label("Your words, saved.", 30, 0xfff6f4f0);
    label("Completed dictations stay on this phone until you delete them, including text that could not be inserted.", 15, 0xffb3adb9);
    search = new EditText(this);
    search.setHint("Search transcripts");
    search.setContentDescription("Search transcripts");
    search.setTextColor(0xfff6f4f0);
    search.setHintTextColor(0xffb3adb9);
    search.setSingleLine(true);
    column.addView(search);
    button("Clear history", () -> new AlertDialog.Builder(this)
        .setTitle("Delete all transcripts?")
        .setMessage("This permanently clears the history saved on this phone.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete all", (d, w) -> {
          try {
            history.clear();
            MainActivity.pendingTranscript = MainActivity.lastTranscript = null;
            if (BubbleService.instance != null) BubbleService.instance.refreshRecovery();
            refresh();
          } catch (android.database.SQLException e) { storageError(); }
        }).show());
    entries = new LinearLayout(this);
    entries.setOrientation(LinearLayout.VERTICAL);
    column.addView(entries);
    search.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        limit = 100;
        refresh();
      }
      public void afterTextChanged(Editable e) {}
    });
  }

  @Override
  public void onResume() {
    super.onResume();
    refresh();
  }

  private void refresh() {
    if (entries == null) return;
    entries.removeAllViews();
    try {
      var results = history.search(search.getText().toString(), limit + 1);
      if (results.isEmpty()) {
        TextView empty = new TextView(this);
        empty.setText(search.length() == 0 ? "No dictations yet. Your next completed dictation will appear here."
            : "No matching transcripts.");
        empty.setTextColor(0xffb3adb9);
        empty.setPadding(0, dp(24), 0, dp(24));
        entries.addView(empty);
      }
      for (int i = 0; i < Math.min(limit, results.size()); i++) {
        var entry = results.get(i);
        Button row = new Button(this);
        row.setAllCaps(false);
        row.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        row.setTextColor(0xfff6f4f0);
        String preview = entry.text().isBlank() ? "(Empty after customizations)" : entry.text();
        if (preview.length() > 180) preview = preview.substring(0, 180) + "…";
        row.setText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(new Date(entry.createdAt())) + " · " + entry.mode() + "\n" + preview);
        row.setPadding(dp(12), dp(16), dp(12), dp(16));
        entries.addView(row, new LinearLayout.LayoutParams(-1, -2));
        row.setOnClickListener(v -> show(entry));
      }
      if (results.size() > limit) {
        Button more = new Button(this);
        more.setText("Load older transcripts");
        entries.addView(more);
        more.setOnClickListener(v -> { limit += 100; refresh(); });
      }
    } catch (android.database.SQLException e) { storageError(); }
  }

  private void show(TranscriptHistory.Entry entry) {
    TextView text = new TextView(this);
    text.setText(entry.text());
    text.setTextIsSelectable(true);
    text.setTextSize(17);
    text.setPadding(dp(20), dp(12), dp(20), dp(12));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(text);
    new AlertDialog.Builder(this).setTitle("Saved transcript · " + entry.mode())
        .setView(scroll).setPositiveButton("Copy", (d, w) -> MainActivity.copyTranscript(this, entry.text()))
        .setNeutralButton("More", (d, w) -> actions(entry)).setNegativeButton("Close", null).show();
  }

  private void actions(TranscriptHistory.Entry entry) {
    String[] actions = entry.text().equals(entry.original())
        ? new String[] {"Copy", "Share", "Delete"}
        : new String[] {"Copy", "Share", "View original", "Delete"};
    new AlertDialog.Builder(this).setTitle("Saved transcript")
        .setItems(actions, (d, which) -> {
          switch (actions[which]) {
            case "Copy" -> {
              MainActivity.copyTranscript(this, entry.text());
              Toast.makeText(this, "Transcript copied", Toast.LENGTH_SHORT).show();
            }
            case "Share" -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, entry.text()), "Share transcript"));
            case "View original" -> new AlertDialog.Builder(this).setTitle("Before customizations")
                .setMessage(entry.original()).setPositiveButton("Copy original", (a, b) ->
                    MainActivity.copyTranscript(this, entry.original())).setNegativeButton("Close", null).show();
            case "Delete" -> new AlertDialog.Builder(this).setTitle("Delete transcript?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (a, b) -> {
                  try {
                    history.delete(entry.id());
                    if (entry.text().equals(MainActivity.lastTranscript)) MainActivity.lastTranscript = null;
                    if (entry.text().equals(MainActivity.pendingTranscript)) MainActivity.pendingTranscript = null;
                    if (BubbleService.instance != null) BubbleService.instance.refreshRecovery();
                    refresh();
                  } catch (android.database.SQLException e) { storageError(); }
                }).show();
          }
        }).setNegativeButton("Close", null).show();
  }

  private void storageError() {
    Toast.makeText(this, "Could not access transcript history. Check available phone storage.", Toast.LENGTH_LONG).show();
  }

  @Override
  public void onDestroy() {
    if (history != null) history.close();
    super.onDestroy();
  }
}
