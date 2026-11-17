package dev.nosa.hex;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Phone-private history; deliberately excluded from backup along with the rest of Hex. */
final class TranscriptHistory extends SQLiteOpenHelper {
  record Entry(long id, long createdAt, String text, String original, String mode, String engine) {}

  TranscriptHistory(Context context) {
    this(context, "transcript-history.db");
  }

  TranscriptHistory(Context context, String databaseName) {
    super(context.getApplicationContext(), databaseName, null, 1);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE transcripts (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL,"
        + " text TEXT NOT NULL, original TEXT NOT NULL, mode TEXT NOT NULL, engine TEXT NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

  void add(String text, String original, String mode, String engine) {
    if (original == null || original.isBlank()) return;
    ContentValues values = new ContentValues();
    values.put("created_at", System.currentTimeMillis());
    values.put("text", text);
    values.put("original", original);
    values.put("mode", mode);
    values.put("engine", engine);
    getWritableDatabase().insertOrThrow("transcripts", null, values);
  }

  List<Entry> search(String query, int limit) {
    List<Entry> entries = new ArrayList<>();
    String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    try (Cursor cursor = getReadableDatabase().query("transcripts", null,
        "(text LIKE ? ESCAPE '\\' OR original LIKE ? ESCAPE '\\')", new String[] {pattern, pattern},
        null, null, "created_at DESC, id DESC", Integer.toString(limit))) {
      while (cursor.moveToNext()) {
        entries.add(new Entry(cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
            cursor.getString(3), cursor.getString(4), cursor.getString(5)));
      }
    }
    return entries;
  }

  void delete(long id) {
    getWritableDatabase().delete("transcripts", "id = ?", new String[] {Long.toString(id)});
  }

  void clear() {
    getWritableDatabase().delete("transcripts", null, null);
  }
}
