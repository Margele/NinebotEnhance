package dev.ichinomiya.ninebotenhance.ui;

import android.app.*;
import android.view.*;
import android.widget.*;

/** Native rounded dialogs with explicit text colors, independent of the host Activity theme. */
final class DialogContent {
    static TextView text(Activity activity, MirrorUi theme, String value, int size) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setTextColor(theme.text);
        int pad = MirrorUi.dp(activity, 20); text.setPadding(pad, pad / 2, pad, pad / 2);
        text.setLineSpacing(MirrorUi.dp(activity, 3), 1); return text;
    }
    static AlertDialog create(Activity activity, MirrorUi theme, String heading, View body) {
        return new AlertDialog.Builder(activity).setCustomTitle(title(activity, theme, heading)).setView(body).setPositiveButton("关闭", null).create();
    }
    /** A dialog with one action next to the close button; the action runs and the dialog closes. */
    static AlertDialog create(Activity activity, MirrorUi theme, String heading, View body, String action, Runnable onAction) {
        return new AlertDialog.Builder(activity).setCustomTitle(title(activity, theme, heading)).setView(body)
                .setPositiveButton(action, (d, w) -> onAction.run()).setNegativeButton("关闭", null).create();
    }
    private static TextView title(Activity activity, MirrorUi theme, String heading) {
        TextView title = text(activity, theme, heading, 20);
        title.setPadding(title.getPaddingLeft(), MirrorUi.dp(activity, 20), title.getPaddingRight(), MirrorUi.dp(activity, 12));
        return title;
    }
    static void show(Activity activity, MirrorUi theme, AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 24, false));
        for (int which : new int[]{-1, -2, -3}) { Button button = dialog.getButton(which); if (button != null) button.setTextColor(theme.accent); }
    }
    static void document(Activity activity, MirrorUi theme, String heading, String value) {
        TextView body = text(activity, theme, value, 13); body.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        show(activity, theme, create(activity, theme, heading, scroll));
    }
    private DialogContent() {}
}
