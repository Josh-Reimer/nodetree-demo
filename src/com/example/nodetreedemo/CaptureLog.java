package com.example.nodetreedemo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Shared, in-memory ring buffer of captured records. This is where the real app
 * would instead queue records for upload; here it only ever feeds the on-screen
 * display. No I/O, no network.
 *
 * Records are kept as structured objects (not pre-formatted strings) so the UI
 * can group/filter them per app and format them per view.
 */
public final class CaptureLog {

    /** One captured accessibility event, already extracted. */
    public static final class Record {
        public final long tsMillis;
        public final String ts;     // HH:mm:ss.SSS
        public final String pkg;    // source package ("app")
        public final String type;   // event type name
        public final String title;  // window / pane title
        public final String event;  // joined event text
        public final String url;    // detected URL, or null
        public final String tree;   // indented screen-text dump
        public final int nodes;     // nodes read for this event

        public Record(long tsMillis, String ts, String pkg, String type, String title,
                      String event, String url, String tree, int nodes) {
            this.tsMillis = tsMillis;
            this.ts = ts;
            this.pkg = pkg;
            this.type = type;
            this.title = title == null ? "" : title;
            this.event = event == null ? "" : event;
            this.url = url;
            this.tree = tree == null ? "" : tree;
            this.nodes = nodes;
        }
    }

    private static final int MAX = 400;
    private static final ArrayDeque<Record> BUF = new ArrayDeque<>();

    private static volatile String latest = "";          // one line, for the overlay HUD
    private static volatile boolean overlayEnabled = true;

    public static synchronized void add(Record r) {
        BUF.addLast(r);
        while (BUF.size() > MAX) BUF.removeFirst();
    }

    public static synchronized List<Record> snapshot() {
        return new ArrayList<>(BUF);
    }

    /** Distinct packages seen so far, in first-seen order — for the app filter. */
    public static synchronized List<String> packages() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (Record r : BUF) s.add(r.pkg);
        return new ArrayList<>(s);
    }

    public static synchronized int size() { return BUF.size(); }

    public static synchronized void clear() { BUF.clear(); latest = ""; }

    public static void setLatest(String s) { latest = s; }
    public static String latest() { return latest; }

    public static boolean isOverlayEnabled() { return overlayEnabled; }
    public static void setOverlayEnabled(boolean b) { overlayEnabled = b; }
}
