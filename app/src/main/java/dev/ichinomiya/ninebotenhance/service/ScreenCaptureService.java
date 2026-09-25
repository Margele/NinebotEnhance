package dev.ichinomiya.ninebotenhance.service;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.*;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.view.Surface;
import dev.ichinomiya.ninebotenhance.R;
import dev.ichinomiya.ninebotenhance.core.CaptureSize;
import dev.ichinomiya.ninebotenhance.diagnostics.Diagnostics;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;

/** All MediaProjection operations run on the module's main thread after explicit system consent. */
public final class ScreenCaptureService extends Service {
    private static final String CHANNEL = "screen_capture", STOP = "stop_capture";
    private static final int NOTIFICATION = 401;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ProjectionSession session;
    private MediaProjection projection;
    private MediaProjection.Callback callback;
    private VirtualDisplay display;
    private String request;
    private PendingIntent stopAction;
    private int dpi;
    @Override public void onCreate() { super.onCreate(); session = ProjectionSession.get(this); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { if (request == null) stopSelfResult(startId); return START_NOT_STICKY; }
        String id = intent.getStringExtra(Protocol.REQUEST);
        if (STOP.equals(intent.getAction())) {
            if (id != null && id.equals(request)) { session.stop(id, "已从通知结束录屏"); end(id); }
            else if (request == null) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        // A delayed or duplicated service start must never stop a later active recording.
        if (request != null) return START_NOT_STICKY;
        String token = intent.getStringExtra("consent_token");
        Intent consent = Ipc.parcelableExtra(intent, "capture_data", Intent.class);
        if (intent.getIntExtra("result_code", 0) != Activity.RESULT_OK || consent == null) { stopSelfResult(startId); return START_NOT_STICKY; }
        ProjectionSession.Boot boot = session.consumeGrant(id, token);
        if (boot == null) { stopSelfResult(startId); return START_NOT_STICKY; }
        request = id; dpi = boot.dpi();
        try {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "投屏录制", NotificationManager.IMPORTANCE_LOW));
            Intent stop = new Intent(this, ScreenCaptureService.class).setAction(STOP)
                    .setData(Uri.parse("ninebot-enhance://stop-capture/" + id)).putExtra(Protocol.REQUEST, id);
            stopAction = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
            Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mirror)
                    .setContentTitle("Ninebot Enhance 正在投屏").setContentText("正在分享所选应用或整个屏幕，点击结束")
                    .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setContentIntent(stopAction)
                    .addAction(new Notification.Action.Builder(null, "结束投屏", stopAction).build()).build();
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, consent);
            if (projection == null) throw new IllegalStateException("系统没有返回录屏授权");
            final String current = id;
            callback = new MediaProjection.Callback() {
                @Override public void onStop() { session.stop(current, "系统录屏已结束，请重新开始投屏"); end(current); }
                @Override public void onCapturedContentResize(int width, int height) {
                    try { session.contentSize(current, width, height); }
                    catch (RuntimeException e) { session.stop(current, "无法读取录屏尺寸"); }
                }
            };
            projection.registerCallback(callback, main);
            display = projection.createVirtualDisplay("Ninebot Enhance Screen Capture", boot.size().width(), boot.size().height(), dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, boot.surface(), null, main);
            if (display == null || !session.ready(id, token, this, display.getDisplay().getDisplayId())) {
                session.stop(id, "录屏启动已取消"); end(id);
            }
        } catch (RuntimeException e) {
            Diagnostics.add("PROJECTION start " + Ipc.error(e)); session.stop(id, "系统录屏启动失败：" + Ipc.error(e)); end(id);
        }
        return START_NOT_STICKY;
    }
    void resizeOutput(Surface output, CaptureSize size) {
        if (display == null) throw new IllegalStateException("录屏已结束");
        // Resize the existing display; a consent token cannot create a second one on Android 14+.
        display.resize(size.width(), size.height(), dpi); display.setSurface(output);
    }
    void end(String id) {
        if (id == null || !id.equals(request)) return;
        request = null;
        if (stopAction != null) { stopAction.cancel(); stopAction = null; }
        if (display != null) { try { display.release(); } catch (RuntimeException ignored) {} display = null; }
        MediaProjection old = projection; projection = null;
        if (old != null) { try { if (callback != null) old.unregisterCallback(callback); old.stop(); } catch (RuntimeException ignored) {} }
        callback = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        String id = request;
        if (id != null) { session.stop(id, "录屏服务已关闭"); end(id); }
        super.onDestroy();
    }
}
