package dev.nosa.hex;

/** Only replace a previous dictation while its text and cursor are still untouched. */
record RetryEdit(String text, int selectionStart, int selectionEnd, int from, int to) {
  static RetryEdit captured(String text, int start, int end) {
    if (text == null || start < 0 || end < 0 || start > text.length() || end > text.length())
      return null;
    return new RetryEdit(text, start, end, Math.min(start, end), Math.max(start, end));
  }

  boolean matches(String current, int start, int end) {
    return text.equals(current) && selectionStart == start && selectionEnd == end;
  }

  RetryEdit inserted(String transcript) {
    TextInsertion.Result result = TextInsertion.insert(text, from, to, transcript);
    return new RetryEdit(result.text(), result.cursor(), result.cursor(), from, result.cursor());
  }
}
