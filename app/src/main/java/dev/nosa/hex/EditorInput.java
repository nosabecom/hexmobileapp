package dev.nosa.hex;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.annotation.TargetApi;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.SurroundingText;
import java.util.Objects;

/** Uses the editor's input connection when accessibility hides its selection. */
@TargetApi(33)
final class EditorInput extends InputMethod {
  private static final int CONTEXT = 4096;
  private long generation;

  record Snapshot(long generation, AccessibilityInputConnection connection, SurroundingText text) {}

  EditorInput(AccessibilityService service) {
    super(service);
  }

  @Override
  public void onStartInput(EditorInfo info, boolean restarting) {
    super.onStartInput(info, restarting);
    generation++;
  }

  @Override
  public void onFinishInput() {
    super.onFinishInput();
    generation++;
  }

  Snapshot capture(AccessibilityNodeInfo node) {
    EditorInfo info = getCurrentInputEditorInfo();
    if (!getCurrentInputStarted() || info == null || !BubbleService.eligible(node)
        || !Objects.equals(info.packageName, String.valueOf(node.getPackageName()))) return null;
    AccessibilityInputConnection connection = getCurrentInputConnection();
    if (connection == null) return null;
    SurroundingText text = connection.getSurroundingText(CONTEXT, CONTEXT, 0);
    if (text == null || text.getOffset() < 0 || text.getSelectionStart() < 0
        || text.getSelectionEnd() < 0 || text.getSelectionStart() > text.getText().length()
        || text.getSelectionEnd() > text.getText().length()) return null;
    return new Snapshot(generation, connection, text);
  }

  BubbleService.Insertion insert(AccessibilityNodeInfo node, Snapshot snapshot, String transcript) {
    if (snapshot == null || snapshot.generation() != generation) return BubbleService.Insertion.REJECTED;
    Snapshot current = capture(node);
    if (current == null || current.generation() != snapshot.generation()
        || !sameText(snapshot.text(), current.text())) return BubbleService.Insertion.REJECTED;
    SurroundingText before = snapshot.text();
    TextInsertion.Result edit = TextInsertion.insert(before.getText().toString(),
        before.getSelectionStart(), before.getSelectionEnd(), transcript);
    // Use the captured connection, so an asynchronous focus change cannot redirect this edit.
    snapshot.connection().commitText(transcript, 1, null);
    // A commit is asynchronous and has no return value. Never issue a second insertion
    // if the editor accepts the request but its result cannot be confirmed yet.
    SurroundingText after = snapshot.connection().getSurroundingText(
        CONTEXT + transcript.length(), CONTEXT, 0);
    return after != null && after.getOffset() == before.getOffset()
        && after.getSelectionStart() == edit.cursor() && after.getSelectionEnd() == edit.cursor()
        && Objects.equals(edit.text(), after.getText().toString())
        ? BubbleService.Insertion.CONFIRMED : BubbleService.Insertion.ACCEPTED;
  }

  private static boolean sameText(SurroundingText a, SurroundingText b) {
    return a.getOffset() == b.getOffset() && a.getSelectionStart() == b.getSelectionStart()
        && a.getSelectionEnd() == b.getSelectionEnd()
        && Objects.equals(a.getText().toString(), b.getText().toString());
  }
}
