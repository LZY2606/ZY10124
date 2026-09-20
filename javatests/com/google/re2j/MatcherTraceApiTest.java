/*
 * Copyright (c) 2026 The Go Authors. All rights reserved.
 *
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */
package com.google.re2j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Public-API behaviour checks that accompany the NFA narrative in {@code docs/nfa-trace.md}:
 * repeated {@link Matcher#find}, the empty-match nudge, capture boundaries measured in UTF-16 code
 * units around surrogate pairs, and result reuse by {@code replaceAll}.
 */
@RunWith(JUnit4.class)
public class MatcherTraceApiTest {

  @Test
  public void repeatedFindAndEmptyMatchNudge() {
    Matcher m = Pattern.compile("a*").matcher("aba");
    List<int[]> hits = new ArrayList<int[]>();
    while (m.find()) {
      hits.add(new int[] {m.start(), m.end()});
      if (hits.size() > 10) {
        throw new AssertionError("non-terminating find loop");
      }
    }
    // [0,1)="a", [1,1) empty at 'b', [2,3)="a", [3,3) empty at end.
    assertEquals(4, hits.size());
    assertEquals(0, hits.get(0)[0]);
    assertEquals(1, hits.get(0)[1]);
    assertEquals(1, hits.get(1)[0]);
    assertEquals(1, hits.get(1)[1]);
    assertEquals(2, hits.get(2)[0]);
    assertEquals(3, hits.get(2)[1]);
    assertEquals(3, hits.get(3)[0]);
    assertEquals(3, hits.get(3)[1]);
  }

  @Test
  public void emptyNudgeAdvancesOneUtf16UnitAcrossSurrogatePair() {
    // Empty-capable pattern: the nudge after an empty match is start+1 (one UTF-16 code unit),
    // i.e. it may land in the middle of a surrogate pair; the machine then reads whole runes.
    Matcher m = Pattern.compile("").matcher("x\uD83D\uDE00y");
    assertTrue(m.find());
    assertEquals(0, m.start());
    assertEquals(0, m.end());
    assertTrue(m.find()); // nudged to 1 (high surrogate)
    assertEquals(1, m.start());
    assertEquals(1, m.end());
    assertTrue(m.find()); // next nudge to 2 (low surrogate)
    assertEquals(2, m.start());
    assertTrue(m.find()); // then 3
    assertEquals(3, m.start());
    assertTrue(m.find()); // then 4 (end)
    assertEquals(4, m.start());
    assertFalse(m.find());
  }

  @Test
  public void captureBoundariesAreUtf16CodeUnits() {
    // Group 1 wraps the supplementary rune U+1F600, which occupies two UTF-16 units.
    Matcher m = Pattern.compile("(\uD83D\uDE00+)").matcher("a\uD83D\uDE00b");
    assertTrue(m.find());
    assertEquals(1, m.start());
    assertEquals(3, m.end());
    assertEquals(1, m.start(1));
    assertEquals(3, m.end(1));
    assertEquals("\uD83D\uDE00", m.group(1));
    // Sanity: code-point count over the same span is one.
    assertEquals(1, m.group(1).codePointCount(0, m.group(1).length()));
  }

  @Test
  public void captureSpanningSurrogatePairInsideLineStartMatch() {
    // Same input as the traced one: "x\uD83D\uDE00a\nac"; group from the second line match.
    Matcher m = Pattern.compile("(?m)^(a|()b)a*c").matcher("x\uD83D\uDE00a\nac");
    assertTrue(m.find());
    assertEquals(5, m.start());
    assertEquals(7, m.end());
    assertEquals("a", m.group(1));
    // $2 is the nullable branch that did not participate: null group, -1 boundaries.
    assertNull(m.group(2));
    assertEquals(-1, m.start(2));
    assertEquals(-1, m.end(2));
  }

  @Test
  public void resetRewindsMatcherState() {
    Matcher m = Pattern.compile("a").matcher("aa");
    assertTrue(m.find());
    assertEquals(0, m.start());
    assertTrue(m.find());
    assertEquals(1, m.start());
    assertFalse(m.find());
    m.reset();
    assertTrue(m.find());
    assertEquals(0, m.start());

    m.reset("ba");
    assertTrue(m.find());
    assertEquals(1, m.start());
  }

  @Test
  public void replaceAllReusesFindAndGroupResults() {
    // $1 in the replacement reads the same captured boundaries produced by find().
    assertEquals("[a][b][a]", Pattern.compile("(a|b)").matcher("aba").replaceAll("[$1]"));
    // Empty matches participate but never cause an infinite loop: find() nudges by one UTF-16
    // unit, so "" matches at every boundary (here 0,1,2,3 -> '-' around every rune).
    assertEquals("-a-b-a-", Pattern.compile("").matcher("aba").replaceAll("-"));
    // re2j (unlike the JDK, which suppresses an empty result adjacent to a non-empty one)
    // emits both "a" at [0,1) and "" at [1,1), plus "" at end [2,2): X, X, b, X.
    assertEquals("XXbX", Pattern.compile("a*").matcher("ab").replaceAll("X"));
  }

  @Test
  public void firstAlternationWinsAtMergeEvenIfShorter() {
    // In "ax" both branches can match the prefix "a"; branch 1 reaches the continuation merge
    // first and owns it, so $1 is "a" even though its own greedy x? could have taken "x".
    Matcher m = Pattern.compile("(ax?|a)x").matcher("ax");
    assertTrue(m.matches());
    assertEquals("a", m.group(1));
  }

  @Test
  public void longestMatchFlagAffectsResultButNotPublicUnitSemantics() {
    String p = "(a+)|(a+ b+)";
    String in = "xxx aaa bbb yyy";
    Matcher first = Pattern.compile(p).matcher(in);
    assertTrue(first.find());
    assertEquals("aaa", first.group());
    Matcher longest = Pattern.compile(p, Pattern.LONGEST_MATCH).matcher(in);
    assertTrue(longest.find());
    assertEquals("aaa bbb", longest.group());
    assertEquals(4, longest.start());
    assertEquals(11, longest.end());
  }
}
