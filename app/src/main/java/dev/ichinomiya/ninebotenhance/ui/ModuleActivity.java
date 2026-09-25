package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowInsets;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.privilege.PrivilegeManager;
import java.util.concurrent.TimeUnit;

/**
 * The launcher entry. It exists so the system lists the module in its autostart manager and treats it as an application the
 * user has opened: several ROMs refuse to let Ninebot bind the module service until then. The first open runs the setup
 * guide; afterwards the page is the permission check (one row per grant, its state and what it is used for; a missing one is
 * requested by tapping the row) and, below it, the one action: force-stopping Ninebot so LSPosed injects the module on its
 * next start. Everything else stays inside the Ninebot vehicle page.
 */
public final class ModuleActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private MirrorUi theme;
    private PermissionRows rows;
    private Button stop;
    @Override protected void onCreate(Bundle saved) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        if (!SetupGuideActivity.done(this)) { startActivity(new Intent(this, SetupGuideActivity.class)); finish(); return; }
        PrivilegeManager.initialize(this);
        theme = new MirrorUi(dark);
        int pad = MirrorUi.dp(this, 20), gap = MirrorUi.dp(this, 12);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.surface); root.setForceDarkAllowed(false); root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); return insets; });
        getWindow().setDecorFitsSystemWindows(false);
        TextView title = new TextView(this); title.setText("Ninebot Enhance"); title.setTextSize(22); title.setTextColor(theme.text);
        title.setPadding(0, MirrorUi.dp(this, 6), 0, MirrorUi.dp(this, 6)); root.addView(title);
        TextView version = new TextView(this); version.setText("版本 " + Protocol.VERSION); version.setTextSize(14); version.setTextColor(theme.secondary);
        version.setPadding(0, 0, 0, gap); root.addView(version);
        TextView heading = new TextView(this); heading.setText("权限检测"); heading.setTextSize(16); heading.setTextColor(theme.text);
        heading.setPadding(0, gap / 2, 0, gap / 2); root.addView(heading);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list, new LinearLayout.LayoutParams(-1, -2));
        rows = new PermissionRows(this, theme, list, this::refresh);
        stop = new Button(this); stop.setText("强制退出九号出行"); theme.button(stop, null); stop.setTextSize(14); stop.setMaxLines(1);
        stop.setMinimumHeight(MirrorUi.dp(this, 52)); stop.setOnClickListener(v -> forceStop());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, -2); stopParams.topMargin = gap * 2;
        root.addView(stop, stopParams);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); scroll.setBackgroundColor(theme.surface);
        setContentView(scroll);
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onPause() { super.onPause(); if (rows != null) rows.stop(); }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] grants) { super.onRequestPermissionsResult(code, names, grants); refresh(); }
    private void refresh() { if (rows != null) rows.fill(true); }
    // ---------------------------------------------------------------- force stop
    /** Shizuku / Sui first, then su; without either the system's own page for Ninebot is the place to stop it. */
    private void forceStop() {
        if (getPackageManager().getLaunchIntentForPackage(Protocol.TARGET) == null) { Toast.makeText(this, "未安装九号出行", Toast.LENGTH_SHORT).show(); return; }
        stop.setEnabled(false);
        new Thread(() -> {
            boolean done = false;
            try { if (PrivilegeManager.status(this).getBoolean("privilege_granted")) done = PrivilegeManager.forceStopTarget(this) == 0; } catch (Exception ignored) {}
            if (!done) done = stopWithSu();
            boolean stopped = done;
            main.post(() -> {
                stop.setEnabled(true);
                if (stopped) { Toast.makeText(this, "已强制退出九号出行", Toast.LENGTH_SHORT).show(); return; }
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + Protocol.TARGET)));
                    Toast.makeText(this, "请在页面里点「强制停止」", Toast.LENGTH_LONG).show();
                } catch (RuntimeException e) { ErrorDialog.show(this, null, "无法强制退出九号出行", dev.ichinomiya.ninebotenhance.ipc.Ipc.error(e)); }
            });
        }, "Enhance-ForceStop").start();
    }
    private static boolean stopWithSu() {
        try {
            Process process = new ProcessBuilder("su", "-c", "am force-stop " + Protocol.TARGET).redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroy(); return false; }
            return process.exitValue() == 0;
        } catch (Exception e) { return false; }
    }
}
