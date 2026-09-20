/*
 * Copyright (c) 2020 The Go Authors. All rights reserved.
 *
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */
package com.google.re2j.trace;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import com.google.re2j.Pattern;
import java.util.List;

/**
 * Minimal observer for a single NFA execution of the package-private
 * {@code com.google.re2j.Machine}.
 *
 * <p>
 * The tracer does not duplicate the VM: it drives the production methods
 * {@code Machine.add(Queue,int,int,int[],int,Thread)} and
 * {@code Machine.step(Queue,Queue,int,int,int,int,int,boolean)} directly via reflection; only the
 * small outer scheduling loop of {@code Machine.match} is mirrored so that the runnable thread sets
 * can be recorded after each rune step. No production class gains a field or method for
 * instrumentation, and this class is not part of the public API.
 */
final class NfaTracer {

  /** One rune position: the queue that became {@code runq} at that position. */
  static final class Round {
    final int pos;
    final int rune;
    final int width;
    final List<Integer> seededPcs; // runq just before step(), in queue order
    final List<int[]> seeded; // {pc, cap0, ...} of live threads in runq
    final List<int[]> next; // nextq threads produced by step()
    final boolean matched;
    final int[] matchcap;

    Round(
        int pos,
        int rune,
        int width,
        List<Integer> seededPcs,
        List<int[]> seeded,
        List<int[]> next,
        boolean matched,
        int[] matchcap) {
      this.pos = pos;
      this.rune = rune;
      this.width = width;
      this.seededPcs = seededPcs;
      this.seeded = seeded;
      this.next = next;
      this.matched = matched;
      this.matchcap = matchcap;
    }
  }

  private static final Class<?> MACHINE;
  private static final Class<?> QUEUE;
  private static final Class<?> MACHINE_INPUT;
  private static final Constructor<?> MACHINE_CTOR;
  private static final Method M_ADD;
  private static final Method M_STEP;
  private static final Method M_INIT;
  private static final Method M_SUBMATCHES;
  private static final Method MI_FROM_UTF16;
  private static final Method MI_STEP;
  private static final Method MI_CONTEXT;
  private static final Method MI_ENDPOS;
  private static final Field Q_SIZE;
  private static final Field Q_DENSE_PCS;
  private static final Field Q_DENSE_THREADS;
  private static final Field T_CAP;
  private static final Field M_MATCHED;
  private static final Field M_MATCHCAP;
  private static final Field M_NCAP;
  private static final Field RE2_COND;
  private static final Field RE2_PREFIX;
  private static final Field RE2_PREFIX_RUNE;
  private static final int EOF;
  private static final int UNANCHORED;
  private static final int EMPTY_BEGIN_TEXT;

  static {
    try {
      MACHINE = Class.forName("com.google.re2j.Machine");
      QUEUE = Class.forName("com.google.re2j.Machine$Queue");
      MACHINE_INPUT = Class.forName("com.google.re2j.MachineInput");
      Class<?> re2Class = Class.forName("com.google.re2j.RE2");
      Class<?> threadClass = Class.forName("com.google.re2j.Machine$Thread");
      Class<?> utils = Class.forName("com.google.re2j.Utils");

      MACHINE_CTOR = MACHINE.getDeclaredConstructor(re2Class);
      MACHINE_CTOR.setAccessible(true);
      M_ADD =
          MACHINE.getDeclaredMethod(
              "add", QUEUE, int.class, int.class, int[].class, int.class, threadClass);
      M_ADD.setAccessible(true);
      M_STEP =
          MACHINE.getDeclaredMethod(
              "step",
              QUEUE,
              QUEUE,
              int.class,
              int.class,
              int.class,
              int.class,
              int.class,
              boolean.class);
      M_STEP.setAccessible(true);
      M_INIT = MACHINE.getDeclaredMethod("init", int.class);
      M_INIT.setAccessible(true);
      M_SUBMATCHES = MACHINE.getDeclaredMethod("submatches");
      M_SUBMATCHES.setAccessible(true);

      MI_FROM_UTF16 = MACHINE_INPUT.getDeclaredMethod("fromUTF16", CharSequence.class);
      MI_FROM_UTF16.setAccessible(true);
      MI_STEP = MACHINE_INPUT.getDeclaredMethod("step", int.class);
      MI_STEP.setAccessible(true);
      MI_CONTEXT = MACHINE_INPUT.getDeclaredMethod("context", int.class);
      MI_CONTEXT.setAccessible(true);
      MI_ENDPOS = MACHINE_INPUT.getDeclaredMethod("endPos");
      MI_ENDPOS.setAccessible(true);

      Q_SIZE = QUEUE.getDeclaredField("size");
      Q_SIZE.setAccessible(true);
      Q_DENSE_PCS = QUEUE.getDeclaredField("densePcs");
      Q_DENSE_PCS.setAccessible(true);
      Q_DENSE_THREADS = QUEUE.getDeclaredField("denseThreads");
      Q_DENSE_THREADS.setAccessible(true);

      T_CAP = threadClass.getDeclaredField("cap");
      T_CAP.setAccessible(true);
      M_MATCHED = MACHINE.getDeclaredField("matched");
      M_MATCHED.setAccessible(true);
      M_MATCHCAP = MACHINE.getDeclaredField("matchcap");
      M_MATCHCAP.setAccessible(true);
      M_NCAP = MACHINE.getDeclaredField("ncap");
      M_NCAP.setAccessible(true);

      RE2_COND = re2Class.getDeclaredField("cond");
      RE2_COND.setAccessible(true);
      RE2_PREFIX = re2Class.getDeclaredField("prefix");
      RE2_PREFIX.setAccessible(true);
      RE2_PREFIX_RUNE = re2Class.getDeclaredField("prefixRune");
      RE2_PREFIX_RUNE.setAccessible(true);

      Field eof = MACHINE_INPUT.getDeclaredField("EOF");
      eof.setAccessible(true);
      EOF = eof.getInt(null);
      Field unanchored = re2Class.getDeclaredField("UNANCHORED");
      unanchored.setAccessible(true);
      UNANCHORED = unanchored.getInt(null);
      Field beginText = utils.getDeclaredField("EMPTY_BEGIN_TEXT");
      beginText.setAccessible(true);
      EMPTY_BEGIN_TEXT = beginText.getInt(null);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Object re2;
  private final Object prog;
  private final int ncap;
  private final List<Round> rounds = new ArrayList<Round>();

  NfaTracer(Pattern pattern, String input) throws ReflectiveOperationException {
    Field re2Field = Pattern.class.getDeclaredField("re2");
    re2Field.setAccessible(true);
    this.re2 = re2Field.get(pattern);
    Field progField = re2.getClass().getDeclaredField("prog");
    progField.setAccessible(true);
    this.prog = progField.get(re2);
    Field numCap = prog.getClass().getDeclaredField("numCap");
    numCap.setAccessible(true);
    this.ncap = numCap.getInt(prog);
    run(input);
  }

  static Object utf16Input(String s) throws ReflectiveOperationException {
    return MI_FROM_UTF16.invoke(null, s);
  }

  private int step(Object in, int pos) throws ReflectiveOperationException {
    return (Integer) MI_STEP.invoke(in, pos);
  }

  private void run(String src) throws ReflectiveOperationException {
    Object machine = MACHINE_CTOR.newInstance(re2);
    M_INIT.invoke(machine, ncap);
    int[] matchcap = (int[]) M_MATCHCAP.get(machine);
    Arrays.fill(matchcap, 0, ncap, -1);

    int anchor = UNANCHORED;
    int startCond = RE2_COND.getInt(re2);
    String prefix = (String) RE2_PREFIX.get(re2);
    int prefixRune = RE2_PREFIX_RUNE.getInt(re2);

    Object in = utf16Input(src);
    int endPos = (Integer) MI_ENDPOS.invoke(in);
    int pos = 0;

    Object runq = newQueue(machine, 0);
    Object nextq = newQueue(machine, 1);

    int packed = step(in, pos);
    int rune = packed >> 3;
    int width = packed & 7;
    int rune1 = -1;
    int width1 = 0;
    if (packed != EOF) {
      int p1 = step(in, pos + width);
      rune1 = p1 >> 3;
      width1 = p1 & 7;
    }
    int flag = (Integer) MI_CONTEXT.invoke(in, pos);

    for (int guard = 0; guard < src.length() + 4; guard++) {
      if (queueSize(runq) == 0) {
        if ((startCond & EMPTY_BEGIN_TEXT) != 0 && pos != 0) {
          break;
        }
        if (M_MATCHED.getBoolean(machine)) {
          break;
        }
        if (!prefix.isEmpty() && rune1 != prefixRune) {
          // Literal-prefix fast skip: MachineInput.index jumps pos to the
          // next prefix occurrence instead of walking rune by rune.
          Method index =
              MACHINE_INPUT.getDeclaredMethod(
                  "index", Class.forName("com.google.re2j.RE2"), int.class);
          index.setAccessible(true);
          int advance = (Integer) index.invoke(in, re2, pos);
          if (advance < 0) {
            break;
          }
          pos += advance;
          packed = step(in, pos);
          rune = packed >> 3;
          width = packed & 7;
          packed = step(in, pos + width);
          rune1 = packed >> 3;
          width1 = packed & 7;
        }
      }
      if (!M_MATCHED.getBoolean(machine) && (pos == 0 || anchor == UNANCHORED)) {
        if (ncap > 0) {
          matchcap[0] = pos;
        }
        add(machine, runq, startPc(), pos, matchcap, flag, null);
      }

      int nextPos = pos + width;
      int nextFlag = (Integer) MI_CONTEXT.invoke(in, nextPos);
      // Deep-copy the seeded runq before step(), which frees every thread back
      // into the machine pool and nulls the dense slots.
      List<Integer> seededPcs = pcsOf(runq);
      List<int[]> seeded = threadsOf(runq);
      M_STEP.invoke(machine, runq, nextq, pos, nextPos, rune, nextFlag, anchor, pos == endPos);
      List<int[]> next = threadsOf(nextq);
      rounds.add(
          new Round(
              pos,
              rune,
              width,
              seededPcs,
              seeded,
              next,
              M_MATCHED.getBoolean(machine),
              Arrays.copyOf((int[]) M_MATCHCAP.get(machine), M_NCAP.getInt(machine))));

      if (width == 0) {
        break;
      }
      if (ncap == 0 && M_MATCHED.getBoolean(machine)) {
        break;
      }
      pos += width;
      rune = rune1;
      width = width1;
      if (rune != -1) {
        int p1 = step(in, pos + width);
        rune1 = p1 >> 3;
        width1 = p1 & 7;
      }
      Object tmp = runq;
      runq = nextq;
      nextq = tmp;
    }
  }

  private int startPc() throws ReflectiveOperationException {
    Field start = prog.getClass().getDeclaredField("start");
    start.setAccessible(true);
    return start.getInt(prog);
  }

  private Object newQueue(Object machine, int which) throws ReflectiveOperationException {
    Field qf = MACHINE.getDeclaredField(which == 0 ? "q0" : "q1");
    qf.setAccessible(true);
    return qf.get(machine);
  }

  private static void add(Object machine, Object q, int pc, int pos, int[] cap, int cond, Object t)
      throws ReflectiveOperationException {
    M_ADD.invoke(machine, q, pc, pos, cap, cond, t);
  }

  private static int queueSize(Object q) throws ReflectiveOperationException {
    return Q_SIZE.getInt(q);
  }

  private static List<Integer> pcsOf(Object q) throws ReflectiveOperationException {
    int size = Q_SIZE.getInt(q);
    int[] pcs = (int[]) Q_DENSE_PCS.get(q);
    List<Integer> out = new ArrayList<Integer>();
    for (int j = 0; j < size; j++) {
      out.add(pcs[j]);
    }
    return out;
  }

  private static List<int[]> threadsOf(Object q) throws ReflectiveOperationException {
    int size = Q_SIZE.getInt(q);
    int[] pcs = (int[]) Q_DENSE_PCS.get(q);
    Object[] threads = (Object[]) Q_DENSE_THREADS.get(q);
    List<int[]> list = new ArrayList<int[]>();
    for (int j = 0; j < size; j++) {
      Object t = threads[j];
      if (t == null) {
        // Null dense slots are pc-only markers left by add() when an ALT path
        // reached an already-queued pc; the live thread is an earlier slot.
        continue;
      }
      int[] cap = (int[]) T_CAP.get(t);
      int[] entry = new int[cap.length + 1];
      entry[0] = pcs[j];
      // Copy the caps too: pooled Thread objects (and their arrays) are reused
      // or mutated in place by CAPTURE closures during later rounds.
      System.arraycopy(cap, 0, entry, 1, cap.length);
      list.add(entry);
    }
    return list;
  }

  List<Round> rounds() {
    return rounds;
  }

  /** Runq pcs (including null epsilon slots) when the rune is consumed. */
  List<Integer> pcsAt(int roundIndex) {
    return rounds.get(roundIndex).seededPcs;
  }

  /** Pcs of the live (rune/MATCH-holding) threads seeded into runq, in order. */
  List<Integer> livePcsAt(int roundIndex) {
    List<Integer> pcs = new ArrayList<Integer>();
    for (int[] e : rounds.get(roundIndex).seeded) {
      pcs.add(e[0]);
    }
    return pcs;
  }

  /** Cap slots of a live nextq thread at pc, or null if pc is not queued there. */
  int[] capsAt(int roundIndex, int pc) {
    for (int[] e : rounds.get(roundIndex).next) {
      if (e[0] == pc) {
        return Arrays.copyOfRange(e, 1, e.length);
      }
    }
    return null;
  }

  /** Cap slots of the live runq thread at pc before the step. */
  int[] seededCapsAt(int roundIndex, int pc) {
    for (int[] e : rounds.get(roundIndex).seeded) {
      if (e[0] == pc) {
        return Arrays.copyOfRange(e, 1, e.length);
      }
    }
    return null;
  }

  int[] finalMatchcap() {
    return rounds.get(rounds.size() - 1).matchcap;
  }

  boolean matched() {
    return rounds.get(rounds.size() - 1).matched;
  }
}
