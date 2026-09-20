/*
 * Copyright (c) 2026 The Go Authors. All rights reserved.
 *
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */
package com.google.re2j;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Small package-private helper that drives a single real {@link Machine} execution with a
 * {@link Machine.Tracer} installed and records a compact stream of NFA events. It exists only to
 * support {@code TraceTest}; it is deliberately not public and exposes no internal debug state
 * beyond immutable value copies ({@link Event}).
 */
final class TraceDump {

  /** One observed NFA event. All arrays are private defensive copies. */
  static final class Event {
    final String kind; // see kinds emitted in Recorder below
    final int qid;
    final int pc;
    final int pos;
    final int value;
    final int[] caps; // capture snapshot (park, matchWin) or null
    final int[] pcs; // parked pc list (stepEnd) or null
    final int[][] capList; // capture snapshots parallel to pcs (stepEnd) or null

    Event(
        String kind, int qid, int pc, int pos, int value, int[] caps, int[] pcs, int[][] capList) {
      this.kind = kind;
      this.qid = qid;
      this.pc = pc;
      this.pos = pos;
      this.value = value;
      this.caps = caps;
      this.pcs = pcs;
      this.capList = capList;
    }

    private static final String[] KIND_NAMES = {
      "start", "alt-out", "alt-arg", "empty", "nop", "cap", "after-rune"
    };

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(kind);
      if (kind.equals("visit")) {
        sb.append(" q")
            .append(qid)
            .append(" pc=")
            .append(pc)
            .append(" pos=")
            .append(pos)
            .append(" via=")
            .append(KIND_NAMES[value]);
      } else if (kind.equals("dedup")) {
        sb.append(" q").append(qid).append(" pc=").append(pc);
      } else if (kind.equals("emptyRejected")) {
        sb.append(" q")
            .append(qid)
            .append(" pc=")
            .append(pc)
            .append(" want=0x")
            .append(Integer.toHexString(value));
      } else if (kind.equals("captureSet")) {
        sb.append(" q")
            .append(qid)
            .append(" slot=")
            .append(pc)
            .append(": ")
            .append(pos)
            .append("->")
            .append(value);
      } else if (kind.equals("park")) {
        sb.append(" q")
            .append(qid)
            .append(" pc=")
            .append(pc)
            .append(" pos=")
            .append(pos)
            .append(" cap=")
            .append(capString(caps));
      } else if (kind.equals("stepBegin")) {
        sb.append(" round=")
            .append(qid)
            .append(" runq=q")
            .append(pc)
            .append(" pos=")
            .append(pos)
            .append(" rune=")
            .append(value == -1 ? "EOF" : new String(Character.toChars(value)));
      } else if (kind.equals("stepEnd")) {
        sb.append(" round=")
            .append(qid)
            .append(" nextq=q")
            .append(pc)
            .append(" nextPos=")
            .append(pos)
            .append(" threads=")
            .append(threadsString());
      } else if (kind.equals("matchWin")) {
        sb.append(" pos=")
            .append(pos)
            .append(" replace=")
            .append(value == 1)
            .append(" cap=")
            .append(capString(caps));
      } else if (kind.equals("pruneTail")) {
        sb.append(" q").append(qid).append(" from=").append(pc);
      } else if (kind.equals("prefixSkip")) {
        sb.append(" pos=").append(pos).append(" +").append(value);
      } else if (kind.equals("finish")) {
        sb.append(" matched=").append(value == 1);
      }
      return sb.toString();
    }

    private String pcsString() {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < pcs.length; i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(pcs[i]);
      }
      return sb.append(']').toString();
    }

    private String threadsString() {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < pcs.length; i++) {
        if (i > 0) {
          sb.append("; ");
        }
        sb.append("pc=")
            .append(pcs[i])
            .append(" cap=")
            .append(capList[i] == null ? "<none>" : capString(capList[i]));
      }
      return sb.append(']').toString();
    }
  }

  static String capString(int[] caps) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < caps.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(caps[i]);
    }
    return sb.append(']').toString();
  }

  /** Immutable result of one traced execution. */
  static final class Trace {
    final boolean matched;
    final int[] matchcap;
    final List<Event> events;
    final String prog;

    Trace(boolean matched, int[] matchcap, List<Event> events, String prog) {
      this.matched = matched;
      this.matchcap = matchcap;
      this.events = events;
      this.prog = prog;
    }

    /** Human-readable multi-line trace, for diagnostics and ad hoc inspection. */
    String render() {
      StringBuilder sb = new StringBuilder();
      sb.append(prog);
      for (Event e : events) {
        sb.append(e).append('\n');
      }
      return sb.toString();
    }

    List<Event> events(String kind) {
      List<Event> out = new ArrayList<Event>();
      for (Event e : events) {
        if (e.kind.equals(kind)) {
          out.add(e);
        }
      }
      return out;
    }
  }

  private TraceDump() {}

  /** Compiles pattern with PERL flags (or POSIX/longest when {@code longest}). */
  static RE2 compile(String pattern, boolean longest) {
    return longest ? RE2.compilePOSIX(pattern) : RE2.compile(pattern);
  }

  static Trace run(String pattern, String input, int start, int anchor, boolean longest) {
    return run(compile(pattern, longest), input, start, anchor);
  }

  /** Runs one match() on a freshly allocated, non-pooled Machine and records all events. */
  static Trace run(RE2 re2, String input, int start, int anchor) {
    MachineInput in = newUTF16Input(input, 0, input.length());
    Machine machine = new Machine(re2);
    final List<Event> events = new ArrayList<Event>();
    machine.setTracer(new Recorder(events));
    machine.init(re2.prog.numCap);
    boolean matched = machine.match(in, start, anchor);
    int[] caps = matched ? machine.submatches() : null;
    return new Trace(matched, caps, events, re2.prog.toString());
  }

  static String prog(RE2 re2) {
    return re2.prog.toString();
  }

  // UTF16Input is a private nested class, so construct it reflectively (same package).
  private static MachineInput newUTF16Input(CharSequence s, int start, int end) {
    try {
      Class<?> c = Class.forName("com.google.re2j.MachineInput$UTF16Input");
      Constructor<?> ctor = c.getDeclaredConstructor(CharSequence.class, int.class, int.class);
      ctor.setAccessible(true);
      return (MachineInput) ctor.newInstance(s, start, end);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static final class Recorder extends Machine.Tracer {
    private final List<Event> events;

    Recorder(List<Event> events) {
      this.events = events;
    }

    @Override
    void visit(int qid, int pc, int pos, String kind) {
      events.add(new Event("visit", qid, pc, pos, kindIndex(kind), null, null, null));
    }

    @Override
    void dedup(int qid, int pc) {
      events.add(new Event("dedup", qid, pc, 0, 0, null, null, null));
    }

    @Override
    void emptyRejected(int qid, int pc, int want, int have) {
      events.add(new Event("emptyRejected", qid, pc, 0, want, null, null, null));
    }

    @Override
    void captureSet(int qid, int slot, int oldPos, int newPos) {
      events.add(new Event("captureSet", qid, slot, oldPos, newPos, null, null, null));
    }

    @Override
    void park(int qid, int pc, int pos, int[] cap) {
      events.add(new Event("park", qid, pc, pos, 0, cap, null, null));
    }

    @Override
    void stepBegin(int round, int runqId, int pos, int rune) {
      events.add(new Event("stepBegin", round, runqId, pos, rune, null, null, null));
    }

    @Override
    void stepEnd(int round, int nextqId, int nextPos, int[] pcs, int[][] caps) {
      events.add(new Event("stepEnd", round, nextqId, nextPos, 0, null, pcs, caps));
    }

    @Override
    void matchWin(int pos, int[] cap, boolean replace) {
      events.add(new Event("matchWin", 0, 0, pos, replace ? 1 : 0, cap, null, null));
    }

    @Override
    void pruneTail(int qid, int from) {
      events.add(new Event("pruneTail", qid, from, 0, 0, null, null, null));
    }

    @Override
    void finish(boolean matched, int[] matchcap) {
      events.add(new Event("finish", 0, 0, 0, matched ? 1 : 0, null, null, null));
    }

    @Override
    void prefixSkip(int pos, int advance) {
      events.add(new Event("prefixSkip", 0, 0, pos, advance, null, null, null));
    }

    private static int kindIndex(String kind) {
      if (kind.equals("start")) {
        return 0;
      } else if (kind.equals("alt-out")) {
        return 1;
      } else if (kind.equals("alt-arg")) {
        return 2;
      } else if (kind.equals("empty")) {
        return 3;
      } else if (kind.equals("nop")) {
        return 4;
      } else if (kind.equals("cap")) {
        return 5;
      } else if (kind.equals("after-rune")) {
        return 6;
      } else {
        throw new AssertionError(kind);
      }
    }
  }
}
