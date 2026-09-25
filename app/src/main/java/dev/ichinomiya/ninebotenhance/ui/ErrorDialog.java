package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Every error the module reports to the user: the module's own colours, selectable text, and a button that copies it. */
public final class ErrorDialog {
    public static void show(Activity activity, View reference, String message) { show(activity, reference, "出错了", message); }
    public static void show(Activity activity, View reference, String title, String message) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        String text = message == null ? "" : message;
        try {
            MirrorUi theme = new MirrorUi(activity, reference != null ? reference : activity.findViewById(android.R.id.content));
            TextView body = DialogContent.text(activity, theme, text, 14); body.setTextIsSelectable(true);
            ScrollView scroll = new ScrollView(activity); scroll.addView(body);
            DialogContent.show(activity, theme, DialogContent.create(activity, theme, title, scroll, "复制", () -> copy(activity, text)));
        } catch (RuntimeException e) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    }
    /** The view's activity when it has one; the error is only logged otherwise. */
    public static boolean show(View anywhere, String message) {
        Context context = anywhere == null ? null : anywhere.getContext();
        while (context instanceof android.content.ContextWrapper && !(context instanceof Activity)) context = ((android.content.ContextWrapper) context).getBaseContext();
        if (!(context instanceof Activity)) return false;
        show((Activity) context, anywhere, message); return true;
    }
    private static void copy(Context context, String text) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Ninebot Enhance", text));
        // Android 13 and later show their own confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
    }
    private ErrorDialog() {}
}
