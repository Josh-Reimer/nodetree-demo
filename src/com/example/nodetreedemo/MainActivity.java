package com.example.nodetreedemo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    // Slow, readable cadence (was 500ms).
    private static final long REFRESH_MS = 1200;
    private static final String ALL = "All apps";

    private TextView log;
    private TextView status;
    private ScrollView scroll;
    private Button btnOverlay;
    private Button btnPause;
    private Spinner appFilter;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean paused = false;
    private String selectedPkg = ALL;      // ALL = no filter
    private int lastPkgCount = -1;
    private int bufferedWhilePaused = 0;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            maybeRebuildFilter();
            renderStatus();
            if (!paused) renderLog();           // frozen while paused so you can scroll
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Android 15+ is edge-to-edge by default: pad the root by the system-bar
        // insets so the top controls aren't drawn under the status/action bar.
        final View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                int l, t, r, b;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets si = insets.getInsets(
                            android.view.WindowInsets.Type.systemBars()
                                    | android.view.WindowInsets.Type.displayCutout());
                    l = si.left; t = si.top; r = si.right; b = si.bottom;
                } else {
                    l = insets.getSystemWindowInsetLeft();
                    t = insets.getSystemWindowInsetTop();
                    r = insets.getSystemWindowInsetRight();
                    b = insets.getSystemWindowInsetBottom();
                }
                int pad = (int) (12 * getResources().getDisplayMetrics().density);
                v.setPadding(l + pad, t + pad, r + pad, b + pad);
                return insets;
            }
        });
        root.requestApplyInsets();

        log = findViewById(R.id.log);
        status = findViewById(R.id.status);
        scroll = findViewById(R.id.scroll);
        btnOverlay = findViewById(R.id.btnOverlay);
        btnPause = findViewById(R.id.btnPause);
        appFilter = findViewById(R.id.appFilter);

        findViewById(R.id.btnEnable).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        btnOverlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !CaptureLog.isOverlayEnabled();
                CaptureLog.setOverlayEnabled(now);
                btnOverlay.setText(now ? "Overlay: ON" : "Overlay: OFF");
            }
        });

        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                paused = !paused;
                btnPause.setText(paused ? "Resume" : "Pause");
                if (!paused) { bufferedWhilePaused = 0; renderLog(); }
            }
        });

        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CaptureLog.clear();
                bufferedWhilePaused = 0;
                lastPkgCount = -1;
                renderLog();
            }
        });

        appFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPkg = String.valueOf(parent.getItemAtPosition(pos));
                renderLog();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        btnOverlay.setText(CaptureLog.isOverlayEnabled() ? "Overlay: ON" : "Overlay: OFF");
        setFilterItems(new ArrayList<String>());
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause()  { super.onPause();  ui.removeCallbacks(refresh); }

    /** Rebuild the app-filter dropdown only when the set of seen apps grows. */
    private void maybeRebuildFilter() {
        List<String> pkgs = CaptureLog.packages();
        if (pkgs.size() == lastPkgCount) return;
        lastPkgCount = pkgs.size();
        setFilterItems(pkgs);
    }

    private void setFilterItems(List<String> pkgs) {
        List<String> items = new ArrayList<>();
        items.add(ALL);
        items.addAll(pkgs);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        appFilter.setAdapter(ad);
        int idx = items.indexOf(selectedPkg);
        appFilter.setSelection(idx >= 0 ? idx : 0);
    }

    private void renderStatus() {
        boolean enabled = isServiceEnabled();
        String base = enabled
                ? "Capture service ENABLED · " + CaptureLog.size() + " records"
                : "NOT enabled — tap ‘Enable service’ → turn on ‘Node Tree Demo’.";
        if (paused) base += "  ·  PAUSED (scroll freely)";
        status.setText("Status: " + base);
        status.setTextColor(enabled ? 0xFF1B5E20 : 0xFFB00020);
    }

    private void renderLog() {
        List<CaptureLog.Record> all = CaptureLog.snapshot();
        if (all.isEmpty()) {
            log.setText("(nothing captured yet)\n\nEnable the service, then switch to another "
                    + "app — every screen's text will appear here, grouped per app.");
            return;
        }

        boolean filtered = !ALL.equals(selectedPkg);
        StringBuilder sb = new StringBuilder();
        String lastHeaderPkg = null;
        int shown = 0;

        for (CaptureLog.Record r : all) {
            if (filtered && !r.pkg.equals(selectedPkg)) continue;

            // Group: print an app header whenever the app changes (or once, when filtered).
            if (!r.pkg.equals(lastHeaderPkg)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("──────── ").append(r.pkg).append(" ────────\n");
                lastHeaderPkg = r.pkg;
            }

            sb.append('[').append(r.ts).append("]  ").append(r.type).append('\n');
            if (!r.title.isEmpty()) sb.append("  title : ").append(trunc(r.title, 200)).append('\n');
            if (!r.event.isEmpty()) sb.append("  event : ").append(trunc(r.event, 300)).append('\n');
            if (r.url != null)      sb.append("  URL   : ").append(trunc(r.url, 300)).append('\n');
            if (!r.tree.isEmpty())  sb.append("  screen text (").append(r.nodes).append(" nodes):\n")
                                      .append(r.tree);
            sb.append('\n');
            shown++;
        }

        if (shown == 0) {
            log.setText("(no records for " + selectedPkg + " yet)");
            return;
        }
        log.setText(sb.toString());

        // Auto-scroll to the newest only while live (never while paused).
        if (!paused) {
            scroll.post(new Runnable() {
                @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    private boolean isServiceEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        return flat.contains(getPackageName() + "/");
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
