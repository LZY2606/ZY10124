/*
 * Copyright (c) 2020 The Go Authors. All rights reserved.
 *
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */
package com.google.re2j.trace;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tracing tests for the threaded-NFA execution of a single small pattern:
 *
 * <pre>{@code
 * ^((a)*|a)(b?)
 * }</pre>
 *
 * It exercises an alternation, a greedy repetition, a nullable alternative ({@code (b?)} and the
 * {@code a} alternative is shadowed by {@code (a)*}), a begin-line assertion and two capturing
 * groups. The assertions are about the final match/captures and a handful of decisive thread
 * transitions, not the full trace log.
 */
@RunWith(JUnit4.class)
public class NfaTraceTest {

  private static final String REGEX = "^((a)*|a)(b?)";
  private static final String INPUT = "aa\ud835\udd4fb"; // 'aa' + U+1D54F + 'b'

  private static NfaTracer trace(String regex, String input) throws Exception {
    return new NfaTracer(Pattern.compile(regex), input);
  }

  @Test
  public void publicMatchAndCaptures() {
    Matcher m = Pattern.compile(REGEX).matcher(INPUT);
    assertTrue(m.find());
    assertEquals(0, m.start());
    assertEquals(2, m.end());
    assertEquals("aa", m.group());
    assertEquals("aa", m.group(1));
    assertEquals("a", m.group(2));
    assertEquals("", m.group(3));
  }

  @Test
  public void tracerAgreesWithPublicResult() throws Exception {
    NfaTracer t = trace(REGEX, INPUT);
    assertTrue(t.matched());
    // cap layout: [m0,m1, g1s,g1e, g2s,g2e, g3s,g3e]
    assertArrayEquals(new int[] {0, 2, 0, 2, 1, 2, 2, 2}, t.finalMatchcap());
  }

  @Test
  public void seededThreadsRespectAltPriority() throws Exception {
    NfaTracer t = trace(REGEX, INPUT);

    // pos=0: the seeded live threads, in queue order, are the greedy star
    // body (pc 4), the nullable (b?) body (pc 11) and exit (pc 14), then the
    // shadowed second alternative (pc 7). pc 4 and pc 11 are enqueued by ALT
    // closures before their siblings, so the loop body precedes the exit.
    assertEquals(Arrays.asList(4, 11, 14, 7), t.livePcsAt(0));

    // pos=1: pc 14 matched at pos=0, so step() pruned the lower-priority pc 7;
    // the surviving greedy continuation (4) and the nullable (11,14) remain.
    assertEquals(Arrays.asList(4, 11, 14), t.livePcsAt(1));

    // pos=2: same set tries the supplementary rune U+1D54F (width 2); neither
    // 'a' nor 'b' matches it, so the next round drains.
    assertEquals(Arrays.asList(4, 11, 14), t.livePcsAt(2));
  }

  @Test
  public void captureSlotsFlowWithThreads() throws Exception {
    NfaTracer t = trace(REGEX, INPUT);

    // At pos=1 the live body thread already carries the open group1 and the
    // open/close pair of group2's first iteration (slots 2,4,5 set; the close
    // of group1 (slot 3) and group3 pair (slots 6,7) stay -1 until closure).
    // The body thread seeded at pos=1 is the continuation that consumed the
    // first 'a', so it already carries group2's first closed iteration [1,1]
    // (the close capture fires as the thread enters pc5 at nextPos=1).
    int[] caps = t.seededCapsAt(1, 4);
    assertNotNull(caps);
    assertEquals(0, caps[0]);
    assertEquals(-1, caps[1]);
    assertEquals(0, caps[2]); // group1 open
    assertEquals(-1, caps[3]);
    assertEquals(1, caps[4]); // group2 first iteration boundary at 1
    assertEquals(1, caps[5]);
    assertEquals(-1, caps[6]);
    assertEquals(-1, caps[7]);

    // The same thread, as captured at the pos=0 transition, shows that same
    // boundary being written while the rune is consumed.
    int[] moved = t.capsAt(0, 4);
    assertNotNull(moved);
    assertEquals(1, moved[4]);
    assertEquals(1, moved[5]);
  }

  @Test
  public void laterEndingGreedyBranchWins() throws Exception {
    // Both the nullable (b?) exit and the shadowed 'a' alternative finish
    // earlier conceptually, but because the greedy loop body is queued first,
    // its later-ending MATCH overwrites matchcap: the whole match is "aa".
    NfaTracer t = trace(REGEX, INPUT);
    assertEquals(0, t.finalMatchcap()[0]);
    assertEquals(2, t.finalMatchcap()[1]);
    assertEquals(0, t.finalMatchcap()[2]);
    assertEquals(2, t.finalMatchcap()[3]);
  }

  @Test
  public void runeWidthIsUtf16UnitsForSupplementaryCodePoint() throws Exception {
    // U+1D54F occupies two UTF-16 code units at indices 2..4; the machine rune
    // step reports width 2, and public start/end are UTF-16 offsets.
    Matcher m = Pattern.compile("(.)").matcher(INPUT);
    assertTrue(m.find());
    assertTrue(m.find());
    assertTrue(m.find());
    assertEquals(2, m.start(1));
    assertEquals(4, m.end(1));
    assertEquals(2, m.end(1) - m.start(1));
    assertEquals(0x1d54f, m.group(1).codePointAt(0));

    NfaTracer t = trace(REGEX, INPUT);
    // Round at pos=2 consumes the surrogate pair: rune U+1D54F, width 2.
    NfaTracer.Round r = t.rounds().get(2);
    assertEquals(2, r.pos);
    assertEquals(0x1d54f, r.rune);
    assertEquals(2, r.width);
    // Neither 'a' nor 'b' matches it, so the thread set at pos=2 is the last
    // live round; at pos=4 the queue drains, and EMPTY_BEGIN_TEXT prevents any
    // restart for the ^-anchored program.
    assertTrue(r.next.isEmpty());
    assertTrue(t.matched());
    assertEquals(0, t.finalMatchcap()[0]);
  }

  @Test
  public void consecutiveFindNudgesPastEmptyMatch() {
    Matcher m = Pattern.compile("a*").matcher("baab");
    assertTrue(m.find());
    assertEquals(0, m.start());
    assertEquals(0, m.end());
    assertTrue(m.find()); // empty match nudges start by one UTF-16 unit
    assertEquals(1, m.start());
    assertEquals(3, m.end());
    assertTrue(m.find()); // trailing empties at 3 and 4
    assertEquals(3, m.end());
    assertTrue(m.find());
    assertEquals(4, m.start());
    assertFalse(m.find());
    assertEquals("-b--b-", Pattern.compile("a*").matcher("baab").replaceAll("-"));
  }

  @Test
  public void resetDiscardsMatchState() {
    Matcher m = Pattern.compile("a").matcher("aa");
    assertTrue(m.find());
    assertEquals(0, m.start());
    m.reset();
    assertTrue(m.find()); // search restarts at 0
    assertEquals(0, m.start());
  }

  @Test
  public void literalPrefixSkipsNonCandidatePositions() throws Exception {
    // The whole regexp is the required literal prefix. With an empty runq the
    // machine probes the next rune (rune1); when at pos 2 the lookahead is the
    // prefix's first rune 'a', so MachineInput.index jumps straight there and
    // the first seeded round is pos 2 -- positions 0 and 1 are never walked.
    NfaTracer t = new NfaTracer(Pattern.compile("abc"), "xxabc");
    List<NfaTracer.Round> rounds = t.rounds();
    assertEquals(2, rounds.get(0).pos);
    int[] mc = t.finalMatchcap();
    assertEquals(2, mc[0]);
    assertEquals(5, mc[1]);
  }

  @Test
  public void beginTextProgramStopsAfterDrain() throws Exception {
    // \A gives the program a leading EMPTY_BEGIN_WIDTH(4); the machine seeds
    // threads only at pos 0 and, once the queue drains later, never restarts
    // (the (startCond & EMPTY_BEGIN_TEXT) break in Machine.match).
    NfaTracer t = new NfaTracer(Pattern.compile("\\Aabc"), "xabc");
    assertFalse(t.matched());
    assertTrue(t.rounds().size() <= 2); // pos 0 drain, then stop
  }

  @Test
  public void longestFlagChangesPriorityInMachineOnly() throws Exception {
    // Identical program for both modes; only the Machine's match/step policy
    // differs (longest == true keeps exploring and maximizes end position).
    Pattern first = Pattern.compile("a|aa");
    Pattern longest = Pattern.compile("a|aa", Pattern.LONGEST_MATCH);
    Matcher m1 = first.matcher("xaa");
    Matcher m2 = longest.matcher("xaa");
    assertTrue(m1.find());
    assertTrue(m2.find());
    assertEquals(2, m1.end()); // leftmost-first: first alternative, shorter
    assertEquals(3, m2.end()); // leftmost-longest: later-ending match kept

    NfaTracer t = new NfaTracer(longest, "xaa");
    assertTrue(t.matched());
    assertArrayEquals(new int[] {1, 3}, t.finalMatchcap());
  }
}
