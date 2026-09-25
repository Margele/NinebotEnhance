package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.core.OpenSourceNotice;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.platform.ModuleResources;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import android.content.res.ColorStateList;
import java.io.*;

public final class AboutDialog {
    public static void show(Activity activity, View reference, FrameClient frames) {
        MirrorUi theme = new MirrorUi(activity, reference);
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.addView(DialogContent.text(activity, theme, "Ninebot Enhance", 24));
        TextView version = DialogContent.text(activity, theme, "版本 " + Protocol.VERSION + "\n" + Protocol.MODULE, 13);
        version.setTextColor(theme.secondary); content.addView(version);
        CheckBox debug = new CheckBox(activity); debug.setText("调试模式"); debug.setTextSize(15);
        debug.setTextColor(theme.text);
        debug.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{theme.accent, theme.secondary}));
        debug.setChecked(frames.debugModeEnabled()); debug.setVisibility(frames.debugModeUnlocked() ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams debugParams = new LinearLayout.LayoutParams(-1, -2);
        int pad = MirrorUi.dp(activity, 20); debugParams.setMargins(pad, MirrorUi.dp(activity, 4), pad, MirrorUi.dp(activity, 8));
        debug.setMinHeight(MirrorUi.dp(activity, 48)); content.addView(debug, debugParams);
        debug.setOnCheckedChangeListener((button, checked) -> frames.setDebugMode(checked));
        // Diagnostic register probe lives with the debug switch: once ticked it only writes PROBE lines to the log.
        LinearLayout probeRow = new LinearLayout(activity); probeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        CheckBox probe = new CheckBox(activity); probe.setText("寄存器探测"); probe.setTextSize(15); probe.setTextColor(theme.text);
        probe.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{theme.accent, theme.secondary}));
        probe.setChecked(frames.widgetSettings().enabled(dev.ichinomiya.ninebotenhance.core.WidgetSettings.REGISTER_PROBE)); probe.setMinHeight(MirrorUi.dp(activity, 48));
        probeRow.addView(probe, new LinearLayout.LayoutParams(0, -2, 1));
        Button probeSettings = new Button(activity); probeSettings.setText("设置"); probeSettings.setAllCaps(false); theme.button(probeSettings, null); probeSettings.setTextSize(12);
        probeSettings.setMinHeight(0); probeSettings.setMinimumHeight(0); probeSettings.setMinWidth(0); probeSettings.setMinimumWidth(0); probeSettings.setPadding(MirrorUi.dp(activity, 8), 0, MirrorUi.dp(activity, 8), 0); probeSettings.setGravity(android.view.Gravity.CENTER);
        probeSettings.setOnClickListener(v -> RegisterProbeDialog.show(activity, reference, frames));
        probeRow.addView(probeSettings, new LinearLayout.LayoutParams(MirrorUi.dp(activity, 72), MirrorUi.dp(activity, 32)));
        probeRow.setVisibility(frames.debugModeUnlocked() ? View.VISIBLE : View.GONE); content.addView(probeRow, debugParams);
        probe.setOnCheckedChangeListener((button, checked) -> { frames.saveWidgetSettings(frames.widgetSettings().with(dev.ichinomiya.ninebotenhance.core.WidgetSettings.REGISTER_PROBE, checked)); frames.report("DEBUG register probe " + checked); });
        // Dashboard navigation test: the module's only vehicle write path (command 113 display data), scripted route while a vehicle session runs.
        CheckBox naviTest = new CheckBox(activity); naviTest.setText("巡航导航测试数据"); naviTest.setTextSize(15); naviTest.setTextColor(theme.text);
        naviTest.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{theme.accent, theme.secondary}));
        naviTest.setChecked(frames.naviTest()); naviTest.setMinHeight(MirrorUi.dp(activity, 48));
        naviTest.setVisibility(frames.debugModeUnlocked() ? View.VISIBLE : View.GONE); content.addView(naviTest, debugParams);
        naviTest.setOnCheckedChangeListener((button, checked) -> frames.saveNaviTest(checked));
        version.setOnClickListener(v -> { if (frames.debugVersionTap()) { debug.setVisibility(View.VISIBLE); probeRow.setVisibility(View.VISIBLE); naviTest.setVisibility(View.VISIBLE); } });
        content.addView(DialogContent.text(activity, theme, "为九号出行添加应用投屏、系统录屏、虚拟屏预览与输入、会话统计。", 15));
        TextView repository = DialogContent.text(activity, theme, OpenSourceNotice.REPOSITORY, 13); repository.setTextColor(theme.accent);
        repository.setOnClickListener(v -> {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(OpenSourceNotice.REPOSITORY))); }
            catch (RuntimeException e) { Toast.makeText(activity, OpenSourceNotice.REPOSITORY, Toast.LENGTH_LONG).show(); }
        });
        content.addView(repository);
        Button update = new Button(activity); update.setText("检查更新"); theme.button(update, null);
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(-1, -2);
        updateParams.setMargins(pad, MirrorUi.dp(activity, 8), pad, MirrorUi.dp(activity, 8));
        content.addView(update, updateParams);
        update.setOnClickListener(v -> { update.setEnabled(false); update.setText("检查中"); checkUpdate(activity, theme, frames, update, System.currentTimeMillis(), true, 0); });
        TextView authors = DialogContent.text(activity, theme, "作者 " + OpenSourceNotice.AUTHORS, 13); authors.setTextColor(theme.secondary); content.addView(authors);
        TextView copyright = DialogContent.text(activity, theme, "Copyright 2026 Ninebot Enhance contributors\nApache License 2.0\n本项目不是九号官方产品。", 13);
        copyright.setTextColor(theme.secondary); content.addView(copyright);
        resourceButton(activity, theme, content, "开源许可证", "META-INF/licenses/NinebotEnhance-Apache-2.0.txt");
        resourceButton(activity, theme, content, "第三方声明与致谢", "META-INF/NOTICE.txt");
        resourceButton(activity, theme, content, "Shizuku API 许可证", "META-INF/licenses/Shizuku-MIT.txt");
        resourceButton(activity, theme, content, "scrcpy 许可证", "META-INF/licenses/Apache-2.0.txt");
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        DialogContent.show(activity, theme, DialogContent.create(activity, theme, "关于", scroll));
    }
    /**
     * A forced lookup runs in the module process; the answer arrives on later snapshots, so ask again each second until one is
     * dated after the click, a failure is, or ten seconds pass.
     */
    private static void checkUpdate(Activity activity, MirrorUi theme, FrameClient frames, Button button, long started, boolean refresh, int attempt) {
        frames.checkUpdate(refresh, result -> {
            boolean fresh = result.getLong("checked_at") >= started, failed = result.getLong("failed_at") >= started;
            if (!fresh && !failed && attempt < 10) { button.postDelayed(() -> checkUpdate(activity, theme, frames, button, started, false, attempt + 1), 1000); return; }
            button.setEnabled(true); button.setText("检查更新");
            if (!fresh) { Toast.makeText(activity, "检查更新失败", Toast.LENGTH_SHORT).show(); return; }
            if (result.getBoolean("newer")) UpdateDialog.show(activity, theme, result.getString("latest", ""), result.getString("url", dev.ichinomiya.ninebotenhance.core.UpdateCheck.RELEASES_URL));
            else Toast.makeText(activity, "已是最新版本 " + Protocol.VERSION, Toast.LENGTH_SHORT).show();
        }, error -> { button.setEnabled(true); button.setText("检查更新"); Toast.makeText(activity, "检查更新失败", Toast.LENGTH_SHORT).show(); });
    }
    private static void resourceButton(Activity activity, MirrorUi theme, LinearLayout content, String label, String resource) {
        Button button = new Button(activity); button.setText(label); theme.button(button, null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(MirrorUi.dp(activity, 20), MirrorUi.dp(activity, 8), MirrorUi.dp(activity, 20), MirrorUi.dp(activity, 8));
        content.addView(button, params);
        button.setOnClickListener(v -> {
            try { DialogContent.document(activity, theme, label, ModuleResources.text(resource)); }
            catch (IOException | RuntimeException e) { Toast.makeText(activity, "无法读取：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
    }
    private AboutDialog() {}
}
