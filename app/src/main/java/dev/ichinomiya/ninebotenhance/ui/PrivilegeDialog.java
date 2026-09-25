package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.PictureSource;
import dev.ichinomiya.ninebotenhance.core.PrivilegeMode;
import java.util.function.Consumer;

/**
 * The picture source comes first; the privileged backend, its state, one request button and the keep-root switch appear only
 * for the virtual display. The state is read from the module every 1.5 s; a tap during a read is queued, never dropped.
 */
public final class PrivilegeDialog {
    public static void show(Activity activity, FrameClient frames, View reference) { show(activity, frames, reference, value -> {}); }
    public static void show(Activity activity, FrameClient frames, View reference, Consumer<PictureSource> saved) {
        MirrorUi theme = new MirrorUi(activity, reference);
        int pad = MirrorUi.dp(activity, 20), gap = MirrorUi.dp(activity, 12);
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, gap / 2, pad, pad); content.setBackgroundColor(theme.surface);
        content.addView(caption(activity, theme, "画面提供方式"));
        RadioGroup sources = new RadioGroup(activity); sources.setOrientation(RadioGroup.VERTICAL);
        RadioButton cast = radio(activity, theme, sources, PictureSource.CAST.label()), virtual = radio(activity, theme, sources, PictureSource.VIRTUAL.label()),
                draw = radio(activity, theme, sources, PictureSource.DRAW.label());
        content.addView(sources, new LinearLayout.LayoutParams(-1, -2));
        boolean virtualAllowed = PictureSource.VIRTUAL.allowed(Build.VERSION.SDK_INT);
        virtual.setAlpha(virtualAllowed ? 1f : 0.5f);
        TextView status = new TextView(activity); status.setTextColor(theme.secondary); status.setTextSize(13);
        status.setLineSpacing(MirrorUi.dp(activity, 3), 1);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2); statusParams.topMargin = gap;
        content.addView(status, statusParams);
        // Everything below exists for the virtual display only.
        LinearLayout privileged = new LinearLayout(activity); privileged.setOrientation(LinearLayout.VERTICAL);
        privileged.addView(caption(activity, theme, "授权"));
        RadioGroup backends = new RadioGroup(activity); backends.setOrientation(RadioGroup.VERTICAL);
        RadioButton root = radio(activity, theme, backends, "Root"), shizuku = radio(activity, theme, backends, "Shizuku / Sui");
        privileged.addView(backends, new LinearLayout.LayoutParams(-1, -2));
        Button grant = new Button(activity); grant.setText("申请 / 验证 Root 权限"); theme.button(grant, null);
        grant.setMinHeight(MirrorUi.dp(activity, 48)); grant.setEnabled(false);
        LinearLayout.LayoutParams grantParams = new LinearLayout.LayoutParams(-1, -2); grantParams.topMargin = gap;
        privileged.addView(grant, grantParams);
        CheckBox keepRoot = new CheckBox(activity); keepRoot.setText("不降权"); keepRoot.setTextColor(theme.text); keepRoot.setTextSize(15);
        keepRoot.setButtonTintList(ColorStateList.valueOf(theme.accent)); keepRoot.setEnabled(false);
        keepRoot.setPadding(0, MirrorUi.dp(activity, 8), 0, MirrorUi.dp(activity, 4));
        LinearLayout.LayoutParams keepParams = new LinearLayout.LayoutParams(-1, -2); keepParams.topMargin = gap / 2;
        privileged.addView(keepRoot, keepParams);
        TextView keepNote = new TextView(activity); keepNote.setTextColor(theme.secondary); keepNote.setTextSize(13);
        keepNote.setLineSpacing(MirrorUi.dp(activity, 3), 1);
        keepNote.setText("辅助进程默认用 su 2000 降到 shell 身份运行，副屏触摸靠系统给 shell 的注入权限；HyperOS 等系统不给这项权限时画面能投、副屏点不动。"
                + "勾选后辅助进程保持 root 身份（uid 0）运行，不再受这项限制。只对 Root 和以 Root 运行的 Shizuku / Sui 生效，ADB 方式启动的 Shizuku 本身就是 shell，勾选也无法提升。"
                + "改动后下次开始投屏会重新验证 Root。");
        privileged.addView(keepNote, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams privilegedParams = new LinearLayout.LayoutParams(-1, -2); privilegedParams.topMargin = gap;
        content.addView(privileged, privilegedParams);

        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        TextView title = new TextView(activity); title.setText("授权方式");
        title.setTextColor(theme.text); title.setTextSize(20); title.setPadding(pad, pad, pad, gap / 2);
        AlertDialog dialog = new AlertDialog.Builder(activity).setCustomTitle(title).setView(scroll)
                .setPositiveButton("保存", null).setNegativeButton("关闭", null).create();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 24, false));
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setTextColor(theme.accent); save.setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(theme.accent);
        Handler main = new Handler(Looper.getMainLooper());
        Bundle[] latest = {null};
        int[] previousSource = {cast.getId()};
        java.util.function.Supplier<PictureSource> selectedSource = () -> virtual.isChecked() ? PictureSource.VIRTUAL : draw.isChecked() ? PictureSource.DRAW : PictureSource.CAST;
        Runnable showMode = () -> {
            PictureSource source = selectedSource.get();
            Bundle r = latest[0]; boolean active = r != null && r.getBoolean("active");
            privileged.setVisibility(source.virtual() ? View.VISIBLE : View.GONE);
            if (!source.virtual()) {
                status.setVisibility(source.captures() ? View.VISIBLE : View.GONE);
                status.setText(source.captures() ? "开始时在系统窗口选择要投的应用或整个屏幕。" + (active ? "\n请先结束投屏再修改。" : "") : "");
                return;
            }
            status.setVisibility(View.VISIBLE);
            if (r == null) { status.setText("正在读取授权状态…"); grant.setEnabled(false); return; }
            if (root.isChecked()) {
                boolean ready = r.getBoolean("root_ready"), pending = r.getBoolean("root_pending");
                status.setText(r.getString("root_status", "") + (active ? "\n请先结束投屏再修改。" : ""));
                grant.setText(ready ? "Root 权限可用" : pending ? "正在检查 Root 权限…" : "申请 / 验证 Root 权限");
                grant.setEnabled(!active && !ready && !pending);
            } else {
                boolean granted = r.getBoolean("privilege_granted"), pending = r.getBoolean("privilege_pending");
                status.setText(r.getString("privilege_status", "") + (active ? "\n请先结束投屏再修改。" : ""));
                grant.setText(granted ? "已授权" : pending ? "等待系统授权…" : "授权 Shizuku / Sui");
                grant.setEnabled(r.getBoolean("privilege_can_request"));
            }
        };
        sources.setOnCheckedChangeListener((group, id) -> {
            if (id == virtual.getId() && !virtualAllowed) {
                Toast.makeText(activity, "仅限 Android 14+ 可用", Toast.LENGTH_SHORT).show();
                group.check(previousSource[0]); return;
            }
            previousSource[0] = id; showMode.run();
        });
        backends.setOnCheckedChangeListener((group, id) -> showMode.run());

        class Requests implements Runnable {
            boolean busy, selected;
            Bundle queued;
            boolean queuedClose;
            @Override public void run() { query(new Bundle(), false); }
            void controls(boolean enabled) {
                cast.setEnabled(enabled); virtual.setEnabled(enabled); draw.setEnabled(enabled); root.setEnabled(enabled); shizuku.setEnabled(enabled);
                keepRoot.setEnabled(enabled); save.setEnabled(enabled); if (!enabled) grant.setEnabled(false);
            }
            void next() {
                if (queued == null) { main.postDelayed(this, 1500); return; }
                Bundle args = queued; boolean close = queuedClose; queued = null;
                query(args, close);
            }
            void query(Bundle args, boolean closeAfter) {
                if (!dialog.isShowing()) return;
                boolean mutation = args.getBoolean("save") || args.getBoolean("request_permission") || args.getBoolean("request_root");
                if (busy) {
                    // A tap during an automatic read must not be silently dropped or sent twice.
                    if (mutation && queued == null) { queued = new Bundle(args); queuedClose = closeAfter; controls(false); }
                    return;
                }
                busy = true; main.removeCallbacks(this);
                // Automatic status reads preserve both focus and the user's unsaved selection.
                if (mutation || !selected) controls(false);
                frames.privilege(args, result -> {
                    busy = false;
                    if (closeAfter) {
                        if (dialog.isShowing()) dialog.dismiss();
                        saved.accept(PictureSource.read(result.getString(PictureSource.KEY), PictureSource.CAST)); return;
                    }
                    if (!dialog.isShowing()) return;
                    if (!selected) {
                        PictureSource source = PictureSource.read(result.getString(PictureSource.KEY), PictureSource.CAST);
                        RadioButton chosen = source.virtual() ? virtual : source.draws() ? draw : cast;
                        previousSource[0] = chosen.getId(); chosen.setChecked(true);
                        (PrivilegeMode.read(result.getString("privilege_mode"), PrivilegeMode.ROOT) == PrivilegeMode.SHIZUKU ? shizuku : root).setChecked(true);
                        keepRoot.setChecked(result.getBoolean("keep_root"));
                        selected = true;
                    }
                    latest[0] = result;
                    boolean active = result.getBoolean("active");
                    controls(!active);
                    showMode.run();
                    next();
                }, message -> {
                    busy = false; if (!dialog.isShowing()) return;
                    status.setVisibility(View.VISIBLE); status.setText(message); controls(false);
                    next();
                });
            }
        }
        Requests requests = new Requests();
        grant.setOnClickListener(v -> {
            Bundle args = new Bundle(); args.putBoolean(root.isChecked() ? "request_root" : "request_permission", true); requests.query(args, false);
        });
        save.setOnClickListener(v -> {
            PictureSource source = selectedSource.get();
            Bundle args = new Bundle(); args.putBoolean("save", true); args.putString(PictureSource.KEY, source.name());
            if (source.virtual()) args.putString("privilege_mode", (root.isChecked() ? PrivilegeMode.ROOT : PrivilegeMode.SHIZUKU).name());
            args.putBoolean("keep_root", keepRoot.isChecked());
            requests.query(args, true);
        });
        dialog.setOnDismissListener(v -> main.removeCallbacks(requests));
        showMode.run();
        requests.run();
    }
    private static TextView caption(Activity activity, MirrorUi theme, String text) {
        TextView view = new TextView(activity); view.setText(text); view.setTextColor(theme.secondary); view.setTextSize(13);
        view.setPadding(0, MirrorUi.dp(activity, 6), 0, MirrorUi.dp(activity, 2)); return view;
    }
    private static RadioButton radio(Activity activity, MirrorUi theme, RadioGroup parent, String label) {
        RadioButton button = new RadioButton(activity); button.setId(View.generateViewId()); button.setText(label); button.setTextColor(theme.text); button.setTextSize(15);
        button.setButtonTintList(ColorStateList.valueOf(theme.accent)); button.setPadding(0, MirrorUi.dp(activity, 6), 0, MirrorUi.dp(activity, 6));
        parent.addView(button, new RadioGroup.LayoutParams(-1, -2)); return button;
    }
    private PrivilegeDialog() {}
}
