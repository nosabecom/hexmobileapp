package dev.nosa.hex;

import java.util.*;
import java.util.regex.Pattern;

/** Data-only equivalents of Hex's native text transformations. */
final class TextTransforms {
  record Rule(String phrase, String output) {}
  private record Match(int start, int end, int rule) {}

  static String apply(String text, List<Rule> rules, List<String> transformations) {
    text = replace(text, rules);
    for (String transformation : transformations) {
      text = switch (transformation) {
        case "lowercase" -> text.toLowerCase(Locale.ROOT);
        case "spongebob-case" -> sponge(text);
        case "no-trailing-punctuation" -> noTrailingPunctuation(text);
        default -> text;
      };
    }
    return text;
  }

  static String noTrailingPunctuation(String text) {
    int end = text.length();
    while (end > 0 && Character.isWhitespace(text.codePointBefore(end)))
      end -= Character.charCount(text.codePointBefore(end));
    int marks = end;
    while (marks > 0 && ".,!?;:。！？…、，；：".indexOf(text.codePointBefore(marks)) >= 0)
      marks -= Character.charCount(text.codePointBefore(marks));
    return text.substring(0, marks) + text.substring(end);
  }

  static String sponge(String text) {
    StringBuilder out = new StringBuilder();
    boolean upper = false;
    for (int i = 0; i < text.length(); ) {
      int code = text.codePointAt(i);
      String letter = new String(Character.toChars(code));
      if (Character.isLetter(code)) {
        out.append(upper ? letter.toUpperCase(Locale.ROOT) : letter.toLowerCase(Locale.ROOT));
        upper = !upper;
      } else out.append(letter);
      i += Character.charCount(code);
    }
    return out.toString();
  }

  private static boolean word(int code) {
    String script = Character.UnicodeScript.of(code).name();
    if (Set.of("HAN", "HIRAGANA", "KATAKANA", "HANGUL", "THAI", "LAO", "KHMER", "MYANMAR", "TIBETAN").contains(script)) return false;
    return Character.isLetterOrDigit(code) || code == '_';
  }

  static String replace(String text, List<Rule> rules) {
    List<Match> candidates = new ArrayList<>();
    for (int rule = 0; rule < rules.size(); rule++) {
      String phrase = rules.get(rule).phrase().trim();
      if (phrase.isEmpty()) continue;
      var matcher = Pattern.compile(Pattern.quote(phrase), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
      int from = 0;
      while (from < text.length() && matcher.find(from)) {
        int start = matcher.start(), end = matcher.end();
        if ((start == 0 || !word(text.codePointBefore(start))) && (end == text.length() || !word(text.codePointAt(end)))) {
          candidates.add(new Match(start, end, rule));
          from = end;
        } else from = start + Character.charCount(text.codePointAt(start));
      }
    }
    candidates.sort(Comparator.<Match>comparingInt(m -> -(m.end() - m.start()))
        .thenComparingInt(Match::start).thenComparingInt(Match::rule));
    List<Match> selected = new ArrayList<>();
    for (Match candidate : candidates) {
      if (selected.stream().noneMatch(m -> candidate.start() < m.end() && m.start() < candidate.end()))
        selected.add(candidate);
    }
    selected.sort(Comparator.comparingInt(Match::start));
    StringBuilder out = new StringBuilder();
    int cursor = 0;
    boolean[] capitalize = {false};
    for (int i = 0; i < selected.size(); i++) {
      Match match = selected.get(i);
      if (cursor < match.start()) push(out, text.substring(cursor, match.start()), capitalize);
      String replacement = rules.get(match.rule()).output();
      cursor = match.end();
      if (!replacement.isEmpty()) {
        push(out, replacement, capitalize);
        continue;
      }
      int next = i + 1 < selected.size() ? selected.get(i + 1).start() : text.length();
      String between = text.substring(match.end(), next);
      if (sentenceStart(out)) {
        int first = text.substring(match.start(), match.end()).codePoints().filter(Character::isLetter).findFirst().orElse(0);
        if (Character.isUpperCase(first)) capitalize[0] = true;
        int skip = 0;
        while (skip < between.length() && ".!?,;:".indexOf(between.charAt(skip)) >= 0) skip++;
        while (skip < between.length() && horizontal(between.charAt(skip))) skip++;
        cursor += skip;
        if ((skip == between.length() && next == text.length()) || (skip < between.length() && between.charAt(skip) == '\n')) trim(out);
      } else {
        int remaining = match.end();
        while (remaining < text.length() && horizontal(text.charAt(remaining))) remaining++;
        boolean atEnd = remaining == text.length() || text.charAt(remaining) == '\n';
        if ((!between.isEmpty() && ".!?,;:".indexOf(between.charAt(0)) >= 0) || atEnd) {
          trim(out);
          if (out.length() > 0 && out.charAt(out.length() - 1) == ',') out.setLength(out.length() - 1);
          if (atEnd) while (cursor < next && horizontal(text.charAt(cursor))) cursor++;
        } else if (out.length() > 0 && horizontal(out.charAt(out.length() - 1))) {
          while (cursor < next && horizontal(text.charAt(cursor))) cursor++;
        }
      }
    }
    if (cursor < text.length()) push(out, text.substring(cursor), capitalize);
    return out.toString();
  }

  private static boolean horizontal(char c) { return c == ' ' || c == '\t'; }

  private static void trim(StringBuilder out) {
    while (out.length() > 0 && horizontal(out.charAt(out.length() - 1))) out.setLength(out.length() - 1);
  }

  private static boolean sentenceStart(StringBuilder out) {
    int end = out.length();
    while (end > 0 && horizontal(out.charAt(end - 1))) end--;
    return end == 0 || ".!?\n".indexOf(out.charAt(end - 1)) >= 0;
  }

  private static void push(StringBuilder out, String segment, boolean[] capitalize) {
    int first = 0;
    while (first < segment.length() && Character.isWhitespace(segment.codePointAt(first)))
      first += Character.charCount(segment.codePointAt(first));
    if (capitalize[0] && first < segment.length()) {
      out.append(segment, 0, first);
      int code = segment.codePointAt(first);
      String letter = new String(Character.toChars(code));
      out.append(Character.isLowerCase(code) && sentenceStart(out) ? letter.toUpperCase(Locale.ROOT) : letter);
      out.append(segment.substring(first + Character.charCount(code)));
    } else out.append(segment);
    if (first < segment.length()) capitalize[0] = false;
  }
}
