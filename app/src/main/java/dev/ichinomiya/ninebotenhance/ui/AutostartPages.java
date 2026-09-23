package dev.ichinomiya.ninebotenhance.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import java.util.function.Consumer;

/**
 * Opens the system page where the user allows this module to be started by other applications. Vendor pages come first
 * (MIUI / HyperOS, ColorOS / OxygenOS / realme UI, vivo, Huawei / Honor); the application details page is the fallback everywhere.
 */
public final class AutostartPages {
    public static void open(Context context, Consumer<String> log) {
        Intent[] candidates = {
            new Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", Protocol.MODULE),
            new Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            new Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            new Intent().setClassName("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            new Intent().setClassName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            new Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            new Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            new Intent().setClassName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + Protocol.MODULE)),
        };
        for (Intent candidate : candidates) {
            try {
                context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                if (log != null) log.accept("SETTINGS autostart page " + (candidate.getComponent() != null ? candidate.getComponent().flattenToShortString() : candidate.getAction()));
                return;
            } catch (RuntimeException ignored) {}
        }
        if (log != null) log.accept("SETTINGS autostart page unavailable");
    }
    public static void openNotificationAccess(Context context) {
        try { context.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException ignored) {}
    }
    private AutostartPages() {}
}
