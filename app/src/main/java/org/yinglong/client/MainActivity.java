package org.yinglong.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.diag.AppLog;
import org.yinglong.client.net.OpenVpnProfileUtil;
import org.yinglong.client.net.RelayProbe;
import org.yinglong.client.net.SessionRefreshMonitor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-button UI. Long-press START opens the persistent diagnostic log. */
public final class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Button toggle;
    private volatile boolean started;
    private SessionRefreshMonitor refreshMonitor;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppLog.init(this);
        AppLog.i("ui", "MainActivity created; v0.2.1 diagnostic build");
        setContentView(buildUi());
    }

    private FrameLayout buildUi() {
        FrameLayout root = new FrameLayout(this);
        toggle = new Button(this);
        toggle.setText("СТАРТ");
        toggle.setTextSize(22f);
        toggle.setAllCaps(false);
        toggle.setOnClickListener(v -> {
            if (started) stopSession(); else startSession();
        });
        toggle.setOnLongClickListener(v -> {
            showLogDialog();
            return true;
        });

        int w = dp(220);
        int h = dp(72);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        root.addView(toggle, lp);
        return root;
    }

    private void startSession() {
        started = true;
        toggle.setEnabled(false);
        toggle.setText("ПОИСК…");
        AppLog.i("session", "START pressed");

        refreshMonitor = new SessionRefreshMonitor(this);
        refreshMonitor.start();

        io.execute(() -> {
            try {
                List<Relay> relays = new RelayStore(this).read();
                AppLog.i("session", "local relay pool size=" + relays.size());
                List<RelayProbe.Result> ranked = RelayProbe.rank(relays, 48, 8, 2200);
                AppLog.i("session", "reachable/rankable candidates=" + ranked.size());
                if (ranked.isEmpty()) throw new IllegalStateException("нет доступных кандидатов");

                int preview = Math.min(5, ranked.size());
                for (int i = 0; i < preview; i++) {
                    RelayProbe.Result rr = ranked.get(i);
                    AppLog.i("candidate", "#" + (i + 1) + " " + rr.relay.countryShort + " " + rr.relay.ip
                            + " tcp=" + rr.liveTcp + " connectMs=" + rr.connectMs + " score=" + rr.relay.score);
                }

                Relay best = ranked.get(0).relay;
                OpenVpnProfileUtil.Endpoint endpoint = OpenVpnProfileUtil.endpoint(best);
                AppLog.i("session", "selected relay=" + best.ip
                        + " country=" + best.countryShort
                        + " protocol=" + (endpoint == null ? "unknown" : (endpoint.tcp ? "tcp" : "udp"))
                        + " port=" + (endpoint == null ? -1 : endpoint.port));

                // The repository does not yet contain a VpnService/OpenVPN transport backend.
                // Make this explicit in logs instead of pretending a connection was attempted.
                AppLog.w("tunnel", "NO TUNNEL BACKEND: relay selection finished, but VpnService/OpenVPN core is not implemented yet");

                runOnUiThread(() -> {
                    toggle.setText("СТАРТ");
                    toggle.setEnabled(true);
                    started = false;
                    if (refreshMonitor != null) refreshMonitor.stop();
                    Toast.makeText(this,
                            "Relay найден: " + best.countryShort + " " + best.ip + ". Туннель пока не реализован. Долгое нажатие — журнал.",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                AppLog.e("session", "relay selection failed", e);
                runOnUiThread(() -> {
                    toggle.setText("СТАРТ");
                    toggle.setEnabled(true);
                    started = false;
                    if (refreshMonitor != null) refreshMonitor.stop();
                    Toast.makeText(this, "Ошибка: " + e.getMessage() + ". Долгое нажатие — журнал.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void stopSession() {
        AppLog.i("session", "STOP pressed");
        started = false;
        if (refreshMonitor != null) refreshMonitor.stop();
        toggle.setText("СТАРТ");
        toggle.setEnabled(true);
    }

    private void showLogDialog() {
        final String log = AppLog.read(this);
        TextView text = new TextView(this);
        int pad = dp(12);
        text.setPadding(pad, pad, pad, pad);
        text.setTextSize(11f);
        text.setTextIsSelectable(true);
        text.setText(log);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Yinglong • журнал")
                .setView(scroll)
                .setPositiveButton("КОПИРОВАТЬ", null)
                .setNeutralButton("ОЧИСТИТЬ", null)
                .setNegativeButton("ЗАКРЫТЬ", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Yinglong log", AppLog.read(this)));
                Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                AppLog.clear(this);
                text.setText(AppLog.read(this));
            });
        });
        dialog.show();
    }

    @Override protected void onDestroy() {
        AppLog.i("ui", "MainActivity destroyed");
        if (refreshMonitor != null) refreshMonitor.stop();
        io.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
