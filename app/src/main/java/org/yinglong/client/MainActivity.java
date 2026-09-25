package org.yinglong.client;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.catalog.RelayUpdater;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView list;
    private ProgressBar progress;
    private Button refresh;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        refresh.setOnClickListener(v -> updateNow());
        loadCatalogue();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Yinglong");
        title.setTextSize(30f);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Resilient relay catalogue • VPN Gate seed + live refresh");
        subtitle.setTextSize(14f);
        root.addView(subtitle);

        status = new TextView(this);
        status.setPadding(0, dp(16), 0, dp(8));
        root.addView(status);

        refresh = new Button(this);
        refresh.setText("Обновить каталог сейчас");
        root.addView(refresh);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        list = new TextView(this);
        list.setTextSize(13f);
        list.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, lp);
        return root;
    }

    private void loadCatalogue() {
        status.setText("Читаю локальный каталог…");
        io.execute(() -> {
            try {
                RelayStore store = new RelayStore(this);
                List<Relay> relays = store.read();
                relays.sort(Comparator.comparingLong((Relay r) -> r.score).reversed());
                StringBuilder sb = new StringBuilder();
                int limit = Math.min(relays.size(), 100);
                for (int i = 0; i < limit; i++) {
                    Relay r = relays.get(i);
                    sb.append(String.format(Locale.US,
                            "%3d. %s  %s  %.1f Mbps  ping %d ms  score %d\n",
                            i + 1, r.countryShort, r.ip, r.speedMbps(), r.pingMs, r.score));
                }
                runOnUiThread(() -> {
                    status.setText("Доступно relay: " + relays.size() + " • локальный snapshot защищён от неудачного обновления");
                    list.setText(sb.toString());
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Ошибка каталога: " + e.getMessage()));
            }
        });
    }

    private void updateNow() {
        refresh.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Загружаю свежий список через текущую сеть…");
        io.execute(() -> {
            try {
                int count = new RelayUpdater(this).refresh();
                runOnUiThread(() -> status.setText("Каталог обновлён: " + count + " relay"));
                loadCatalogue();
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Обновление не удалось; оставлен старый рабочий список: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> {
                    refresh.setEnabled(true);
                    progress.setVisibility(View.GONE);
                });
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
