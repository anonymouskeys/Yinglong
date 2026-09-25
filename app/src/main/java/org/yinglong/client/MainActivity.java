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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.yinglong.client.diag.AppLog;
import org.yinglong.client.net.VpnSessionManager;

/** One-button UI. Long-press START/STOP opens diagnostics. */
public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 1001;

    private Button toggle;
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
        AppLog.i("ui-state", state + " " + (detail == null ? "" : detail));

        if (state == VpnSessionManager.State.CONNECTED) {
            Toast.makeText(this, "VPN подключён", Toast.LENGTH_SHORT).show();
        } else if (state == VpnSessionManager.State.ERROR) {
            Toast.makeText(this, "VPN: " + detail + ". Открываю журнал.", Toast.LENGTH_LONG).show();
            toggle.postDelayed(this::showLogDialog, 250L);
        }
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppLog.init(this);
        AppLog.i("ui", "MainActivity created; v0.3.1 repair build");
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

    private FrameLayout buildUi() {
        FrameLayout root = new FrameLayout(this);
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

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(220), dp(72), Gravity.CENTER);
        root.addView(toggle, lp);
        return root;
    }

    private void requestVpnPermissionAndStart() {
        AppLog.i("ui", "START pressed; VpnService.prepare");
        Intent consent;
        try {
            consent = VpnService.prepare(this);
        } catch (Throwable t) {
            AppLog.e("ui", "VpnService.prepare failed", t);
            Toast.makeText(this, "Ошибка подготовки Android VPN. Долгое нажатие — журнал.", Toast.LENGTH_LONG).show();
            return;
        }

        if (consent == null) {
            AppLog.i("ui", "VPN consent already granted -> manager.start");
            manager.start();
        } else {
            try {
                AppLog.i("ui", "launching Android VPN consent activity");
                startActivityForResult(consent, VPN_PERMISSION_REQUEST);
            } catch (Throwable e) {
                AppLog.e("ui", "unable to show VPN consent", e);
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
            Toast.makeText(this, "Разрешение получено. Ищу relay…", Toast.LENGTH_SHORT).show();
            manager.start();
        } else {
            AppLog.w("ui", "Android VPN consent DENIED/CANCELLED");
            Toast.makeText(this, "Без разрешения Android VPN запустить туннель нельзя", Toast.LENGTH_LONG).show();
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
