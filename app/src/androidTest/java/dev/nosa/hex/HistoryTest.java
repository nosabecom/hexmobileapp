package dev.nosa.hex;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;

public class HistoryTest {
  private Context context;
  private String database;

  @Before public void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    database = "history-test-" + java.util.UUID.randomUUID() + ".db";
  }

  @After public void cleanup() { context.deleteDatabase(database); }

  @Test public void survivesReopeningAndKeepsAllDictationsIncludingDuplicates() {
    try (TranscriptHistory store = new TranscriptHistory(context, database)) {
      store.add("hello", "Hello.", "Global", "On-device");
      store.add("hello", "Hello.", "Global", "On-device");
      store.add("next", "Next.", "Global", "server");
      store.add("", "Um.", "Global", "server");
      store.add("", "", "Global", "server");
    }
    try (TranscriptHistory store = new TranscriptHistory(context, database)) {
      var results = store.search("", 100);
      assertEquals(4, results.size());
      assertEquals("", results.get(0).text());
      assertEquals("Um.", results.get(0).original());
      assertEquals("next", results.get(1).text());
      assertEquals("Hello.", results.get(2).original());
      assertTrue(results.get(0).createdAt() > 0);
      assertEquals(2, store.search("HELLO", 100).size());
      assertEquals(1, store.search("", 1).size());
      store.delete(results.get(1).id());
      assertTrue(store.search("Next", 100).isEmpty());
      store.clear();
      assertTrue(store.search("", 100).isEmpty());
    }
  }

  @Test public void searchesOriginalTextAndTreatsWildcardsAsLiteralText() {
    try (TranscriptHistory store = new TranscriptHistory(context, database)) {
      store.add("100% a_b \\ text", "One hundred percent.", "Global", "server");
      store.add("other", "Other.", "Global", "server");
      assertEquals(1, store.search("%", 100).size());
      assertEquals(1, store.search("_", 100).size());
      assertEquals(1, store.search("\\", 100).size());
      assertEquals(1, store.search("hundred", 100).size());
      assertTrue(store.search("' OR 1=1 --", 100).isEmpty());
    }
  }
}
