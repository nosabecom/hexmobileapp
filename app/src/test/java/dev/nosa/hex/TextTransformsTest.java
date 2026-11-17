package dev.nosa.hex;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class TextTransformsTest {
  private TextTransforms.Rule rule(String phrase, String output) {
    return new TextTransforms.Rule(phrase, output);
  }

  @Test public void punctuationPreservesInternalMarksWhitespaceAndClosingQuotes() {
    assertEquals("Hello, world", TextTransforms.noTrailingPunctuation("Hello, world.!?"));
    assertEquals("Hello  \n", TextTransforms.noTrailingPunctuation("Hello？！。  \n"));
    assertEquals("\"Hello.\"", TextTransforms.noTrailingPunctuation("\"Hello.\""));
    assertEquals("(Hello.)", TextTransforms.noTrailingPunctuation("(Hello.)!"));
    assertEquals("Hello", TextTransforms.noTrailingPunctuation("Hello,;:，；："));
    assertEquals("", TextTransforms.noTrailingPunctuation("?!"));
    assertEquals("  \n", TextTransforms.noTrailingPunctuation("  \n"));
  }

  @Test public void correctionsUseLongestMatchAndDoNotRecurse() {
    var rules = List.of(rule("open", "closed"), rule("open code", "OpenCode"),
        rule("code then open", "combined"), rule("closed", "shut"));
    assertEquals("closed combined", TextTransforms.replace("open code then open", rules));
    assertEquals("first", TextTransforms.replace("HEX", List.of(rule("hex", "first"), rule("HEX", "second"))));
  }

  @Test public void correctionsRespectBoundariesUnicodeAndLiteralOutput() {
    var rules = List.of(rule("open code", "OpenCode"), rule("über", "Über"), rule("issue", "$1\\x"));
    assertEquals("OpenCode. Über; reopen code open codes übercool", TextTransforms.replace("OPEN CODE. ÜBER; reopen code open codes übercool", rules));
    assertEquals("这个$1\\x很重要 issue2 issue_id", TextTransforms.replace("这个issue很重要 issue2 issue_id", rules));
    assertEquals("Hi I think so.", TextTransforms.replace("Hi I I think so.", List.of(rule("I I", "I"))));
  }

  @Test public void removedFillersKeepSpacingPunctuationAndSentenceCase() {
    var rules = List.of(rule("um", ""), rule("uh", ""));
    assertEquals("I think so.", TextTransforms.replace("I um think so.", rules));
    assertEquals("I, think so.", TextTransforms.replace("I, um, uh, think so.", rules));
    assertEquals("The build passed.", TextTransforms.replace("Um, uh, the build passed.", rules));
    assertEquals("the build passed.", TextTransforms.replace("um, the build passed.", rules));
    assertEquals("I think so.", TextTransforms.replace("I think so, um.", rules));
    assertEquals("I think so", TextTransforms.replace("I think so, um", rules));
    assertEquals("", TextTransforms.replace("Um.", rules));
  }

  @Test public void transformationsRunAfterCorrectionsInSavedOrder() {
    assertEquals("opencode works", TextTransforms.apply("OPEN CODE works.",
        List.of(rule("open code", "OpenCode")), List.of("lowercase", "no-trailing-punctuation")));
    assertEquals("hElLo, WoRlD!", TextTransforms.sponge("HELLO, world!"));
    assertEquals("hello", TextTransforms.apply("HELLO", List.of(), List.of("spongebob-case", "lowercase")));
    assertEquals("hElLo", TextTransforms.apply("HELLO", List.of(), List.of("lowercase", "spongebob-case")));
  }
}
