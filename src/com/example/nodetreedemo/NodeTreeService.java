package com.example.nodetreedemo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.webkit.URLUtil;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Faithful, self-contained recreation of the "live node-tree recording" technique
 * observed in the analyzed Accountable2You APK (its class `v2`): for every
 * accessibility event it joins the event text, resolves the window / pane title,
 * walks the full live view tree reading each node's text, and detects URLs — then,
 * instead of persisting and uploading, it files the result as a structured record
 * shown on-device (per-app log + overlay HUD).
 *
 * It reproduces the real app's password-field redaction so you can see both what
 * IS captured and what is deliberately skipped.
 */
public class NodeTreeService extends AccessibilityService {

    private static final int MAX_NODES = 500;   // bound the walk so we never ANR
    private static final int MAX_DEPTH = 25;
    private static final int MAX_TEXT = 4000;    // cap per-record tree text length

    // Coalesce identical consecutive captures so the stream stays readable.
    private static final long COALESCE_MS = 2000;

    private final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private WindowManager wm;
    private TextView overlay;
    private boolean overlayAttached = false;

    private String lastKey = "";
    private long lastKeyTime = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;          // every event type
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        CaptureLog.add(new CaptureLog.Record(System.currentTimeMillis(),
                ts.format(new Date()), "(service)", "CONNECTED", "", "", null,
                "  • now observing every app on screen", 0));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            String pkg = String.valueOf(event.getPackageName());
            if ("com.example.nodetreedemo".equals(pkg)) return; // don't record our own UI
            if ("com.android.systemui".equals(pkg)) return;     // the real app skips this too

            String type = eventTypeName(event.getEventType());
            String eventText = joinEventText(event);

            AccessibilityNodeInfo src = event.getSource();
            String paneOrWindow = resolveTitle(src);

            StringBuilder tree = new StringBuilder();
            int[] count = {0};
            if (src != null) {
                walk(src, 0, tree, count);
            } else {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) walk(root, 0, tree, count);
            }

            String url = firstUrl(eventText, tree.toString());

            // Coalesce: drop a capture identical to the one just before it (within a
            // short window). This kills the TYPE_WINDOW_CONTENT_CHANGED spam and keeps
            // the on-screen stream slow enough to read.
            String key = pkg + '|' + type + '|' + paneOrWindow + '|' + eventText + '|' + tree.length();
            long now = System.currentTimeMillis();
            if (key.equals(lastKey) && now - lastKeyTime < COALESCE_MS) {
                updateOverlay(pkg, paneOrWindow, eventText, url, count[0]);
                return;
            }
            lastKey = key;
            lastKeyTime = now;

            CaptureLog.add(new CaptureLog.Record(now, ts.format(new Date(now)), pkg, type,
                    paneOrWindow, eventText, url, cap(tree.toString()), count[0]));
            updateOverlay(pkg, paneOrWindow, eventText, url, count[0]);
        } catch (Throwable t) {
            CaptureLog.add(new CaptureLog.Record(System.currentTimeMillis(),
                    ts.format(new Date()), "(error)", t.getClass().getSimpleName(),
                    "", "", null, "", 0));
        }
    }

    /** Recursively read text from every node — the heart of the technique. */
    private void walk(AccessibilityNodeInfo node, int depth, StringBuilder out, int[] count) {
        if (node == null || depth > MAX_DEPTH || count[0] >= MAX_NODES) return;
        count[0]++;

        String text = readNodeText(node);
        if (!text.isEmpty()) {
            for (int i = 0; i < depth; i++) out.append("  ");
            out.append("• ").append(text).append('\n');
            if (out.length() > MAX_TEXT) { out.append("  …(truncated)\n"); return; }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && count[0] < MAX_NODES; i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) walk(child, depth + 1, out, count);
        }
    }

    /**
     * Read a node's visible text — UNLESS it is a password/secret field, in which
     * case we redact it, reproducing the analyzed app's own filter (inputType
     * password variations, isPassword(), "•" mask, "password" hint/label).
     */
    private String readNodeText(AccessibilityNodeInfo n) {
        try {
            CharSequence t = n.getText();
            CharSequence cd = n.getContentDescription();
            String raw = t != null ? t.toString().trim()
                    : (cd != null ? cd.toString().trim() : "");
            if (raw.isEmpty()) return "";
            if (isSecret(n, raw)) return "[REDACTED password/secret field]";
            return raw;
        } catch (Throwable e) {
            return "";
        }
    }

    private boolean isSecret(AccessibilityNodeInfo n, String raw) {
        try {
            if (n.isPassword()) return true;
            int it = n.getInputType();
            if (it == 16 || it == 128 || it == 129 || it == 144 || it == 145 || it == 224) return true;
            if (raw.contains("•") || raw.contains("●")) return true;
            CharSequence hint = Build.VERSION.SDK_INT >= 26 ? n.getHintText() : null;
            if (hint != null && hint.toString().toLowerCase(Locale.ROOT).contains("password")) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private String resolveTitle(AccessibilityNodeInfo src) {
        String out = "";
        try {
            if (src != null && Build.VERSION.SDK_INT >= 28) {
                CharSequence p = src.getPaneTitle();
                if (p != null) out = p.toString().trim();
            }
            if (out.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && Build.VERSION.SDK_INT >= 28) {
                    CharSequence p = root.getPaneTitle();
                    if (p != null) out = p.toString().trim();
                }
            }
            if (out.isEmpty()) {
                for (AccessibilityWindowInfo w : getWindows()) {
                    if (w != null && w.isActive()) {
                        CharSequence wt = w.getTitle();
                        if (wt != null) { out = wt.toString().trim(); break; }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private String firstUrl(String eventText, String tree) {
        for (String blob : new String[]{eventText, tree}) {
            if (blob == null) continue;
            for (String tok : blob.split("\\s+")) {
                String c = tok.trim();
                if (c.isEmpty()) continue;
                if ((c.contains(".") && (c.startsWith("http") || c.startsWith("www.")))
                        && URLUtil.isValidUrl(c.startsWith("http") ? c : "https://" + c)) {
                    return c;
                }
            }
        }
        return null;
    }

    private String joinEventText(AccessibilityEvent event) {
        List<CharSequence> list = event.getText();
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : list) {
            if (cs == null) continue;
            String s = cs.toString().trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString().trim();
    }

    private void updateOverlay(String pkg, String title, String event, String url, int nodes) {
        String line = "👁 " + pkg + (title.isEmpty() ? "" : " · " + trunc(title, 50));
        CaptureLog.setLatest(line);
        if (!CaptureLog.isOverlayEnabled()) { detachOverlay(); return; }
        try {
            if (overlay == null) {
                overlay = new TextView(this);
                overlay.setTextColor(0xFFFFFFFF);
                overlay.setBackgroundColor(0xCC000000);
                overlay.setTextSize(11f);
                overlay.setPadding(18, 14, 18, 14);
            }
            StringBuilder hud = new StringBuilder();
            hud.append("👁  NodeTreeDemo sees:\n");
            hud.append("app: ").append(pkg).append('\n');
            if (!title.isEmpty()) hud.append("title: ").append(trunc(title, 60)).append('\n');
            if (!TextUtils.isEmpty(event)) hud.append("text: ").append(trunc(event, 70)).append('\n');
            if (url != null) hud.append("url: ").append(trunc(url, 70)).append('\n');
            hud.append('(').append(nodes).append(" on-screen nodes read)");
            overlay.setText(hud.toString());

            if (!overlayAttached && wm != null) {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.BOTTOM;
                wm.addView(overlay, lp);
                overlayAttached = true;
            }
        } catch (Throwable ignored) {}
    }

    private void detachOverlay() {
        try {
            if (overlayAttached && wm != null && overlay != null) {
                wm.removeView(overlay);
                overlayAttached = false;
            }
        } catch (Throwable ignored) {}
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent intent) {
        detachOverlay();
        return super.onUnbind(intent);
    }

    private static String cap(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "\n  …(truncated)\n";
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String eventTypeName(int t) {
        switch (t) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED: return "TYPE_VIEW_CLICKED";
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED: return "TYPE_VIEW_LONG_CLICKED";
            case AccessibilityEvent.TYPE_VIEW_SELECTED: return "TYPE_VIEW_SELECTED";
            case AccessibilityEvent.TYPE_VIEW_FOCUSED: return "TYPE_VIEW_FOCUSED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: return "TYPE_VIEW_TEXT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: return "TYPE_WINDOW_STATE_CHANGED";
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED: return "TYPE_NOTIFICATION_STATE_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER: return "TYPE_VIEW_HOVER_ENTER";
            case AccessibilityEvent.TYPE_VIEW_HOVER_EXIT: return "TYPE_VIEW_HOVER_EXIT";
            case AccessibilityEvent.TYPE_VIEW_SCROLLED: return "TYPE_VIEW_SCROLLED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED: return "TYPE_VIEW_TEXT_SELECTION_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: return "TYPE_WINDOW_CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_ANNOUNCEMENT: return "TYPE_ANNOUNCEMENT";
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED: return "TYPE_WINDOWS_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY: return "TYPE_VIEW_TEXT_TRAVERSED";
            default: return "type_0x" + Integer.toHexString(t);
        }
    }
}
