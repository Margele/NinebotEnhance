package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;

/**
 * The launcher entry. It exists so the system lists the module in its autostart manager and treats it as an application the
 * user has opened: several ROMs refuse to let Ninebot bind the module service until then. It offers the two system pages that
 * matter and a shortcut back to Ninebot; everything else stays inside the Ninebot vehicle page.
 */
public final class ModuleActivity extends Activity {
    @Override protected void onCreate(Bundle saved) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        MirrorUi theme = new MirrorUi(dark);
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
        root.addView(button(theme, "自启动设置", v -> AutostartPages.open(this, null)), params(gap));
        root.addView(button(theme, "通知使用权", v -> AutostartPages.openNotificationAccess(this)), params(gap));
        root.addView(button(theme, "打开九号出行", v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(Protocol.TARGET);
            if (launch != null) { try { startActivity(launch); } catch (RuntimeException ignored) {} }
            else Toast.makeText(this, "未安装九号出行", Toast.LENGTH_SHORT).show();
        }), params(gap));
        root.addView(button(theme, "关闭", v -> finish()), params(gap));
        ScrollView scroll = new ScrollView(this); scroll.addView(root); scroll.setBackgroundColor(theme.surface);
        setContentView(scroll);
    }
    private Button button(MirrorUi theme, String text, android.view.View.OnClickListener listener) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); theme.button(view, null); view.setTextSize(14);
        view.setMaxLines(1); view.setMinimumHeight(MirrorUi.dp(this, 52)); view.setOnClickListener(listener); return view;
    }
    private LinearLayout.LayoutParams params(int gap) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = gap; return p; }
}
