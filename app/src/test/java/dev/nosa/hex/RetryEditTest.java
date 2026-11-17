package dev.nosa.hex;

import static org.junit.Assert.*;
import org.junit.Test;

public class RetryEditTest {
  @Test public void retryReplacesPreviousTranscriptAndPreservesSurroundingText() {
    RetryEdit edit = RetryEdit.captured("Hello world!", 6, 11).inserted("Nosa");
    assertTrue(edit.matches("Hello Nosa!", 10, 10));
    assertEquals("Hello new words!", edit.inserted("new words").text());
    assertEquals("Hello retry!", edit.inserted("new words").inserted("retry").text());
  }

  @Test public void editsAndCursorMovementInvalidateAutomaticReplacement() {
    RetryEdit edit = RetryEdit.captured("Hello world", 6, 11).inserted("Nosa");
    assertFalse(edit.matches("Hello Nosa!", 10, 10));
    assertFalse(edit.matches("Hello Nosa", 6, 10));
    assertFalse(edit.matches("Hello Nosa", 9, 9));
  }

  @Test public void failureCanRetryOriginalSelectionIncludingReversedSelection() {
    RetryEdit edit = RetryEdit.captured("Hello world", 11, 6);
    assertTrue(edit.matches("Hello world", 11, 6));
    assertEquals("Hello Nosa", edit.inserted("Nosa").text());
  }

  @Test public void emptyFieldsAndUnicodeKeepCorrectReplacementRange() {
    assertEquals("second", RetryEdit.captured("", 0, 0).inserted("first").inserted("second").text());
    RetryEdit edit = RetryEdit.captured("Hi 🌍!", 3, 5).inserted("Nosa");
    assertEquals("Hi 🌎!", edit.inserted("🌎").text());
    assertEquals(5, edit.inserted("🌎").selectionStart());
  }

  @Test public void hiddenOrInvalidSelectionsRequireManualRecovery() {
    assertNull(RetryEdit.captured("Hello", -1, -1));
    assertNull(RetryEdit.captured("Hello", 0, 6));
    assertNull(RetryEdit.captured(null, 0, 0));
  }
}
