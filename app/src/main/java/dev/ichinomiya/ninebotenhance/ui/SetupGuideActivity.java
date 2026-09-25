package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.bms.BmsController;
import dev.ichinomiya.ninebotenhance.core.BmsSettings;
import dev.ichinomiya.ninebotenhance.core.LampSettings;
import dev.ichinomiya.ninebotenhance.core.PictureSource;
import dev.ichinomiya.ninebotenhance.core.PrivilegeMode;
import dev.ichinomiya.ninebotenhance.lamp.LampController;
import dev.ichinomiya.ninebotenhance.privilege.PrivilegeManager;
import dev.ichinomiya.ninebotenhance.privilege.RootAuthorization;
import dev.ichinomiya.ninebotenhance.service.FrameBridgeService;

/**
 * The first-run guide, in the module process: the picture source (and, for the virtual display, the privileged backend), the
 * permission check, then whether there is a lamp controller and a BMS board to bind, and for a bound board which source the
 * voltage and power come from. Each step saves as it is left; skipping or finishing marks the guide done.
 */
public final class SetupGuideActivity extends Activity {
    static final String DONE = "guide_done";
    static boolean done(Context context) {
        try { return context.getSharedPreferences(FrameBridgeService.STATUS_PREFERENCES, Context.MODE_PRIVATE).getBoolean(DONE, false); }
        catch (RuntimeException e) { return true; }
    }
    private static final int STEP_SOURCE = 0, STEP_PERMISSIONS = 1, STEP_LAMP = 2, STEP_BMS = 3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MirrorUi theme; private boolean dark; private int step = -1, pad, gap;
    private LinearLayout body; private TextView heading; private Button back, next;
    private PictureSource source; private PrivilegeMode mode; private boolean keepRoot;
    private PermissionRows rows; private RadioButton dashboardSource, bmsSource;
    private Runnable pollPrivilege;
    @Override protected void onCreate(Bundle saved) {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        PrivilegeManager.initialize(this);
        theme = new MirrorUi(dark); pad = MirrorUi.dp(this, 20); gap = MirrorUi.dp(this, 12);
        source = PrivilegeManager.source(this); mode = PrivilegeManager.mode(this); keepRoot = PrivilegeManager.keepRoot(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.surface); root.setForceDarkAllowed(false); root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); return insets; });
        getWindow().setDecorFitsSystemWindows(false);
        TextView title = new TextView(this); title.setText("Ninebot Enhance"); title.setTextSize(22); title.setTextColor(theme.text);
        title.setPadding(0, MirrorUi.dp(this, 6), 0, MirrorUi.dp(this, 6)); root.addView(title);
        heading = new TextView(this); heading.setTextSize(16); heading.setTextColor(theme.text); heading.setPadding(0, gap / 2, 0, gap / 2); root.addView(heading);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); root.addView(body, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = new LinearLayout(this); buttons.setGravity(Gravity.CENTER_VERTICAL);
        Button skip = button("跳过"); skip.setOnClickListener(v -> finishGuide());
        back = button("上一步"); back.setOnClickListener(v -> show(step - 1));
        next = button("下一步"); next.setOnClickListener(v -> advance());
        for (Button b : new Button[]{skip, back, next}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); if (buttons.getChildCount() > 0) p.setMarginStart(gap / 2); buttons.addView(b, p);
        }
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2); buttonParams.topMargin = gap * 2; root.addView(buttons, buttonParams);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); scroll.setBackgroundColor(theme.surface);
        setContentView(scroll);
        show(STEP_SOURCE);
    }
    @Override protected void onResume() { super.onResume(); if (step >= STEP_PERMISSIONS) show(step); }
    @Override protected void onPause() { super.onPause(); main.removeCallbacksAndMessages(null); if (rows != null) rows.stop(); }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] grants) {
        super.onRequestPermissionsResult(code, names, grants); if (rows != null && step == STEP_PERMISSIONS) rows.fill(false);
    }
    // ---------------------------------------------------------------- steps
    private void show(int index) {
        main.removeCallbacksAndMessages(null); if (rows != null) rows.stop();
        step = Math.max(STEP_SOURCE, Math.min(STEP_BMS, index)); body.removeAllViews();
        back.setVisibility(step == STEP_SOURCE ? View.INVISIBLE : View.VISIBLE);
        next.setText(step == STEP_BMS ? "完成" : "下一步");
        switch (step) {
            case STEP_SOURCE: sourceStep(); break;
            case STEP_PERMISSIONS: permissionStep(); break;
            case STEP_LAMP: lampStep(); break;
            default: bmsStep();
        }
    }
    private void advance() {
        if (step == STEP_SOURCE) {
            try { PrivilegeManager.save(this, source.name(), mode.name()); PrivilegeManager.saveKeepRoot(this, keepRoot); }
            catch (RuntimeException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        }
        if (step == STEP_BMS) {
            BmsController bms = BmsController.get(this); BmsSettings current = bms.settings();
            if (current.bound() && bmsSource != null && current.preferBms() != bmsSource.isChecked()) bms.save(current.withPreferBms(bmsSource.isChecked()));
            finishGuide(); return;
        }
        show(step + 1);
    }
    private void finishGuide() {
        try { getSharedPreferences(FrameBridgeService.STATUS_PREFERENCES, MODE_PRIVATE).edit().putBoolean(DONE, true).apply(); } catch (RuntimeException ignored) {}
        startActivity(new Intent(this, ModuleActivity.class)); finish();
    }
    /** Step one: the picture source; the virtual display adds its backend, one request button, its state and the keep-root switch. */
    private void sourceStep() {
        heading.setText("画面提供方式");
        RadioGroup sources = new RadioGroup(this); sources.setOrientation(RadioGroup.VERTICAL);
        RadioButton cast = radio(sources, PictureSource.CAST.label()), virtual = radio(sources, PictureSource.VIRTUAL.label()), draw = radio(sources, PictureSource.DRAW.label());
        boolean virtualAllowed = PictureSource.VIRTUAL.allowed(Build.VERSION.SDK_INT); virtual.setAlpha(virtualAllowed ? 1f : 0.5f);
        body.addView(sources, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout privileged = new LinearLayout(this); privileged.setOrientation(LinearLayout.VERTICAL);
        privileged.addView(caption("授权"));
        RadioGroup backends = new RadioGroup(this); backends.setOrientation(RadioGroup.VERTICAL);
        RadioButton root = radio(backends, "Root"), shizuku = radio(backends, "Shizuku / Sui");
        privileged.addView(backends, new LinearLayout.LayoutParams(-1, -2));
        TextView status = new TextView(this); status.setTextColor(theme.secondary); status.setTextSize(13); status.setLineSpacing(MirrorUi.dp(this, 3), 1);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2); statusParams.topMargin = gap / 2; privileged.addView(status, statusParams);
        Button grant = button("申请 / 验证 Root 权限"); LinearLayout.LayoutParams grantParams = new LinearLayout.LayoutParams(-1, -2); grantParams.topMargin = gap; privileged.addView(grant, grantParams);
        CheckBox keep = new CheckBox(this); keep.setText("不降权"); keep.setTextColor(theme.text); keep.setTextSize(15); keep.setButtonTintList(ColorStateList.valueOf(theme.accent));
        keep.setPadding(0, MirrorUi.dp(this, 8), 0, MirrorUi.dp(this, 4)); keep.setChecked(keepRoot); privileged.addView(keep, new LinearLayout.LayoutParams(-1, -2));
        TextView keepNote = new TextView(this); keepNote.setTextColor(theme.secondary); keepNote.setTextSize(13); keepNote.setLineSpacing(MirrorUi.dp(this, 3), 1);
        keepNote.setText("辅助进程默认用 su 2000 降到 shell 身份运行，副屏触摸靠系统给 shell 的注入权限；HyperOS 等系统不给这项权限时画面能投、副屏点不动。"
                + "勾选后辅助进程保持 root 身份（uid 0）运行，不再受这项限制。只对 Root 和以 Root 运行的 Shizuku / Sui 生效，ADB 方式启动的 Shizuku 本身就是 shell，勾选也无法提升。");
        privileged.addView(keepNote, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams privilegedParams = new LinearLayout.LayoutParams(-1, -2); privilegedParams.topMargin = gap; body.addView(privileged, privilegedParams);
        (source.virtual() ? virtual : source.draws() ? draw : cast).setChecked(true);
        (mode == PrivilegeMode.SHIZUKU ? shizuku : root).setChecked(true);
        Runnable refresh = () -> {
            privileged.setVisibility(source.virtual() ? View.VISIBLE : View.GONE);
            if (!source.virtual()) return;
            Bundle r = PrivilegeManager.status(this);
            if (mode == PrivilegeMode.ROOT) {
                boolean ready = r.getBoolean("root_ready"), pending = r.getBoolean("root_pending");
                status.setText(r.getString("root_status", "")); grant.setText(ready ? "Root 权限可用" : pending ? "正在检查 Root 权限…" : "申请 / 验证 Root 权限"); grant.setEnabled(!ready && !pending);
            } else {
                boolean granted = r.getBoolean("privilege_granted"), pending = r.getBoolean("privilege_pending");
                status.setText(r.getString("privilege_status", "")); grant.setText(granted ? "已授权" : pending ? "等待系统授权…" : "授权 Shizuku / Sui"); grant.setEnabled(r.getBoolean("privilege_can_request"));
            }
        };
        pollPrivilege = new Runnable() { @Override public void run() { if (step != STEP_SOURCE) return; refresh.run(); main.postDelayed(this, 1500); } };
        sources.setOnCheckedChangeListener((group, id) -> {
            if (id == virtual.getId() && !virtualAllowed) {
                Toast.makeText(this, "仅限 Android 14+ 可用", Toast.LENGTH_SHORT).show();
                group.check((source.draws() ? draw : cast).getId()); return;
            }
            source = id == virtual.getId() ? PictureSource.VIRTUAL : id == draw.getId() ? PictureSource.DRAW : PictureSource.CAST; refresh.run();
        });
        backends.setOnCheckedChangeListener((group, id) -> { mode = id == shizuku.getId() ? PrivilegeMode.SHIZUKU : PrivilegeMode.ROOT; refresh.run(); });
        keep.setOnCheckedChangeListener((b, checked) -> keepRoot = checked);
        grant.setOnClickListener(v -> {
            try {
                if (mode == PrivilegeMode.SHIZUKU) PrivilegeManager.requestPermission(); else RootAuthorization.request(this);
            } catch (RuntimeException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
            main.postDelayed(refresh, 1500);
        });
        pollPrivilege.run();
    }
    private void permissionStep() {
        heading.setText("权限");
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); body.addView(list, new LinearLayout.LayoutParams(-1, -2));
        rows = new PermissionRows(this, theme, list, () -> { if (step == STEP_PERMISSIONS && rows != null) rows.fill(false); });
        rows.fill(false);
    }
    private void lampStep() {
        heading.setText("大灯");
        body.addView(question("有大灯控制器吗？"));
        LampSettings lamp = LampController.get(this).settings();
        body.addView(state(lamp.bound() ? "已绑定 " + lamp.mac() : "未绑定"));
        choices("有，去连接", () -> startActivity(new Intent(this, LampSettingsActivity.class).putExtra("dark", dark)), "没有", () -> show(STEP_BMS));
    }
    private void bmsStep() {
        heading.setText("BMS");
        body.addView(question("有 BMS 保护板吗？"));
        BmsSettings bms = BmsController.get(this).settings();
        body.addView(state(bms.bound() ? "已绑定 " + bms.label() : "未绑定"));
        choices("有，去连接", () -> startActivity(new Intent(this, BmsSettingsActivity.class).putExtra("dark", dark)), "没有", this::finishGuide);
        bmsSource = null;
        if (!bms.bound()) return;
        body.addView(caption("电压 / 功率来源"));
        RadioGroup sources = new RadioGroup(this); sources.setOrientation(RadioGroup.VERTICAL);
        dashboardSource = radio(sources, "仪表"); bmsSource = radio(sources, "BMS");
        (bms.preferBms() ? bmsSource : dashboardSource).setChecked(true);
        body.addView(sources, new LinearLayout.LayoutParams(-1, -2));
    }
    // ---------------------------------------------------------------- views
    private void choices(String yes, Runnable onYes, String no, Runnable onNo) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        Button first = button(yes); first.setOnClickListener(v -> onYes.run()); Button second = button(no); second.setOnClickListener(v -> onNo.run());
        row.addView(first, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMarginStart(gap / 2); row.addView(second, p);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2); rowParams.topMargin = gap; body.addView(row, rowParams);
    }
    private TextView question(String text) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(16); view.setTextColor(theme.text); view.setPadding(0, gap / 2, 0, gap / 2); return view;
    }
    private TextView state(String text) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(14); view.setTextColor(theme.secondary); return view;
    }
    private TextView caption(String text) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(13); view.setTextColor(theme.secondary); view.setPadding(0, gap, 0, MirrorUi.dp(this, 2)); return view;
    }
    private Button button(String text) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); theme.button(view, null); view.setTextSize(14);
        view.setMaxLines(1); view.setMinimumHeight(MirrorUi.dp(this, 48)); return view;
    }
    private RadioButton radio(RadioGroup group, String text) {
        RadioButton button = new RadioButton(this); button.setId(View.generateViewId()); button.setText(text); button.setTextColor(theme.text); button.setTextSize(15);
        button.setButtonTintList(ColorStateList.valueOf(theme.accent)); button.setPadding(0, MirrorUi.dp(this, 6), 0, MirrorUi.dp(this, 6));
        group.addView(button, new RadioGroup.LayoutParams(-1, -2)); return button;
    }
}
