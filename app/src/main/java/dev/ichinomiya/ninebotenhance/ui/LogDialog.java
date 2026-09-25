package dev.ichinomiya.ninebotenhance.ui;

import android.app.*;
import android.content.*;
import android.view.View;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;

final class LogDialog {
    static void show(Activity activity, FrameClient frames, View reference) {
        frames.diagnostics(summary -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            MirrorUi theme = new MirrorUi(activity, reference);
            TextView body = DialogContent.text(activity, theme, summary, 12); body.setTextIsSelectable(true);
            ScrollView scroll = new ScrollView(activity); scroll.addView(body);
            AlertDialog dialog = DialogContent.create(activity, theme, "日志", scroll);
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "分享完整日志", (DialogInterface.OnClickListener) null);
            DialogContent.show(activity, theme, dialog);
            Button share = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            share.setOnClickListener(v -> {
                share.setEnabled(false); share.setText("正在生成…");
                frames.shareFullLog(uri -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) return;
                    share.setEnabled(true); share.setText("分享完整日志");
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "Ninebot Enhance 完整日志")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        send.setClipData(ClipData.newRawUri("Ninebot Enhance 日志", uri));
                        Intent chooser = Intent.createChooser(send, "分享完整日志");
                        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); chooser.setClipData(send.getClipData());
                        activity.startActivity(chooser);
                    } catch (RuntimeException e) { failure(activity, frames, Ipc.error(e)); }
                }, error -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) return;
                    share.setEnabled(true); share.setText("分享完整日志"); failure(activity, frames, error);
                });
            });
        });
    }
    private static void failure(Activity activity, FrameClient frames, String message) {
        frames.report("LOG_EXPORT " + message);
        ErrorDialog.show(activity, null, "无法分享日志", message);
    }
    private LogDialog() {}
}
