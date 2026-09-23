package dev.ichinomiya.ninebotenhance.ui;

import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.client.ServiceBridge;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.core.DirectSession;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;
import dev.ichinomiya.ninebotenhance.core.StartPermission;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import dev.ichinomiya.ninebotenhance.platform.AppCatalog;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Retains the verified original ivCruise listener and the selected vehicle's checks. */
public final class DirectCastController implements Application.ActivityLifecycleCallbacks {
    public static final String CRUISE = "cn.ninebot.device.motor.navi.CruiseModeActivity";
    private final FrameClient frames;
    private final BooleanSupplier compatible;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DirectSession session = new DirectSession();
    private final VehicleCardInjector injector;
    private WeakReference<Activity> foreground = new WeakReference<>(null), origin = new WeakReference<>(null), cruiseActivity = new WeakReference<>(null);
    private WeakReference<View> originCard = new WeakReference<>(null);
    private final Set<Application> applications = Collections.newSetFromMap(new IdentityHashMap<>());
    private final WeakHashMap<View, String> entryStates = new WeakHashMap<>();
    private long launchDeadline, beforeFrames, captureMissingSince, inlineDeadline;
    private CruiseVirtualPanel panel;
    private RecordingPanel recordingPanel;
    private LocalVirtualDisplayDialog localPanel;
    private long localRecreateUntil;
    private final StartPermission.Check permissionCheck = new StartPermission.Check();
    private WeakReference<Activity> permissionActivity = new WeakReference<>(null);
    private WeakReference<View> permissionCard = new WeakReference<>(null);
    public DirectCastController(FrameClient frames, BooleanSupplier compatible) {
        this.frames = frames; this.compatible = compatible;
        injector = new VehicleCardInjector(this, frames);
        frames.setDynamicPageListener(this::rescanCards);
    }
    public void attach(Application application) { if (applications.add(application)) application.registerActivityLifecycleCallbacks(this); }
    public void inflated(int id, View view) {
        if (!compatible.getAsBoolean() || view == null) return;
        try { if (view.getResources().getResourceEntryName(id).equals("layout_detail_navigation_card")) view.post(() -> injector.scan(view)); }
        catch (RuntimeException ignored) {}
    }
    public void decorate(Button button, View card) {
        String info = VehicleCardInjector.entryInfo(card);
        if (!info.equals(entryStates.put(card, info))) frames.report("DIRECT ENTRY " + info);
        if (permissionCheck.active()) { button.setText("取消授权检查"); button.setEnabled(true); return; }
        switch (session.phase()) {
            case IDLE: button.setText("全屏投屏"); break;
            case CHECKING_VEHICLE: button.setText("取消车辆检查"); break;
            case CONSENT: case WAITING_FRAMES: button.setText(frames.cachedPrivilege().usesVirtualDisplay() ? "取消创建虚拟屏" : "取消录屏启动"); break;
            case STARTING: button.setText("取消投屏启动"); break;
            case RUNNING: button.setText(session.isLocal() ? "停止虚拟屏预览" : "停止全屏投屏"); break;
        }
        button.setEnabled(true);
    }
    public void click(Activity activity, View card) {
        if (!compatible.getAsBoolean()) return;
        if (permissionCheck.active()) { cancelPermissionCheck(); return; }
        if (session.phase() != DirectSession.Phase.IDLE) { end(session.request(), true, "投屏已停止"); return; }
        if (!usable(activity) || card == null || !card.isAttachedToWindow()) return;
        autostartPrompt(activity);
        checkPermission(activity, card, () -> startVehicle(activity, card));
    }
    private void startVehicle(Activity activity, View card) {
        if (session.phase() != DirectSession.Phase.IDLE || !usable(activity) || card == null || !card.isAttachedToWindow()) return;
        frames.report("DIRECT CLICK " + VehicleCardInjector.entryInfo(card));
        String request = UUID.randomUUID().toString().replace("-", ""); if (!session.begin(request)) return;
        frames.beginSessionObservations(request, DirectSession.Mode.VEHICLE);
        origin = new WeakReference<>(activity); originCard = new WeakReference<>(card);
        captureMissingSince = inlineDeadline = 0;
        View view = VehicleCardInjector.cruise(card);
        if (view == null) { end(request, false, "未找到巡航启动动作，请长按投屏按钮查看日志"); return; }
        frames.report("DIRECT checking vehicle before virtual display creation");
        try {
            // Run the selected card's original checks before allocating any virtual display.
            // Visibility/enabled flags do not gate its bound listener.
            if (!view.callOnClick()) { end(request, false, "巡航入口尚未绑定启动动作"); return; }
        } catch (RuntimeException e) { end(request, true, "巡航启动失败：" + Ipc.error(e)); return; }
        injector.scan(card);
        waitVehicle(request, SystemClock.elapsedRealtime() + 90000);
    }
    /** Re-runs the card injector on every live vehicle card, e.g. after the page factory or an unlock changed. */
    public void rescanCards() {
        for (View card : new ArrayList<>(entryStates.keySet())) if (card != null && card.isAttachedToWindow()) injector.scan(card);
    }
    private View attachedCard(Activity activity) {
        if (activity == null) return null;
        for (View card : new ArrayList<>(entryStates.keySet()))
            if (card != null && card.isAttachedToWindow() && card.isShown() && VehicleCardInjector.activity(card.getContext()) == activity) return card;
        return null;
    }
    /** Captured at the original power query's invocation, never from a cached previous result. */
    public String vehicleCheckRequest() {
        return session.vehicleCheckRequest();
    }
    public void vehiclePowerObserved(String request, boolean on) {
        main.post(() -> {
            if (!session.powerChecked(request, on)) return;
            frames.report("DIRECT vehicle power checked on=" + on);
            if (!on) end(request, true, "车辆未开机，请先开机并连接蓝牙后再投屏");
        });
    }
    private void waitVehicle(String request, long deadline) {
        if (!session.matches(request) || session.phase() != DirectSession.Phase.CHECKING_VEHICLE) return;
        Activity cruise = cruiseActivity.get();
        if (!usable(origin.get()) && !usable(cruise)) { end(request, false, "车辆页面已关闭"); return; }
        if (SystemClock.elapsedRealtime() >= deadline) {
            end(request, true, "未确认车辆投屏条件，请确认车辆已开机并连接蓝牙后重试"); return;
        }
        if (usable(cruise) && foreground.get() == cruise && cruise.hasWindowFocus()) {
            session.cruiseReady(request);
            if (session.prepareDisplay(request)) { createVehicleDisplay(request); return; }
        }
        main.postDelayed(() -> waitVehicle(request, deadline), 200);
    }
    private void createVehicleDisplay(String request) {
        beforeFrames = frames.replacementCount();
        frames.report("DIRECT vehicle checks passed; starting selected capture source");
        frames.startDirect(request, DirectSession.Mode.VEHICLE, cruiseActivity.get(), error -> {
            if (!session.matches(request)) return;
            if (error != null) { end(request, true, error); return; }
            if (session.granted(request)) waitReady(request, SystemClock.elapsedRealtime() + (frames.screenCapture() ? 180000 : 65000));
        });
    }
    private void startLocal(Activity activity, View card) {
        if (!compatible.getAsBoolean() || !usable(activity)) return;
        if (session.isLocal()) {
            if (origin.get() != activity) {
                closeLocal(); origin = new WeakReference<>(activity); originCard = new WeakReference<>(card); localRecreateUntil = 0;
            }
            if (localPanel == null) showLocal(activity, session.request());
            return;
        }
        if (session.phase() != DirectSession.Phase.IDLE) { toast(activity, "请先结束仪表投屏"); return; }
        checkPermission(activity, card, () -> beginLocal(activity, card));
    }
    private void beginLocal(Activity activity, View card) {
        if (session.phase() != DirectSession.Phase.IDLE || !usable(activity)) return;
        if (!frames.cachedPrivilege().usesVirtualDisplay()) { toast(activity, "录屏模式不支持本地模拟"); return; }
        String request = UUID.randomUUID().toString().replace("-", "");
        if (!session.begin(request, DirectSession.Mode.LOCAL)) return;
        origin = new WeakReference<>(activity); originCard = new WeakReference<>(card);
        captureMissingSince = localRecreateUntil = 0;
        frames.report("LOCAL begin: virtual display and phone preview only");
        frames.startDirect(request, DirectSession.Mode.LOCAL, error -> {
            if (!session.matches(request)) return;
            if (error != null) { end(request, false, error); return; }
            if (session.granted(request)) waitReady(request, SystemClock.elapsedRealtime() + 65000);
        });
        showLocal(activity, request);
    }
    private void checkPermission(Activity activity, View card, Runnable start) {
        if (permissionCheck.active() || session.phase() != DirectSession.Phase.IDLE) return;
        long generation = permissionCheck.begin(SystemClock.elapsedRealtime());
        permissionActivity = new WeakReference<>(activity); permissionCard = new WeakReference<>(card);
        if (card != null) injector.scan(card);
        permissionState(activity, card, start, generation, true);
    }
    private void permissionState(Activity activity, View card, Runnable start, long generation, boolean prepare) {
        if (!permissionCheck.owns(generation)) return;
        Bundle args = new Bundle(); if (prepare) args.putBoolean("prepare_start", true);
        frames.privilege(args, result -> {
            StartPermission.Check.Result decision = permissionCheck.accept(generation, result.getBoolean("start_allowed"),
                    result.getBoolean("start_pending"), SystemClock.elapsedRealtime());
            if (decision == StartPermission.Check.Result.STALE) return;
            if (decision == StartPermission.Check.Result.WAIT) {
                main.postDelayed(() -> permissionState(activity, card, start, generation, false), 300);
                return;
            }
            cancelPermissionCheck();
            if (!usable(activity) || foreground.get() != activity || session.phase() != DirectSession.Phase.IDLE) return;
            if (decision == StartPermission.Check.Result.START) start.run();
            else if (decision == StartPermission.Check.Result.TIMEOUT)
                permissionRequired(activity, card, "授权检查超时", "请重新点击投屏，或到设置检查授权服务状态。");
            else permissionRequired(activity, card, "请先完成授权", result.getString("start_permission_message",
                    "请先到设置 → 授权方式申请权限，然后重新开始投屏。"));
        }, error -> {
            if (!permissionCheck.owns(generation)) return;
            cancelPermissionCheck();
            if (usable(activity) && foreground.get() == activity)
                permissionRequired(activity, card, "暂时无法检查授权", error + "\n请先到设置检查模块连接与授权状态。");
        });
    }
    private void cancelPermissionCheck() {
        permissionCheck.cancel();
        View card = permissionCard.get(); permissionCard.clear(); permissionActivity.clear();
        if (card != null) injector.scan(card);
    }
    private void permissionRequired(Activity activity, View card, String heading, String message) {
        MirrorUi theme = new MirrorUi(activity, card);
        int pad = MirrorUi.dp(activity, 20);
        TextView title = new TextView(activity); title.setText(heading); title.setTextSize(20); title.setTextColor(theme.text);
        title.setPadding(pad, pad, pad, pad / 2);
        TextView body = new TextView(activity); body.setText(message); body.setTextSize(14); body.setTextColor(theme.secondary);
        body.setPadding(pad, pad / 2, pad, pad); body.setLineSpacing(MirrorUi.dp(activity, 3), 1);
        AlertDialog dialog = new AlertDialog.Builder(activity).setCustomTitle(title).setView(body)
                .setNegativeButton("取消", null).setPositiveButton("去设置", (d, which) -> {
                    if (usable(activity)) PrivilegeDialog.show(activity, frames, card);
                }).create();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 24, false));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(theme.accent);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(theme.accent);
    }
    private void showLocal(Activity activity, String request) {
        if (!session.matches(request) || !session.isLocal() || localPanel != null || !usable(activity)) return;
        try {
            localPanel = new LocalVirtualDisplayDialog(activity, request, frames,
                    () -> end(request, false, "虚拟屏已结束"), () -> entryDetails(activity, originCard.get()));
            localPanel.show();
            if (session.phase() == DirectSession.Phase.RUNNING) localPanel.ready();
        } catch (RuntimeException e) { end(request, false, "无法显示虚拟屏：" + Ipc.error(e)); }
    }
    private void closeLocal() {
        if (localPanel == null) return;
        try { localPanel.close(); } catch (RuntimeException e) { frames.report("LOCAL window close " + Ipc.error(e)); }
        localPanel = null;
    }
    private void waitReady(String request, long deadline) {
        if (!session.matches(request) || session.phase() != DirectSession.Phase.WAITING_FRAMES) return;
        Activity activity = session.isLocal() ? origin.get() : cruiseActivity.get();
        if (!usable(activity) && !(!session.isLocal() && frames.screenCapture())
                && !(session.isLocal() && SystemClock.elapsedRealtime() < localRecreateUntil)) {
            end(request, true, session.isLocal() ? "预览页面已关闭" : "巡航页面已关闭"); return;
        }
        if (frames.failedFor(request)) { end(request, true, frames.status()); return; }
        if (session.isLocal() && frames.readyFor(request)) {
            if (session.localReady(request)) {
                if (localPanel != null) localPanel.ready();
                frames.report("LOCAL running: first RGBA frame ready; no cruise dispatch");
                main.postDelayed(() -> monitor(request), 500);
            }
            return;
        }
        if (!session.isLocal() && frames.readyFor(request)) {
            if (!session.launch(request)) return;
            launchDeadline = SystemClock.elapsedRealtime() + 90000;
            main.postDelayed(() -> monitor(request), 500); return;
        }
        if (SystemClock.elapsedRealtime() >= deadline) { end(request, true, frames.status()); return; }
        main.postDelayed(() -> waitReady(request, deadline), 200);
    }
    private void monitor(String request) {
        if (!session.matches(request)) return;
        long now = SystemClock.elapsedRealtime();
        if (!frames.captureActiveFor(request)) {
            if (captureMissingSince == 0) captureMissingSince = now;
            if (now - captureMissingSince > 4000) { end(request, true, "画面采集已结束\n" + frames.status()); return; }
        } else captureMissingSince = 0;
        if (session.isLocal()) {
            Activity activity = origin.get();
            if (!usable(activity) && now >= localRecreateUntil) { end(request, false, "预览页面已关闭"); return; }
            if (usable(activity) && foreground.get() == activity && localPanel == null) showLocal(activity, request);
            if (session.matches(request)) main.postDelayed(() -> monitor(request), 500);
            return;
        }
        if (session.phase() == DirectSession.Phase.STARTING) {
            if ((frames.screenCapture() || cruiseActivity.get() != null) && frames.replacementCount() > beforeFrames) {
                session.running(request); frames.report("DIRECT RUNNING replacement frames reached capture hooks");
            } else if (now > launchDeadline) { end(request, true, "巡航投屏未启动，请长按按钮查看日志"); return; }
        }
        Activity cruise = cruiseActivity.get();
        if (session.phase() == DirectSession.Phase.RUNNING && usable(cruise) && foreground.get() == cruise && cruise.hasWindowFocus()) {
            try {
                if (frames.screenCapture()) {
                    if (recordingPanel != null && !recordingPanel.owns(cruise)) closePanel();
                    if (recordingPanel == null) recordingPanel = new RecordingPanel(cruise, frames, () -> end(request, true, "投屏已停止"));
                    main.postDelayed(() -> monitor(request), 500); return;
                }
                if (panel != null && (!panel.owns(cruise) || !panel.attached())) closePanel();
                if (panel == null) {
                    if (inlineDeadline == 0) inlineDeadline = now + 10000;
                    panel = CruiseVirtualPanel.mount(cruise, request, frames,
                            () -> end(request, true, "投屏已停止"), () -> entryDetails(cruise, originCard.get()));
                    if (panel != null) inlineDeadline = 0;
                    else if (now >= inlineDeadline) { end(request, true, "未找到巡航地图区域，请长按投屏按钮查看日志"); return; }
                }
            } catch (RuntimeException e) { end(request, true, "无法显示巡航虚拟屏：" + Ipc.error(e)); return; }
        }
        main.postDelayed(() -> monitor(request), 500);
    }
    private void end(String request, boolean finishCruise, String message) {
        if (!session.end(request)) return;
        closePanel(); closeLocal(); localRecreateUntil = 0;
        Activity owned = cruiseActivity.get(), source = origin.get(); View card = originCard.get();
        cruiseActivity.clear(); originCard.clear(); origin.clear(); frames.stopDirect(request);
        if (finishCruise && usable(owned)) owned.finish();
        if (card != null) injector.scan(card);
        frames.report("DIRECT ended: " + message);
        Activity visible = foreground.get(); if (usable(visible)) toast(visible, message); else if (source != null) toast(source, message);
    }
    private void closePanel() {
        if (panel != null) { panel.close(); panel = null; }
        if (recordingPanel != null) { recordingPanel.close(); recordingPanel = null; }
        inlineDeadline = 0;
    }
    /** Called only after the observed cast manager's actual terminal operation, never its encoder rotation/reconfigure. */
    public void transportEnded(String reason) {
        if (session.isLocal() || session.phase() != DirectSession.Phase.RUNNING) return;
        String request = session.request();
        main.post(() -> { if (session.matches(request) && !session.isLocal() && session.phase() == DirectSession.Phase.RUNNING) end(request, true, "巡航投屏已结束：" + reason); });
    }
    private final Runnable scan = new Runnable() {
        @Override public void run() {
            Activity activity = foreground.get(); if (!usable(activity)) return;
            if (compatible.getAsBoolean() && !CRUISE.equals(activity.getClass().getName())) injector.scan(activity.getWindow().getDecorView());
            main.postDelayed(this, 1200);
        }
    };
    @Override public void onActivityCreated(Activity activity, Bundle state) {
        if (state != null && session.matches(state.getString("dev.ichinomiya.ninebotenhance.pendingRequest"))) {
            origin = new WeakReference<>(activity); localRecreateUntil = 0;
        }
        if (!session.isLocal() && session.phase() != DirectSession.Phase.IDLE && CRUISE.equals(activity.getClass().getName())) {
            if (panel != null && !panel.owns(activity)) closePanel();
            cruiseActivity = new WeakReference<>(activity); frames.report("DIRECT cruise activity created");
        }
    }
    @Override public void onActivityResumed(Activity activity) {
        foreground = new WeakReference<>(activity); main.removeCallbacks(scan); main.post(scan);
        if (panel != null && panel.owns(activity)) panel.resume();
        if (recordingPanel != null && recordingPanel.owns(activity)) recordingPanel.resume();
        if (session.isLocal() && origin.get() == activity) {
            if (localPanel == null) showLocal(activity, session.request());
            else localPanel.resume();
        }
    }
    @Override public void onActivityPaused(Activity activity) {
        if (permissionCheck.active() && permissionActivity.get() == activity) cancelPermissionCheck();
        if (panel != null && panel.owns(activity)) panel.pause();
        if (localPanel != null && localPanel.owns(activity)) localPanel.pause();
        if (foreground.get() == activity) { foreground.clear(); main.removeCallbacks(scan); }
    }
    @Override public void onActivityDestroyed(Activity activity) {
        if (localPanel != null && localPanel.owns(activity)) closeLocal();
        if (session.isLocal() && origin.get() == activity) {
            if (activity.isChangingConfigurations()) {
                origin.clear(); localRecreateUntil = SystemClock.elapsedRealtime() + 15000;
            } else end(session.request(), false, "预览页面已退出");
            return;
        }
        if (panel != null && panel.owns(activity)) closePanel();
        if (recordingPanel != null && recordingPanel.owns(activity)) closePanel();
        if (activity.isChangingConfigurations()) { if (cruiseActivity.get() == activity) cruiseActivity.clear(); return; }
        if (activity == cruiseActivity.get()) end(session.request(), false, "巡航页面已退出");
        else if (activity == origin.get() && !usable(cruiseActivity.get()) && (session.phase() == DirectSession.Phase.CHECKING_VEHICLE
                || session.phase() == DirectSession.Phase.CONSENT || session.phase() == DirectSession.Phase.WAITING_FRAMES))
            end(session.request(), false, "车辆页面已退出");
    }
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        if (activity == origin.get() && session.request() != null) state.putString("dev.ichinomiya.ninebotenhance.pendingRequest", session.request());
    }
    public void settings(Activity activity, View card) {
        if (!usable(activity)) return;
        if (!frames.noticeAccepted()) { OpenSourceNoticeDialog.show(activity, card, frames, () -> settings(activity, card)); return; }
        if (permissionCheck.active()) cancelPermissionCheck();
        autostartPrompt(activity);
        DisplaySettings cached = frames.cachedSettings();
        MirrorUi theme = new MirrorUi(activity, card);
        LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density); layout.setPadding(pad, pad / 2, pad, 0);
        TextView connection = new TextView(activity); connection.setTextColor(theme.secondary); connection.setTextSize(13);
        connection.setLineSpacing(MirrorUi.dp(activity, 3), 1);
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(-1, -2); connectionParams.bottomMargin = MirrorUi.dp(activity, 16);
        layout.addView(connection, connectionParams);
        LinearLayout utilities = new LinearLayout(activity);
        Button authorization = new Button(activity); authorization.setText("授权方式"); theme.button(authorization,"settings");
        Button statistics = new Button(activity); statistics.setText("统计信息"); theme.button(statistics,null);
        LinearLayout.LayoutParams utilityParams = new LinearLayout.LayoutParams(0,-2,1);utilityParams.rightMargin=MirrorUi.dp(activity, 12);
        utilities.addView(authorization,utilityParams);utilities.addView(statistics,new LinearLayout.LayoutParams(0,-2,1));
        layout.addView(utilities,new LinearLayout.LayoutParams(-1,-2));
        statistics.setOnClickListener(v->StatisticsDialog.show(activity,frames,card));
        if (ServiceBridge.hyperOs()) {
            // Without autostart HyperOS refuses the bind that starts the module process; offer the system page directly.
            Button autostart = new Button(activity); autostart.setText("自启动设置"); theme.button(autostart, null);
            autostart.setOnClickListener(v -> openAutostart(activity));
            LinearLayout.LayoutParams autostartParams = new LinearLayout.LayoutParams(-1, -2); autostartParams.topMargin = MirrorUi.dp(activity, 12);
            layout.addView(autostart, autostartParams);
            if (!frames.serviceConnected()) connection.setText(frames.serviceStatus());
        }
        LinearLayout tools = new LinearLayout(activity);
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(-1, -2); toolsParams.topMargin = MirrorUi.dp(activity, 12);
        Button widgets=new Button(activity);widgets.setText("控件管理");
        Button encoder=new Button(activity);encoder.setText("设置覆盖");
        Button hidden=new Button(activity);hidden.setText("隐藏功能");
        Button touch=new Button(activity);touch.setText("触摸屏管理");
        for (Button tool : new Button[]{widgets, encoder, hidden, touch}) {
            theme.button(tool, null); tool.setTextSize(13); tool.setMaxLines(1); tool.setPadding(MirrorUi.dp(activity, 2), tool.getPaddingTop(), MirrorUi.dp(activity, 2), tool.getPaddingBottom());
            tool.setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams toolParams = new LinearLayout.LayoutParams(0, -2, 1); if (tools.getChildCount() > 0) toolParams.setMarginStart(MirrorUi.dp(activity, 8));
            tools.addView(tool, toolParams);
        }
        layout.addView(tools, toolsParams);
        widgets.setOnClickListener(v->WidgetSettingsDialog.show(activity,frames,card));
        encoder.setOnClickListener(v->EncoderOverrideDialog.show(activity,frames,card,session.phase()==DirectSession.Phase.IDLE));
        hidden.setOnClickListener(v->HiddenFeatureDialog.show(activity,frames,card));
        touch.setOnClickListener(v->frames.touchSettings(activity,theme.dark));
        TextView appLabel = new TextView(activity); appLabel.setText("启动应用"); appLabel.setTextColor(theme.secondary); appLabel.setPadding(0, pad / 2, 0, pad / 3); layout.addView(appLabel);
        ChoiceSpinner appPicker = new ChoiceSpinner(activity, theme, "选择启动应用");
        ArrayList<Bundle> apps = new ArrayList<>();
        AppPickerAdapter appNames = new AppPickerAdapter(activity, frames, theme, apps);
        appPicker.setAdapter(appNames); appPicker.setEnabled(false);
        appPicker.setBackground(theme.background(activity, theme.input, 14, false)); appPicker.setClipToOutline(true);
        layout.addView(appPicker, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout dimensions=fieldRow(activity,layout),virtualDimensions=fieldRow(activity,layout),options=fieldRow(activity,layout),dpiColumn=fieldColumn(activity,options);
        EditText width=field(activity,fieldColumn(activity,dimensions),"整帧宽度",cached.width,theme),
                height=field(activity,fieldColumn(activity,dimensions),"整帧高度",cached.height,theme),
                virtualWidth=field(activity,fieldColumn(activity,virtualDimensions),"虚拟屏宽度",cached.virtualWidth,theme),
                virtualHeight=field(activity,fieldColumn(activity,virtualDimensions),"虚拟屏高度",cached.virtualHeight,theme),
                dpi=field(activity,dpiColumn,"DPI",cached.dpi,theme);
        // The frame follows the vehicle's cast configuration; the fields stay for the read-back text but are never shown.
        // Virtual size, density, colours and the DPI switches moved to the override dialog; the fields stay for the read-back only.
        dimensions.setVisibility(View.GONE);virtualDimensions.setVisibility(View.GONE);options.setVisibility(View.GONE);
        LinearLayout colorColumn = fieldColumn(activity, options);
        fieldLabel(activity, colorColumn, "深色背景", theme);
        BandColorButton topColor = new BandColorButton(activity, theme, cached.backgroundColor);
        colorColumn.addView(topColor, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout lightColumn = fieldColumn(activity, options);
        fieldLabel(activity, lightColumn, "浅色背景", theme);
        BandColorButton lightColor = new BandColorButton(activity, theme, cached.lightBackgroundColor);
        lightColumn.addView(lightColor, new LinearLayout.LayoutParams(-1, -2));
        android.widget.CheckBox keepDpi=new android.widget.CheckBox(activity);keepDpi.setText("保持 DPI");keepDpi.setTextColor(theme.text);keepDpi.setTextSize(15);
        keepDpi.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));keepDpi.setChecked(cached.keepPhoneDpi);
        keepDpi.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);keepDpi.setIncludeFontPadding(false);keepDpi.setPadding(0,MirrorUi.dp(activity,8),0,MirrorUi.dp(activity,8));
        android.widget.CheckBox compat=new android.widget.CheckBox(activity);compat.setText("兼容缩放");compat.setTextColor(theme.text);compat.setTextSize(15);
        compat.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));compat.setChecked(cached.compatScale||frames.compatScaleForced());
        compat.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);compat.setIncludeFontPadding(false);compat.setPadding(0,MirrorUi.dp(activity,8),0,MirrorUi.dp(activity,8));
        LinearLayout dpiRow=new LinearLayout(activity);dpiRow.setGravity(Gravity.CENTER_VERTICAL);
        dpiRow.addView(keepDpi,new LinearLayout.LayoutParams(0,-2,1));dpiRow.addView(compat,new LinearLayout.LayoutParams(0,-2,1));
        layout.addView(dpiRow,new LinearLayout.LayoutParams(-1,-2));dpiRow.setVisibility(View.GONE);
        // Compat scaling only makes sense with keep-DPI; once the daemon reported a forced-size failure it stays on.
        Runnable compatSync=()->{boolean forced=frames.compatScaleForced();if(forced)compat.setChecked(true);compat.setEnabled(keepDpi.isEnabled()&&keepDpi.isChecked()&&!forced);};
        keepDpi.setOnCheckedChangeListener((b,c)->compatSync.run());compatSync.run();
        LinearLayout actions = new LinearLayout(activity); actions.setGravity(Gravity.CENTER_VERTICAL); actions.setBaselineAligned(false);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2); actionParams.topMargin = pad / 2;
        layout.addView(actions, actionParams);
        Button retry = new Button(activity); retry.setText("重新连接并读取"); theme.button(retry, null);
        retry.setTextSize(13); retry.setPadding(MirrorUi.dp(activity, 6), MirrorUi.dp(activity, 12), MirrorUi.dp(activity, 6), MirrorUi.dp(activity, 12));
        retry.setMinimumHeight(MirrorUi.dp(activity, 48));
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(0, -1, 1); retryParams.setMarginEnd(MirrorUi.dp(activity, 12)); actions.addView(retry, retryParams);
        Button local = new Button(activity); local.setText("本地模拟");
        theme.button(local, null); local.setTextSize(13); local.setMinimumHeight(MirrorUi.dp(activity, 48)); local.setEnabled(session.isLocal());
        actions.addView(local, new LinearLayout.LayoutParams(0, -1, 1));
        TextView localHelp = new TextView(activity);
        localHelp.setText("保存当前选择并启动手机预览，无需车辆开机或连接蓝牙。\n"); localHelp.setTextColor(theme.secondary); localHelp.setTextSize(13); localHelp.setPadding(0, pad / 2, 0, 0); layout.addView(localHelp);
        ScrollView scroll = new ScrollView(activity); scroll.addView(layout);
        TextView title = new TextView(activity); title.setText("设置"); title.setTextSize(20); title.setTextColor(theme.text);
        title.setPadding(pad, pad, pad, pad / 2);
        SettingsFooter footer = new SettingsFooter(activity, theme);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); body.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity).setCustomTitle(title).setView(body).create();
        // Show cached fields and diagnostics first. No service, Binder call or capture worker gates this window.
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 24, false));
        footer.about.setOnClickListener(v -> AboutDialog.show(activity, card, frames));
        footer.close.setOnClickListener(v -> dialog.dismiss());
        Button save = footer.save; save.setEnabled(false);
        boolean[] loaded = {false};DisplaySettings[] loadedValue={null};
        Runnable showMode = () -> {
            boolean virtual = frames.cachedPrivilege().usesVirtualDisplay();
            for (View field : new View[]{appLabel,appPicker,local,localHelp})field.setVisibility(virtual?View.VISIBLE:View.GONE);
            retryParams.setMarginEnd(virtual ? MirrorUi.dp(activity, 12) : 0); retry.setLayoutParams(retryParams);
        };
        showMode.run();
        Runnable read = () -> {
            loaded[0] = false; save.setEnabled(false); retry.setEnabled(false); appPicker.setEnabled(false); local.setEnabled(session.isLocal());
            String w=width.getText().toString(),h=height.getText().toString(),d=dpi.getText().toString(),vw=virtualWidth.getText().toString(),vh=virtualHeight.getText().toString();
            int color = topColor.color(), light = lightColor.color(); boolean keep = keepDpi.isChecked();
            connection.setText("正在读取已保存参数；当前显示缓存或未保存的输入。");
            frames.getSettings(config -> {
                if (!usable(activity) || !dialog.isShowing()) return;
                DisplaySettings value = Ipc.settings(config);loadedValue[0]=value;
                boolean edited = !w.equals(width.getText().toString()) || !h.equals(height.getText().toString()) || !d.equals(dpi.getText().toString())
                        ||!vw.equals(virtualWidth.getText().toString())||!vh.equals(virtualHeight.getText().toString())||color!=topColor.color()||light!=lightColor.color()||keep!=keepDpi.isChecked();
                if (!edited) { width.setText(String.valueOf(value.width)); height.setText(String.valueOf(value.height)); dpi.setText(String.valueOf(value.dpi));
                    virtualWidth.setText(String.valueOf(value.virtualWidth));virtualHeight.setText(String.valueOf(value.virtualHeight));topColor.setBandColor(value.backgroundColor);lightColor.setBandColor(value.lightBackgroundColor);keepDpi.setChecked(value.keepPhoneDpi);compat.setChecked(value.compatScale||frames.compatScaleForced());compatSync.run(); }
                ArrayList<Bundle> catalog = config.getParcelableArrayList(AppCatalog.APPS, Bundle.class);
                String selected = config.getString(AppCatalog.SELECTED, "");
                apps.clear(); apps.add(null);
                int selectedIndex = 0;
                if (catalog != null) for (Bundle app : catalog) {
                    apps.add(app);
                    if (selected.equals(app.getString("component"))) selectedIndex = apps.size() - 1;
                }
                appNames.loaded(); appPicker.setSelection(selectedIndex);
                boolean idle = session.phase() == DirectSession.Phase.IDLE;
                loaded[0] = true; retry.setEnabled(true); save.setEnabled(idle);
                appPicker.setEnabled(idle); local.setEnabled(session.isLocal() || idle);
                width.setEnabled(idle);height.setEnabled(idle);dpi.setEnabled(idle);virtualWidth.setEnabled(idle);virtualHeight.setEnabled(idle);topColor.setEnabled(idle);lightColor.setEnabled(idle);keepDpi.setEnabled(idle);compatSync.run();
                showMode.run();
                connection.setText(!frames.cachedPrivilege().usesVirtualDisplay() ? "当前方式：无（投屏）。\n开始时通过系统窗口选择单个应用或整个屏幕。"
                        : "已读取: 整帧 "+value.width+" × "+value.height+"，虚拟屏 "+value.virtualWidth+" × "+value.virtualHeight+"，"+value.dpi+" DPI"+(value.keepPhoneDpi?"，保持手机 DPI":"")+"。"+(edited?"\n保留你刚输入的内容。":"")
                        + (apps.size() == 1 ? "\n请选择启动应用并允许读取应用列表。" : selectedIndex == 0
                            ? (selected.isEmpty() ? "\n请先选择启动应用。" : "\n原应用入口已不可用，请重新选择。") : "")
                        + (idle ? "" : "\n请先停止投屏再修改。")
                        + (frames.compatibility().isEmpty() ? "" : "\n" + frames.compatibility()));
            }, error -> {
                if (!usable(activity) || !dialog.isShowing()) return;
                retry.setEnabled(true); connection.setText(error + "\n当前输入尚未保存，可重新读取或查看日志。");
            });
        };
        appPicker.setOpenAction(() -> {
            if (!loaded[0] || session.phase() != DirectSession.Phase.IDLE) return;
            int index = appPicker.getSelectedItemPosition();
            String selected = index > 0 && index < apps.size() ? apps.get(index).getString("component", "") : "";
            frames.pickLaunchApp(activity, theme.dark, selected, app -> {
                if (!usable(activity) || !dialog.isShowing() || session.phase() != DirectSession.Phase.IDLE) return;
                String component = app.getString("component", ""); if (component.isEmpty()) return;
                int match = -1;
                for (int i = 1; i < apps.size(); i++) if (component.equals(apps.get(i).getString("component"))) { match = i; break; }
                if (match < 0) { apps.add(app); match = apps.size() - 1; } else apps.set(match, app);
                appNames.loaded(); appPicker.setSelection(match);
                // Only the app choice changes. Width, height, DPI and band edits remain unsaved in this dialog.
            });
        });
        authorization.setOnClickListener(v -> PrivilegeDialog.show(activity, frames, card, selected -> {
            if (!dialog.isShowing()) return;
            showMode.run(); read.run();
        }));
        retry.setOnClickListener(v -> read.run());
        footer.logs.setOnClickListener(v -> entryDetails(activity, card));
        java.util.function.Consumer<Boolean> commit = startAfter -> {
            if (!loaded[0]) return;
            if (session.phase() != DirectSession.Phase.IDLE) { toast(activity, "请先停止投屏再修改设置"); return; }
            if (!frames.cachedPrivilege().usesVirtualDisplay()) { dialog.dismiss(); return; }
            int index = appPicker.getSelectedItemPosition();
            if (index <= 0 || index >= apps.size()) { toast(activity, "请先选择启动应用"); return; }
            String selected = apps.get(index).getString("component");
            try {
                // Display parameters are edited in the override dialog; this save keeps the stored ones inside the current frame.
                DisplaySettings next = (loadedValue[0] == null ? cached : loadedValue[0]).withFrame(frames.frameWidth(), frames.frameHeight());
                save.setEnabled(false); retry.setEnabled(false); local.setEnabled(false); appPicker.setEnabled(false);
                width.setEnabled(false);height.setEnabled(false);dpi.setEnabled(false);virtualWidth.setEnabled(false);virtualHeight.setEnabled(false);topColor.setEnabled(false);lightColor.setEnabled(false);keepDpi.setEnabled(false);compatSync.run();connection.setText("正在保存…");
                frames.saveSettings(next, selected, error -> {
                    if (!usable(activity) || !dialog.isShowing()) return;
                    if (error == null) {
                        dialog.dismiss();
                        if (startAfter) startLocal(activity, card); else toast(activity, "启动应用和显示参数已保存");
                    } else {
                        loaded[0] = false; retry.setEnabled(true); local.setEnabled(session.isLocal());
                        width.setEnabled(true);height.setEnabled(true);dpi.setEnabled(true);virtualWidth.setEnabled(true);virtualHeight.setEnabled(true);topColor.setEnabled(true);lightColor.setEnabled(true);keepDpi.setEnabled(true);compatSync.run();
                        connection.setText(error + "\n请重新读取后再保存。");
                    }
                });
            } catch (IllegalArgumentException e) { toast(activity, e instanceof NumberFormatException ? "请输入整数" : e.getMessage()); }
        };
        save.setOnClickListener(v -> commit.accept(false));
        local.setOnClickListener(v -> {
            if (session.isLocal()) { dialog.dismiss(); startLocal(activity, card); }
            else commit.accept(true);
        });
        read.run();
    }
    private static LinearLayout fieldRow(Activity activity, LinearLayout layout) {
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }
    private static LinearLayout fieldColumn(Activity activity, LinearLayout row) {
        LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) params.setMarginStart(MirrorUi.dp(activity, 12));
        row.addView(column, params); return column;
    }
    private static EditText field(Activity activity, LinearLayout layout, String label, int value, MirrorUi theme) {
        int pad = MirrorUi.dp(activity, 12);
        fieldLabel(activity, layout, label, theme);
        EditText edit = new EditText(activity); edit.setSingleLine(true); edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setMinimumHeight(MirrorUi.dp(activity, 52));
        edit.setTextColor(theme.text); edit.setTextSize(18); edit.setBackgroundTintList(null);
        edit.setBackground(theme.background(activity, theme.input, 12, false)); edit.setPadding(pad, pad, pad, pad);
        edit.setText(String.valueOf(value)); layout.addView(edit, new LinearLayout.LayoutParams(-1, -2)); return edit;
    }
    private static void fieldLabel(Activity activity, LinearLayout layout, String label, MirrorUi theme) {
        int pad = MirrorUi.dp(activity, 12);
        TextView text = new TextView(activity); text.setText(label); text.setTextColor(theme.secondary); text.setTextSize(13);
        text.setSingleLine(true); text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setPadding(0, pad, 0, pad / 2); layout.addView(text);
    }
    /** MIUI per-app permission editor first, then the autostart manager, then the plain app details page. */
    private static boolean autostartPrompted;
    /** Once per process: the system refused to bind the module service, which on HyperOS means autostart is off. */
    private void autostartPrompt(Activity activity) {
        if (autostartPrompted || frames.serviceConnected() || !frames.serviceBindRefused() || !usable(activity)) return;
        autostartPrompted = true;
        new AlertDialog.Builder(activity).setTitle("自启动权限").setMessage(ServiceBridge.AUTOSTART_HINT)
                .setPositiveButton("打开设置", (d, w) -> openAutostart(activity)).setNegativeButton("关闭", null).show();
    }
    private void openAutostart(Activity activity) { AutostartPages.open(activity, frames::report); }
    public void entryDetails(Activity activity, View card) {
        if (!usable(activity)) return;
        frames.report("DIRECT ENTRY " + VehicleCardInjector.entryInfo(card));
        LogDialog.show(activity, frames, card);
    }
    private static boolean usable(Activity activity) { return activity != null && !activity.isFinishing() && !activity.isDestroyed(); }
    private static void toast(Context context, String value) { Toast.makeText(context, value, Toast.LENGTH_LONG).show(); }
}
