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
 * Structural tests of the NFA simulation traced through the package-private {@link Machine.Tracer}
 * hook. The assertions check a few semantic events (match result, capture slots, key thread
 * transitions), never a full log dump, so the tests survive unrelated instruction renumbering.
 *
 * <p>
 * See {@code docs/nfa-trace.md} for the narrative walk-through this test verifies.
 */
@RunWith(JUnit4.class)
public class TraceTest {

  // (?m): ^ may also match after a newline; alternation with a nullable second branch;
  // greedy a*; two capturing groups: $1 = (a|()b), $2 = () (the empty branch).
  private static final String PATTERN = "(?m)^(a|()b)a*c";
  // UTF-16 code unit layout: 0:'x' 1:'\uD83D' 2:'\uDE00' 3:'a' 4:'\n' 5:'a' 6:'c'
  private static final String INPUT = "x\uD83D\uDE00a\nac";

  private static TraceDump.Trace traceMain() {
    return TraceDump.run(PATTERN, INPUT, 0, RE2.UNANCHORED, false);
  }

  // Positions at which the start thread's (?m)^ EMPTY_WIDTH(1) is rejected.
  private static List<Integer> rejectedPositions(TraceDump.Trace t) {
    List<Integer> positions = new ArrayList<Integer>();
    // rejection alone does not carry pos, but each rejection is immediately preceded by a
    // "start" visit at the same position; walk the event stream pairwise.
    List<TraceDump.Event> events = t.events;
    for (int i = 1; i < events.size(); i++) {
      TraceDump.Event e = events.get(i);
      if (e.kind.equals("emptyRejected") && e.pc == 1) {
        for (int j = i - 1; j >= 0; j--) {
          TraceDump.Event prev = events.get(j);
          if (prev.kind.equals("visit") && prev.value == 0 && prev.pc == 1) {
            positions.add(prev.pos);
            break;
          }
        }
      }
    }
    return positions;
  }

  @Test
  public void finalMatchAndCaptures() {
    TraceDump.Trace t = traceMain();
    assertTrue(t.matched);
    // Whole match "ac" at UTF-16 units [5,7); $1="a" [5,6); $2 did not participate -> -1.
    assertEquals(java.util.Arrays.asList(5, 7, 5, 6, -1, -1), toBoxed(t.matchcap));
  }

  @Test
  public void startThreadFailsAssertionOffLineStart() {
    TraceDump.Trace t = traceMain();
    // New start threads are tried at every position; ^ is rejected everywhere except 0 and 5.
    assertEquals(java.util.Arrays.asList(1, 3, 4, 6, 7), rejectedPositions(t));
    // No rejection at the two line starts.
    assertFalse(rejectedPositions(t).contains(0));
    assertFalse(rejectedPositions(t).contains(5));
  }

  @Test
  public void greedyStarSpawnsExitThreadFirst() {
    TraceDump.Trace t = traceMain();
    // At pos 5 (line start) both alternation branches park before consuming rune 'a' at 5.
    List<TraceDump.Event> parks5 = parksAt(t, 5);
    assertEquals(java.util.Arrays.asList(3, 7), pcs(parks5));
    // The pc=7 thread carries the empty $2 capture: slots 4,5 are both 5.
    TraceDump.Event emptyBranch = parks5.get(1);
    assertEquals(5, emptyBranch.caps[4]);
    assertEquals(5, emptyBranch.caps[5]);

    // Round at rune 'a' (pos 5): after consuming it the greedy a* loop ALT (pc 11) is
    // filled with out (body, pc 10) before arg (exit, pc 12), so nextq parks [10, 12].
    TraceDump.Event end = stepEndAt(t, 6);
    assertEquals(java.util.Arrays.asList(10, 12), livePcs(end));
  }

  @Test
  public void captureSlotFlowAroundMatch() {
    TraceDump.Trace t = traceMain();
    // Slot 3 (ket of $1) is written at pos 6 when a*'s body thread returns through the ket.
    TraceDump.Event ket = lastEvent(t, "captureSet", 3);
    assertEquals(6, ket.value); // new value
    // The winning MATCH thread closes slot 1 at 7.
    TraceDump.Event win = lastEvent(t, "matchWin");
    assertEquals(7, win.pos);
    assertEquals(java.util.Arrays.asList(5, 7, 5, 6, -1, -1), toBoxed(win.caps));
  }

  @Test
  public void threadQueueDedupKeepsFirstPriority() {
    // Classic Russ Cox example: /^(a|ab)c/ on "abc".  The first alternation's continuation
    // reaches the merge first at pos 1 and would dominate it, but it dies on rune 'b'; the
    // later-ending second branch is still alive in parallel and completes the whole match.
    TraceDump.Trace t = TraceDump.run("^(a|ab)c", "abc", 0, RE2.ANCHOR_START, false);
    assertTrue(t.matched);
    assertEquals(java.util.Arrays.asList(0, 3, 0, 2), toBoxed(t.matchcap));
    // After consuming 'a', queue order encodes priority: pc 8 (branch 1 continuation)
    // precedes pc 5 (branch 2 body).
    TraceDump.Event end = stepEndAt(t, 1);
    assertEquals(java.util.Arrays.asList(8, 5), livePcs(end));
    // Branch 1 dies on 'b'; only branch 2 reaches MATCH, and no pruning happened before win.
    assertNull(lastOrNull(t.events("pruneTail")));
  }

  @Test
  public void leftmostFirstPrunesQueueAfterWin() {
    // Main pattern: once MATCH fires in leftmost-first mode the remaining queued threads
    // (restart threads, alternatives) are discarded.
    TraceDump.Trace t = traceMain();
    TraceDump.Event prune = lastEvent(t, "pruneTail");
    assertEquals(1, prune.pc); // free(runq, 1): only the winning thread is consumed
    // And the win happens before the search restarts at the EOF position.
    assertTrue(indexOf(t, "matchWin") < indexOf(t, "finish"));
  }

  @Test
  public void literalPrefixAcceleratorSkipsToCandidate() {
    // "fooa?c" has required literal prefix "foo": the cursor jumps from 0 to 4.
    TraceDump.Trace t = TraceDump.run("fooa?c", "zzzzfooc", 0, RE2.UNANCHORED, false);
    assertTrue(t.matched);
    TraceDump.Event skip = lastEvent(t, "prefixSkip");
    assertEquals(0, skip.pos);
    assertEquals(4, skip.value);
    assertEquals(java.util.Arrays.asList(4, 8), toBoxed(t.matchcap));
  }

  @Test
  public void longestFlagChangesSelectionAtMachineLayer() {
    // POSIX syntax has no (?:...); use plain capturing parens.
    String p = "(a+)|(a+ b+)";
    String in = "xxx aaa bbb yyy";
    TraceDump.Trace first = TraceDump.run(p, in, 0, RE2.UNANCHORED, false);
    assertTrue(first.matched);
    // Leftmost-first: first MATCH (short) wins and the queue tail is pruned.
    assertEquals(
        java.util.Arrays.asList(4, 7), toBoxed(java.util.Arrays.copyOf(first.matchcap, 2)));
    assertTrue(!first.events("pruneTail").isEmpty());

    TraceDump.Trace longest = TraceDump.run(p, in, 0, RE2.UNANCHORED, true);
    assertTrue(longest.matched);
    // Longest: later, longer replacements keep arriving; final is "aaa bbb" [4,11);
    // no tail prune is ever requested in longest mode.
    assertEquals(
        java.util.Arrays.asList(4, 11), toBoxed(java.util.Arrays.copyOf(longest.matchcap, 2)));
    assertTrue(longest.events("pruneTail").isEmpty());
    List<TraceDump.Event> wins = longest.events("matchWin");
    assertTrue(wins.size() > 1); // short win replaced by longer ones
    assertTrue(wins.get(wins.size() - 1).value == 1);
  }

  @Test
  public void surrogatePairAdvancesTwoUtf16Units() {
    // One round for the whole rune U+1F600; the nextPos after that round is 3, not 2.
    TraceDump.Trace t = traceMain();
    TraceDump.Event end = stepEndAt(t, 3); // nextPos of the rune-at-pos-1 round
    assertEquals(3, end.pos);
    // And the rune consumed in that round is the supplementary character.
    TraceDump.Event begin = stepBeginAtRune(t, 0x1F600);
    assertEquals(1, begin.pos);
  }

  // ---- helpers ----

  private static List<TraceDump.Event> parksAt(TraceDump.Trace t, int pos) {
    List<TraceDump.Event> out = new ArrayList<TraceDump.Event>();
    for (TraceDump.Event e : t.events("park")) {
      if (e.pos == pos) {
        out.add(e);
      }
    }
    return out;
  }

  private static TraceDump.Event stepEndAt(TraceDump.Trace t, int nextPos) {
    for (TraceDump.Event e : t.events("stepEnd")) {
      if (e.pos == nextPos) {
        return e;
      }
    }
    throw new AssertionError("no stepEnd at nextPos=" + nextPos + "\n" + t.render());
  }

  private static TraceDump.Event stepBeginAtRune(TraceDump.Trace t, int rune) {
    for (TraceDump.Event e : t.events("stepBegin")) {
      if (e.value == rune) {
        return e;
      }
    }
    throw new AssertionError("no stepBegin for rune " + rune);
  }

  private static List<Integer> pcs(List<TraceDump.Event> parks) {
    List<Integer> out = new ArrayList<Integer>();
    for (TraceDump.Event e : parks) {
      out.add(e.pc);
    }
    return out;
  }

  private static List<Integer> livePcs(TraceDump.Event stepEnd) {
    List<Integer> out = new ArrayList<Integer>();
    for (int pc : stepEnd.pcs) {
      if (pc >= 0) {
        out.add(pc);
      }
    }
    return out;
  }

  private static TraceDump.Event lastEvent(TraceDump.Trace t, String kind) {
    TraceDump.Event e = lastOrNull(t.events(kind));
    if (e == null) {
      throw new AssertionError("no " + kind + " event\n" + t.render());
    }
    return e;
  }

  private static TraceDump.Event lastEvent(TraceDump.Trace t, String kind, int pc) {
    List<TraceDump.Event> all = t.events(kind);
    for (int i = all.size() - 1; i >= 0; i--) {
      if (all.get(i).pc == pc) {
        return all.get(i);
      }
    }
    throw new AssertionError("no " + kind + " with pc " + pc);
  }

  private static TraceDump.Event lastOrNull(List<TraceDump.Event> events) {
    return events.isEmpty() ? null : events.get(events.size() - 1);
  }

  private static int indexOf(TraceDump.Trace t, String kind) {
    for (int i = 0; i < t.events.size(); i++) {
      if (t.events.get(i).kind.equals(kind)) {
        return i;
      }
    }
    return -1;
  }

  private static List<Integer> toBoxed(int[] a) {
    List<Integer> out = new ArrayList<Integer>();
    for (int x : a) {
      out.add(x);
    }
    return out;
  }
}
