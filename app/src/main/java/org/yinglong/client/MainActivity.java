package org.yinglong.client;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.net.RelayProbe;
import org.yinglong.client.net.SessionRefreshMonitor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Intentionally minimal UI: one START/STOP control.
 *
 * v0.2 prepares/ranks relay candidates and scopes catalogue maintenance to an
 * explicitly started session. The actual embedded OpenVPN transport is wired
 * in the next tunnel-core step; we do not fake a VPN connection here.
 */
public final class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Button toggle;
    private volatile boolean started;
    private SessionRefreshMonitor refreshMonitor;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
            io.execute(() -> {
                try {
                    int n = new RelayStore(this).read().size();
                    runOnUiThread(() -> Toast.makeText(this, "Relay в локальном пуле: " + n, Toast.LENGTH_SHORT).show());
                } catch (Exception ignored) {}
            });
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

        // Maintenance is session-scoped: it will run once only after Android
        // reports an actual VPN transport, then unregister itself.
        refreshMonitor = new SessionRefreshMonitor(this);
        refreshMonitor.start();

        io.execute(() -> {
            try {
                List<Relay> relays = new RelayStore(this).read();
                List<RelayProbe.Result> ranked = RelayProbe.rank(relays, 48, 8, 2200);
                if (ranked.isEmpty()) throw new IllegalStateException("нет доступных кандидатов");
                Relay best = ranked.get(0).relay;
                runOnUiThread(() -> {
                    // Do not claim CONNECTED until a real tunnel backend confirms it.
                    toggle.setText("СТАРТ");
                    toggle.setEnabled(true);
                    started = false;
                    if (refreshMonitor != null) refreshMonitor.stop();
                    Toast.makeText(this,
                            "Лучший relay найден: " + best.countryShort + " " + best.ip + ". Подключаем OpenVPN-core следующим шагом.",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toggle.setText("СТАРТ");
                    toggle.setEnabled(true);
                    started = false;
                    if (refreshMonitor != null) refreshMonitor.stop();
                    Toast.makeText(this, "Не удалось подобрать relay: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void stopSession() {
        started = false;
        if (refreshMonitor != null) refreshMonitor.stop();
        toggle.setText("СТАРТ");
        toggle.setEnabled(true);
        // Tunnel backend will also be disconnected here once embedded.
    }

    @Override protected void onDestroy() {
        if (refreshMonitor != null) refreshMonitor.stop();
        io.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
