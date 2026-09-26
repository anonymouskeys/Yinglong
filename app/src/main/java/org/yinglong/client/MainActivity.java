package org.yinglong.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.yinglong.client.diag.AppLog;
import org.yinglong.client.net.VpnSessionManager;

/** One-button UI + a compact live status line. Long-press START/STOP opens diagnostics. */
public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 1001;

    private Button toggle;
    private TextView status;
    private VpnSessionManager manager;
    private VpnSessionManager.State lastUiState = VpnSessionManager.State.IDLE;

    private final VpnSessionManager.Listener sessionListener = (state, detail) -> runOnUiThread(() -> {
        lastUiState = state;
        boolean active = state == VpnSessionManager.State.SEARCHING
                || state == VpnSessionManager.State.CONNECTING
                || state == VpnSessionManager.State.CONNECTED
                || state == VpnSessionManager.State.STOPPING;
        toggle.setText(active ? "СТОП" : "СТАРТ");
        toggle.setEnabled(state != VpnSessionManager.State.STOPPING);
        status.setText(renderStatus(state, detail));
        AppLog.i("ui-state", state + " " + (detail == null ? "" : detail));

        if (state == VpnSessionManager.State.CONNECTED) {
            Toast.makeText(this, "VPN подключён", Toast.LENGTH_SHORT).show();
        } else if (state == VpnSessionManager.State.ERROR) {
            Toast.makeText(this, "VPN: " + detail + ". Открываю журнал.", Toast.LENGTH_LONG).show();
            toggle.postDelayed(this::showLogDialog, 200L);
        }
    });

    @Override protected void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        AppLog.init(this);
        AppLog.i("ui", "MainActivity created; v0.9.0 OpenVPN3 Core engine");
        manager = VpnSessionManager.get(this);
        setContentView(buildUi());
    }

    @Override protected void onStart() {
        super.onStart();
        AppLog.i("ui", "MainActivity onStart");
        manager.addListener(sessionListener);
    }

    @Override protected void onResume() {
        super.onResume();
        AppLog.i("ui", "MainActivity onResume state=" + manager.state());
    }

    @Override protected void onStop() {
        AppLog.i("ui", "MainActivity onStop");
        manager.removeListener(sessionListener);
        super.onStop();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        toggle = new Button(this);
        toggle.setText("СТАРТ");
        toggle.setTextSize(22f);
        toggle.setAllCaps(false);
        toggle.setOnClickListener(v -> {
            AppLog.i("ui", "button click currentState=" + manager.state() + " running=" + manager.isRunning());
            if (manager.isRunning() || lastUiState == VpnSessionManager.State.CONNECTED
                    || lastUiState == VpnSessionManager.State.CONNECTING
                    || lastUiState == VpnSessionManager.State.SEARCHING) {
                manager.stop();
            } else {
                requestVpnPermissionAndStart();
            }
        });
        toggle.setOnLongClickListener(v -> {
            AppLog.i("ui", "long press -> diagnostics");
            showLogDialog();
            return true;
        });

        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(dp(220), dp(72));
        root.addView(toggle, buttonLp);

        status = new TextView(this);
        status.setText("Готово. Долгое нажатие — журнал.");
        status.setTextSize(13f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(18), dp(8), dp(8));
        status.setMaxLines(4);
        status.setOnClickListener(v -> showLogDialog());
        status.setOnLongClickListener(v -> {
            showLogDialog();
            return true;
        });
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(status, statusLp);

        return root;
    }

    private String renderStatus(VpnSessionManager.State state, String detail) {
        String d = detail == null ? "" : detail.trim();
        switch (state) {
            case SEARCHING:
                return "ПРОВЕРКА RELAY\n" + d + "\nНажми на текст — журнал";
            case CONNECTING:
                return "ПОДКЛЮЧЕНИЕ\n" + d + "\nНажми на текст — журнал";
            case CONNECTED:
                return "ПОДКЛЮЧЕНО\n" + d;
            case STOPPING:
                return "ОСТАНОВКА…";
            case ERROR:
                return "ОШИБКА\n" + d + "\nНажми на текст — журнал";
            case IDLE:
            default:
                return "Готово. Долгое нажатие — журнал.";
        }
    }

    private void requestVpnPermissionAndStart() {
        AppLog.i("ui", "START pressed; VpnService.prepare");
        status.setText("Проверяю разрешение Android VPN…");
        Intent consent;
        try {
            consent = VpnService.prepare(this);
        } catch (Throwable t) {
            AppLog.e("ui", "VpnService.prepare failed", t);
            status.setText("Ошибка Android VPN. Нажми сюда — журнал.");
            Toast.makeText(this, "Ошибка подготовки Android VPN", Toast.LENGTH_LONG).show();
            return;
        }

        if (consent == null) {
            AppLog.i("ui", "VPN consent already granted -> manager.start");
            status.setText("Разрешение уже есть. Запускаю проверку relay…");
            manager.start();
        } else {
            try {
                AppLog.i("ui", "launching Android VPN consent activity");
                status.setText("Жду разрешение Android VPN…");
                startActivityForResult(consent, VPN_PERMISSION_REQUEST);
            } catch (Throwable e) {
                AppLog.e("ui", "unable to show VPN consent", e);
                status.setText("Не удалось открыть запрос VPN. Нажми сюда — журнал.");
                Toast.makeText(this, "Не удалось запросить VPN-разрешение", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        AppLog.i("ui", "onActivityResult request=" + requestCode + " result=" + resultCode);
        if (requestCode != VPN_PERMISSION_REQUEST) return;
        if (resultCode == RESULT_OK) {
            AppLog.i("ui", "Android VPN consent GRANTED -> manager.start");
            status.setText("Разрешение получено. Запускаю проверку relay…");
            manager.start();
        } else {
            AppLog.w("ui", "Android VPN consent DENIED/CANCELLED");
            status.setText("Разрешение VPN отклонено.");
            Toast.makeText(this, "Без разрешения Android VPN туннель не запустить", Toast.LENGTH_LONG).show();
        }
    }

    private void showLogDialog() {
        final TextView text = new TextView(this);
        int pad = dp(12);
        text.setPadding(pad, pad, pad, pad);
        text.setTextSize(10.5f);
        text.setTextIsSelectable(true);
        text.setText(AppLog.read(this));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Yinglong • журнал • " + manager.state())
                .setView(scroll)
                .setPositiveButton("КОПИРОВАТЬ", null)
                .setNeutralButton("ОБНОВИТЬ", null)
                .setNegativeButton("ЗАКРЫТЬ", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String log = AppLog.read(this);
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Yinglong log", log));
                Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                text.setText(AppLog.read(this));
                scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
            });
        });
        dialog.show();
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override protected void onDestroy() {
        AppLog.i("ui", "MainActivity destroyed");
        manager.removeListener(sessionListener);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
