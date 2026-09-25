package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.TextView;
import android.widget.Toast;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;

/** The newer-release dialog in the module's own colors: the version pair, a close button and one that opens the release page. */
final class UpdateDialog {
    static void show(Activity activity, MirrorUi theme, String version, String url) {
        TextView body = DialogContent.text(activity, theme, "当前 " + Protocol.VERSION + "，最新 " + version, 15);
        DialogContent.show(activity, theme, DialogContent.create(activity, theme, "发现新版本 " + version, body, "打开 Release 页", () -> open(activity, url)));
    }
    static void open(Activity activity, String url) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (RuntimeException e) { ErrorDialog.show(activity, null, "没有可打开链接的应用", url); }
    }
    private UpdateDialog() {}
}
