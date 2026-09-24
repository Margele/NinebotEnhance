package dev.ichinomiya.ninebotenhance.ui;

import dev.ichinomiya.ninebotenhance.core.ThemeMode;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Small native controls matching Ninebot's rounded card surfaces; no target resource IDs required. */
public final class MirrorUi {
    static final String PREVIEW_TAG = "dev.ichinomiya.ninebotenhance.preview";
    public final boolean dark;
    public final int surface, input, text, secondary, border, accent;
    public MirrorUi(Context context, View reference) {
        this(ThemeMode.dark(
                (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
                surface(reference, new int[]{256}), foreground(reference, new int[]{256})));
    }
    /** Explicit palette for module activities inheriting the caller's appearance. */
    public MirrorUi(boolean dark) {
        this.dark = dark;
        surface = dark ? 0xff17191f : 0xfffafbfc; input = dark ? 0xff22252c : 0xffeef1f5;
        text = dark ? 0xfff0f1f4 : 0xff20232a; secondary = dark ? 0xff989da8 : 0xff707886;
        border = dark ? 0xff30343d : 0xffdce1e8; accent = dark ? 0xff92caff : 0xff2474b9;
    }
    private static boolean ignored(View view, int[] budget) {
        return view == null || --budget[0] < 0 || view.getVisibility() != View.VISIBLE || PREVIEW_TAG.equals(view.getTag());
    }
    private static Integer surface(View view, int[] budget) {
        if (ignored(view, budget)) return null;
        // Containers supply theme evidence; individual controls and map imagery do not.
        if (view instanceof ViewGroup) {
            Integer color = solidColor(view.getBackground());
            if (ThemeMode.surfaceDark(color) != null) return color;
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Integer found = surface(group.getChildAt(i), budget); if (found != null) return found;
            }
        }
        return null;
    }
    private static Integer solidColor(Drawable drawable) {
        if (drawable instanceof ColorDrawable) return ((ColorDrawable)drawable).getColor();
        if (drawable instanceof GradientDrawable) {
            ColorStateList colors = ((GradientDrawable)drawable).getColor();
            return colors == null ? null : colors.getColorForState(drawable.getState(), colors.getDefaultColor());
        }
        if (drawable != null && drawable.getCurrent() != drawable) return solidColor(drawable.getCurrent());
        return null;
    }
    private static Integer foreground(View view, int[] budget) {
        if (ignored(view, budget)) return null;
        if (view instanceof TextView && ((TextView)view).getText().length() > 0) return ((TextView)view).getCurrentTextColor();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) { Integer color = foreground(group.getChildAt(i), budget); if (color != null) return color; }
        }
        return null;
    }
    public static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    public Drawable background(Context c, int color, int radius, boolean outline) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(c, radius));
        if (outline) shape.setStroke(dp(c, 1), border);
        return shape;
    }
    public void button(Button button, String glyph) {
        Context c = button.getContext(); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{secondary, text}));
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setBackgroundTintList(null); button.setStateListAnimator(null); button.setElevation(0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22888899), background(c, input, 16, true), null));
        button.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        if (glyph != null) {
            Drawable icon = new Glyph(glyph, secondary); icon.setBounds(0, 0, dp(c, 20), dp(c, 20));
            button.setCompoundDrawablesRelative(icon, null, null, null); button.setCompoundDrawablePadding(dp(c, 8));
        }
    }
    static final class Glyph extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String kind;
        Glyph(String kind, int color) { this.kind = kind; paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); }
        @Override public void draw(Canvas canvas) {
            int save = canvas.save(); Rect b = getBounds(); canvas.translate(b.left, b.top); canvas.scale(b.width() / 24f, b.height() / 24f);
            paint.setStyle(Paint.Style.STROKE);
            if ("cast".equals(kind)) {
                Path screen = new Path(); screen.moveTo(3, 9); screen.lineTo(3, 5); screen.quadTo(3, 3, 5, 3);
                screen.lineTo(20, 3); screen.quadTo(22, 3, 22, 5); screen.lineTo(22, 17); screen.quadTo(22, 19, 20, 19); screen.lineTo(15, 19); canvas.drawPath(screen, paint);
                canvas.drawArc(-6, 12, 12, 30, 270, 90, false, paint); canvas.drawArc(-2, 16, 8, 26, 270, 90, false, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(3, 21, 1.2f, paint);
            } else if ("settings".equals(kind)) {
                canvas.drawCircle(12, 12, 6.3f, paint); canvas.drawCircle(12, 12, 2.4f, paint);
                for (int i = 0; i < 8; i++) { canvas.drawLine(12, 2.5f, 12, 5.1f, paint); canvas.rotate(45, 12, 12); }
            } else if ("back".equals(kind)) {
                Path path = new Path(); path.moveTo(10, 5); path.lineTo(3, 12); path.lineTo(10, 19); canvas.drawPath(path, paint);
                canvas.drawLine(3, 12, 21, 12, paint);
            } else if ("rotate".equals(kind)) {
                canvas.drawRoundRect(6, 7, 18, 17, 2, 2, paint);
                canvas.drawArc(1, 1, 23, 23, 190, 110, false, paint);
                Path arrow = new Path(); arrow.moveTo(15, 1); arrow.lineTo(19, 3); arrow.lineTo(16, 6); canvas.drawPath(arrow, paint);
            } else if ("keyboard".equals(kind)) {
                canvas.drawRoundRect(2, 5, 22, 19, 2, 2, paint);
                paint.setStyle(Paint.Style.FILL);
                for (int row = 0; row < 2; row++) for (int col = 0; col < 4; col++) canvas.drawCircle(6 + col * 4, 9 + row * 3, .75f, paint);
                paint.setStyle(Paint.Style.STROKE); canvas.drawLine(7, 16, 17, 16, paint);
            } else if ("stop".equals(kind)) {
                canvas.drawRoundRect(5, 5, 19, 19, 3, 3, paint);
            } else if ("chevron".equals(kind)) {
                Path path = new Path(); path.moveTo(7, 9); path.lineTo(12, 14); path.lineTo(17, 9); canvas.drawPath(path, paint);
            } else if ("display".equals(kind)) {
                canvas.drawRoundRect(2.5f, 4, 21.5f, 17, 2.5f, 2.5f, paint); canvas.drawLine(12, 17, 12, 21, paint); canvas.drawLine(7.5f, 21, 16.5f, 21, paint);
            } else if ("choice".equals(kind) || "chosen".equals(kind)) {
                canvas.drawCircle(12, 12, 8, paint);
                if ("chosen".equals(kind)) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 4, paint); }
            } else {
                canvas.drawRoundRect(3, 3, 21, 21, 5, 5, paint); canvas.drawCircle(12, 10, 3, paint); canvas.drawArc(7, 13, 17, 23, 200, 140, false, paint);
            }
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
