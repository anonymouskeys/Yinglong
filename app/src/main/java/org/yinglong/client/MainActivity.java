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

/** One-button UI. Long-press opens the persistent diagnostic log. */
public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 1001;

    private Button toggle;
    private VpnSessionManager manager;
    private VpnSessionManager.State lastUiState = VpnSessionManager.State.IDLE;

    private final VpnSessionManager.Listener sessionListener = (state, detail) -> runOnUiThread(() -> {
        lastUiState = state;
        // User asked for only START/STOP. All intermediate details go to the log.
        boolean active = state == VpnSessionManager.State.SEARCHING
                || state == VpnSessionManager.State.CONNECTING
                || state == VpnSessionManager.State.CONNECTED
                || state == VpnSessionManager.State.STOPPING;
        toggle.setText(active ? "СТОП" : "СТАРТ");
        toggle.setEnabled(state != VpnSessionManager.State.STOPPING);

        if (state == VpnSessionManager.State.ERROR) {
            Toast.makeText(this, "VPN: " + detail + ". Долгое нажатие — журнал.", Toast.LENGTH_LONG).show();
        }
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppLog.init(this);
        AppLog.i("ui", "MainActivity created; v0.3 tunnel build");
        manager = VpnSessionManager.get(this);
        setContentView(buildUi());
    }

    @Override protected void onStart() {
        super.onStart();
        manager.addListener(sessionListener);
    }

    @Override protected void onStop() {
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
            if (manager.isRunning() || lastUiState == VpnSessionManager.State.CONNECTED
                    || lastUiState == VpnSessionManager.State.CONNECTING
                    || lastUiState == VpnSessionManager.State.SEARCHING) {
                manager.stop();
            } else {
                requestVpnPermissionAndStart();
            }
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

    private void requestVpnPermissionAndStart() {
        AppLog.i("ui", "START pressed; requesting Android VPN consent if needed");
        Intent consent = VpnService.prepare(this);
        if (consent == null) {
            AppLog.i("ui", "VPN consent already granted");
            manager.start();
        } else {
            try {
                startActivityForResult(consent, VPN_PERMISSION_REQUEST);
            } catch (Exception e) {
                AppLog.e("ui", "unable to show VPN consent", e);
                Toast.makeText(this, "Не удалось запросить VPN-разрешение", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VPN_PERMISSION_REQUEST) return;
        if (resultCode == RESULT_OK) {
            AppLog.i("ui", "Android VPN consent granted");
            manager.start();
        } else {
            AppLog.w("ui", "Android VPN consent denied/cancelled");
            Toast.makeText(this, "Без разрешения Android VPN запустить туннель нельзя", Toast.LENGTH_LONG).show();
        }
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
        manager.removeListener(sessionListener);
        // Deliberately do NOT stop the VPN here: the user's START/STOP session owns it.
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
