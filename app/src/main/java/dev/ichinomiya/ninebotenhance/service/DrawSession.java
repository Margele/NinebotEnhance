package dev.ichinomiya.ninebotenhance.service;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import dev.ichinomiya.ninebotenhance.core.SessionLease;
import dev.ichinomiya.ninebotenhance.diagnostics.Diagnostics;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import java.util.UUID;

/**
 * The drawn picture source's session in the module process. Nothing is captured and nothing privileged is launched: the host
 * paints the frame itself. The session only exists so the dashboard snapshot (BMS, lamp, phone) flows to the host while it
 * runs and the link holds follow it, exactly as for the other two sources.
 */
public final class DrawSession {
    private static DrawSession instance;
    public static synchronized DrawSession get(Context context) {
        if (instance == null) instance = new DrawSession();
        return instance;
    }
    private final SessionLease lease = new SessionLease();
    private String lastRequest, state = "绘制未启动";
    private IBinder owner;
    private IBinder.DeathRecipient ownerDeath;
    private DrawSession() {}
    public synchronized void begin(String request, IBinder client) throws RemoteException {
        if (!Protocol.validRequest(request) || client == null || !client.isBinderAlive()) throw new IllegalArgumentException("绘制接收端未就绪");
        String secret = UUID.randomUUID().toString().replace("-", "");
        if (!lease.begin(request, secret)) throw new IllegalStateException("已有绘制会话正在运行");
        lease.attach(secret); lease.ready(secret);
        lastRequest = request; owner = client; state = "绘制中";
        ownerDeath = () -> stop(request, "九号进程已退出");
        try { client.linkToDeath(ownerDeath, 0); }
        catch (RemoteException e) { stop(request, "无法监视九号进程"); throw e; }
        Diagnostics.add("DRAW session ready; no capture, no Root/Shizuku launch");
    }
    public synchronized boolean active() { return lease.request() != null; }
    public synchronized boolean owns(String request) { return lease.owns(request); }
    public synchronized Bundle status() {
        Bundle result = new Bundle(); result.putString(Protocol.REQUEST, lease.request() == null ? lastRequest : lease.request());
        result.putInt("brokerPid", android.os.Process.myPid()); result.putString("brokerVersion", Protocol.VERSION);
        result.putBoolean("active", active()); result.putBoolean("ready", lease.isReady());
        result.putBoolean(Protocol.SCREEN_CAPTURE, false); result.putBoolean(Protocol.DRAWN, true);
        result.putString("backend", "绘制，无 Root / Shizuku 权限"); result.putString("state", state); result.putInt("displayId", -1);
        return result;
    }
    public void stopCurrent(String reason) { String request; synchronized (this) { request = lease.request(); } stop(request, reason); }
    public void stop(String request, String reason) {
        synchronized (this) {
            if (!lease.end(request)) return;
            state = reason;
            if (owner != null && ownerDeath != null) try { owner.unlinkToDeath(ownerDeath, 0); } catch (RuntimeException ignored) {}
            owner = null; ownerDeath = null;
        }
        Diagnostics.add("DRAW stopped: " + reason);
    }
}
