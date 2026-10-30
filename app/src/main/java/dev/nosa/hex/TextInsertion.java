package dev.nosa.hex;

final class TextInsertion {
  record Result(String text, int cursor) {}

  static Result insert(String original, int start, int end, String transcript) {
    if (start < 0 || end < 0 || start > original.length() || end > original.length())
      throw new IllegalArgumentException("This app does not expose a cursor position.");
    int a = Math.min(start, end), b = Math.max(start, end);
    return new Result(
        original.substring(0, a) + transcript + original.substring(b), a + transcript.length());
  }
}
