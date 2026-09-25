package dev.ichinomiya.ninebotenhance.service;

import dev.ichinomiya.ninebotenhance.core.CallerPolicy;
import dev.ichinomiya.ninebotenhance.core.CaptureSize;
import dev.ichinomiya.ninebotenhance.core.PrivilegeMode;
import dev.ichinomiya.ninebotenhance.diagnostics.Diagnostics;
import dev.ichinomiya.ninebotenhance.diagnostics.LogDigest;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.platform.AppCatalog;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.view.Surface;
import android.view.MotionEvent;
import android.view.KeyEvent;
import dev.ichinomiya.ninebotenhance.privilege.PrivilegeManager;
import dev.ichinomiya.ninebotenhance.privilege.RootAuthorization;

/** Only metadata and Binder/Surface handles cross IPC. No compressed image payloads. */
public final class FrameBridgeService extends Service {
    private volatile boolean projectionSource;
    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(Protocol.DESCRIPTOR); return true; }
            data.enforceInterface(Protocol.DESCRIPTOR);
            int uid = Binder.getCallingUid();
            if (!CallerPolicy.allowedFor(code, uid, android.os.Process.myUid(), getPackageManager().getPackagesForUid(uid)))
                throw new SecurityException("仅允许九号与模块访问");
            Bundle args = data.readBundle(getClassLoader()); if (args == null) args = new Bundle();
            // Queries use this module's MAIN/LAUNCHER visibility declaration, not the calling Ninebot UID.
            // Keep the verified original uid for ownership checks after clearing Binder identity.
            long identity = Binder.clearCallingIdentity();
            try { return dispatch(code, args, uid, data, reply, flags); }
            finally { Binder.restoreCallingIdentity(identity); }
        }
        private boolean dispatch(int code, Bundle args, int uid, Parcel data, Parcel reply, int flags) throws RemoteException {
            RootSession session = RootSession.get(FrameBridgeService.this);
            ProjectionSession projection = ProjectionSession.get(FrameBridgeService.this);
            Bundle result = new Bundle();
            switch (code) {
                case Protocol.LOG_EXPORT_BEGIN: case Protocol.LOG_EXPORT_FINISH: case Protocol.LOG_EXPORT_CANCEL:
                    result = LogExport.dispatch(FrameBridgeService.this, code, uid, args,
                            code == Protocol.LOG_EXPORT_BEGIN ? session.previousExit() : ""); break;
                case Protocol.PRIVILEGE:
                    if (args.getBoolean("save")) synchronized (session) {
                        if (projection.active()) throw new IllegalStateException("请先结束投屏再修改授权方式");
                        session.savePrivilege(args.getString("privilege_mode"));
                        if (args.containsKey("keep_root") && args.getBoolean("keep_root") != PrivilegeManager.keepRoot(FrameBridgeService.this)) {
                            if (session.status().getBoolean("active")) throw new IllegalStateException("请先结束投屏再修改授权方式");
                            // The retained Root shell carries the other identity: drop it so the next check reconnects with the chosen one.
                            PrivilegeManager.saveKeepRoot(FrameBridgeService.this, args.getBoolean("keep_root")); RootAuthorization.close();
                        }
                    }
                    if (args.getBoolean("request_permission")) PrivilegeManager.requestPermission();
                    if (args.getBoolean("request_root")) {
                        if (session.status().getBoolean("active") || projection.active()) throw new IllegalStateException("请先结束投屏再申请 Root 权限");
                        RootAuthorization.request(FrameBridgeService.this);
                    }
                    if (args.getBoolean("prepare_start") && !session.status().getBoolean("active") && !projection.active())
                        PrivilegeManager.prepareStart(FrameBridgeService.this);
                    result = PrivilegeManager.status(FrameBridgeService.this);
                    result.putBoolean("active", session.status().getBoolean("active") || projection.active()); break;
                case Protocol.NOTIFICATION_SETTINGS:
                    android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 35) options.setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    Intent settingsIntent = new Intent(FrameBridgeService.this, dev.ichinomiya.ninebotenhance.ui.NotificationSettingsActivity.class).putExtra("dark", args.getBoolean("dark", true));
                    result.putParcelable("settings_intent", android.app.PendingIntent.getActivity(FrameBridgeService.this, 801, settingsIntent,
                            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT, options.toBundle()));
                    break;
                case Protocol.LAMP_SETTINGS: {
                    android.app.ActivityOptions lampOptions = android.app.ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 35) lampOptions.setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    Intent lampIntent = new Intent(FrameBridgeService.this, dev.ichinomiya.ninebotenhance.ui.LampSettingsActivity.class).putExtra("dark", args.getBoolean("dark", true));
                    result.putParcelable("lamp_intent", android.app.PendingIntent.getActivity(FrameBridgeService.this, 803, lampIntent,
                            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT, lampOptions.toBundle()));
                    break;
                }
                case Protocol.BMS_SETTINGS: {
                    android.app.ActivityOptions bmsOptions = android.app.ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 35) bmsOptions.setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    Intent bmsIntent = new Intent(FrameBridgeService.this, dev.ichinomiya.ninebotenhance.ui.BmsSettingsActivity.class).putExtra("dark", args.getBoolean("dark", true));
                    result.putParcelable("bms_intent", android.app.PendingIntent.getActivity(FrameBridgeService.this, 804, bmsIntent,
                            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT, bmsOptions.toBundle()));
                    break;
                }
                case Protocol.TOUCH_SETTINGS: {
                    android.app.ActivityOptions touchOptions = android.app.ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 35) touchOptions.setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    Intent touchIntent = new Intent(FrameBridgeService.this, dev.ichinomiya.ninebotenhance.ui.TouchSettingsActivity.class).putExtra("dark", args.getBoolean("dark", true));
                    result.putParcelable("touch_intent", android.app.PendingIntent.getActivity(FrameBridgeService.this, 805, touchIntent,
                            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT, touchOptions.toBundle()));
                    break;
                }
                case Protocol.LAUNCH_APP_PICKER: {
                    android.app.ActivityOptions pickerOptions = android.app.ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 35) pickerOptions.setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    Intent pickerIntent = new Intent(FrameBridgeService.this, dev.ichinomiya.ninebotenhance.ui.LaunchAppPickerActivity.class)
                            .putExtra("dark", args.getBoolean("dark", true)).putExtra(AppCatalog.SELECTED, args.getString(AppCatalog.SELECTED, ""))
                            .putExtra(dev.ichinomiya.ninebotenhance.ui.LaunchAppPickerActivity.RESULT, args.getParcelable(dev.ichinomiya.ninebotenhance.ui.LaunchAppPickerActivity.RESULT, ResultReceiver.class));
                    result.putParcelable("picker_intent", android.app.PendingIntent.getActivity(FrameBridgeService.this, 802, pickerIntent,
                            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_CANCEL_CURRENT | android.app.PendingIntent.FLAG_ONE_SHOT, pickerOptions.toBundle()));
                    break;
                }
                case Protocol.UPDATE_CHECK: result = UpdateChecker.get(FrameBridgeService.this).snapshot(args.getBoolean("refresh")); break;
                case Protocol.READ:
                    result = projectionSource ? projection.status() : session.status();
                    // The poll carries whether a Ninebot screen is visible; the lamp and BMS links follow it (see NotificationHub.hostVisible).
                    dev.ichinomiya.ninebotenhance.notification.NotificationHub.hostVisible(FrameBridgeService.this, args.getBoolean("foreground"));
                    break;
                case Protocol.HUD_SNAPSHOT:
                    result = projectionSource ? projection.status() : session.status();
                    if (result.getBoolean("active") && args.getString(Protocol.REQUEST, "").equals(result.getString(Protocol.REQUEST)))
                        result.putBundle("hud", dev.ichinomiya.ninebotenhance.notification.NotificationHub.get(FrameBridgeService.this)
                                .snapshot(args.getLong("hud_cursor", -1), args.getString("hud_epoch", ""),args.getLong("music_art_revision",-1)));
                    break;
                case Protocol.BEGIN:
                    synchronized (session) {
                        Surface surface = args.getParcelable("surface", Surface.class);
                        boolean recording = PrivilegeManager.mode(FrameBridgeService.this) == PrivilegeMode.NONE;
                        if (projection.active() || session.status().getBoolean("active") || recording != args.getBoolean(Protocol.SCREEN_CAPTURE)
                                || recording && args.getBoolean("local")) {
                            if (surface != null) surface.release(); throw new IllegalStateException("会话或授权方式已变化，请结束后重新开始投屏");
                        }
                        if (recording) {
                            CaptureSize size;
                            try { size = new CaptureSize(args.getInt(Protocol.CAPTURE_WIDTH), args.getInt(Protocol.CAPTURE_HEIGHT)); }
                            catch (RuntimeException e) { if (surface != null) surface.release(); throw e; }
                            android.app.PendingIntent consent = projection.begin(args.getString(Protocol.REQUEST), surface, args.getBinder("owner"), uid, size);
                            projectionSource = true; result = projection.status(); result.putParcelable(Protocol.CAPTURE_CONSENT, consent);
                        } else {
                            session.setRenderPlan(args.getInt(Protocol.CAPTURE_WIDTH, 0), args.getInt(Protocol.CAPTURE_HEIGHT, 0), args.getInt("render_dpi", 0));
                            session.begin(args.getString(Protocol.REQUEST), surface, args.getBinder("owner"), uid, Ipc.settings(args), args.getString(AppCatalog.SELECTED));
                            projectionSource = false; result = session.status();
                        }
                    }
                    result.putBundle("hud", dev.ichinomiya.ninebotenhance.notification.NotificationHub.get(FrameBridgeService.this).snapshot(-1, ""));
                    break;
                case Protocol.PROJECTION_SURFACE:
                    Surface updated = args.getParcelable("surface", Surface.class);
                    CaptureSize size;
                    try { size = new CaptureSize(args.getInt(Protocol.CAPTURE_WIDTH), args.getInt(Protocol.CAPTURE_HEIGHT)); }
                    catch (RuntimeException e) { if (updated != null) updated.release(); throw e; }
                    try { result.putBoolean("accepted", projection.replaceSurface(args.getString(Protocol.REQUEST), uid, updated, size, args.getInt(Protocol.CAPTURE_REVISION))); }
                    catch (Exception e) { throw new IllegalStateException("录屏尺寸更新失败：" + Ipc.error(e)); }
                    break;
                case Protocol.STOP_DIRECT:
                    session.stop(args.getString(Protocol.REQUEST), "九号结束投屏"); projection.stop(args.getString(Protocol.REQUEST), "九号结束投屏"); break;
                case Protocol.SETTINGS:
                    if (args.getBoolean("save")) synchronized (session) {
                        if (projection.active()) throw new IllegalStateException("请先结束投屏再修改设置");
                        if (PrivilegeManager.mode(FrameBridgeService.this).usesVirtualDisplay())
                            session.saveSettings(Ipc.settings(args), args.getString(AppCatalog.SELECTED));
                    }
                    result = session.settingsBundle();
                    if (args.getBoolean("include_apps") && PrivilegeManager.mode(FrameBridgeService.this).usesVirtualDisplay())
                        result.putParcelableArrayList(AppCatalog.APPS, AppCatalog.choices(getPackageManager()));
                    break;
                case Protocol.REPORT: Diagnostics.add(args.getString("message", "")); break;
                case Protocol.NAVI_UPDATE: dev.ichinomiya.ninebotenhance.navi.NaviHub.get().publish(args); break;
                case Protocol.NAVI_SNAPSHOT: result = dev.ichinomiya.ninebotenhance.navi.NaviHub.get().snapshot(); break;
                case Protocol.APP_ICON:
                    try { result.putParcelable("icon", AppCatalog.icon(getPackageManager(), args.getString(AppCatalog.SELECTED))); }
                    catch (Exception e) { result.putString("error", Ipc.error(e)); }
                    break;
                case Protocol.LOG:
                    result.putString("text", "系统记录（可能早于本次测试）：\n" + session.previousExit() + "\n" + Diagnostics.text());
                    result.putString("compact", "pid=" + android.os.Process.myPid() + " v=" + Protocol.VERSION
                            + "\n系统退出记录（核对时间）：\n" + LogDigest.head(session.previousExit(), 650)
                            + "\n最近模块事件（新到旧）：\n" + Diagnostics.recent(600)); break;
                case Protocol.UI_BACK:
                    session.requireController(args.getString(Protocol.REQUEST), uid);
                    session.key(args.getString(Protocol.REQUEST), KeyEvent.KEYCODE_BACK); break;
                case Protocol.TOUCH_CALIBRATE:
                    session.requireController(args.getString(Protocol.REQUEST), uid);
                    session.touchCalibrate(args.getString(Protocol.REQUEST), args.getBoolean("calibrating")); break;
                case Protocol.TOUCH_CALIBRATION:
                    session.requireController(args.getString(Protocol.REQUEST), uid);
                    session.saveTouchCalibration(args.getString(Protocol.REQUEST), args.getString("calibration", "")); break;
                case Protocol.UI_RESTART_APP:
                    session.requireController(args.getString(Protocol.REQUEST), uid);
                    session.restartApp(args.getString(Protocol.REQUEST)); break;
                case Protocol.UI_TEXT: case Protocol.UI_TYPING_KEY: case Protocol.UI_DELETE:
                    session.requireController(args.getString(Protocol.REQUEST), uid);
                    session.keyboard(args.getString(Protocol.REQUEST), code, args); break;
                case Protocol.UI_INPUT:
                    MotionEvent event = args.getParcelable("event", MotionEvent.class);
                    if (event == null) throw new IllegalArgumentException("缺少触控事件");
                    try {
                        session.requireController(args.getString(Protocol.REQUEST), uid);
                        if (event.getPointerCount() > 10) throw new IllegalArgumentException("触点过多");
                    } catch (RuntimeException e) { event.recycle(); throw e; }
                    session.input(args.getString(Protocol.REQUEST), event); break; // session owns and recycles event
                default: return super.onTransact(code, data, reply, flags);
            }
            try { reply.writeNoException(); reply.writeBundle(result); return true; }
            finally {
                // Bundle serialization duplicates the descriptor; close the service's copy even if the reply fails.
                if (code == Protocol.LOG_EXPORT_BEGIN) {
                    ParcelFileDescriptor fd = result.getParcelable("log_fd", ParcelFileDescriptor.class);
                    if (fd != null) try { fd.close(); } catch (java.io.IOException ignored) {}
                }
            }
        }
    };
    @Override public void onCreate() {
        super.onCreate(); PrivilegeManager.initialize(this);
        dev.ichinomiya.ninebotenhance.navi.NaviLoopbackServer.start();
        Diagnostics.add("SERVICE created pid=" + android.os.Process.myPid());
    }
    /** Ninebot binding the service is the only sign, from inside the module, that LSPosed injected it; the entry page shows when that last happened. */
    public static final String STATUS_PREFERENCES = "module_status", BOUND_AT = "bound_at";
    @Override public IBinder onBind(Intent intent) {
        Diagnostics.add("SERVICE bound"); RootSession.get(this);
        try { getSharedPreferences(STATUS_PREFERENCES, MODE_PRIVATE).edit().putLong(BOUND_AT, System.currentTimeMillis()).apply(); } catch (RuntimeException ignored) {}
        return binder;
    }
    @Override public boolean onUnbind(Intent intent) { Diagnostics.add("SERVICE unbound"); return super.onUnbind(intent); }
    @Override public void onDestroy() {
        Diagnostics.add("SERVICE destroyed"); RootSession.get(this).stopCurrent("九号连接服务已关闭");
        ProjectionSession.get(this).stopCurrent("九号连接服务已关闭");
        RootAuthorization.close(); super.onDestroy();
    }
}
