package dev.ichinomiya.ninebotenhance.ui;

import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.HiddenFeatures;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.*;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * The module's button row on the vehicle page: virtual display, cast, settings. It sits in the page column right above Ninebot's
 * location card (车辆定位 / 最近骑行 / 总里程: layout_detail_location_card, or the multi-card layout_detail_location_card_item),
 * which the page shows whether or not the vehicle is connected, so the virtual display can be started offline. The navigation card
 * (layout_detail_navigation_card, res/0oW.xml, identical in 6.10.10 and 6.10.11) only appears while the vehicle is connected; it
 * still supplies the cruise entry a cast needs, and it is the fallback anchor for a page without a location card. The page is walked
 * from its decor view: right after either card inflates and, as before, every 1.2 s while a host activity is in front.
 */
public final class VehicleCardInjector {
    private static final String MARKER = "dev.ichinomiya.ninebotenhance.direct-button";
    private static final String HARDKEY_MARKER = MARKER + ".hardkey";
    public static final String NAVIGATION_CARD = "layout_detail_navigation_card", LOCATION_CARD = "layout_detail_location_card", LOCATION_ITEM = "layout_detail_location_card_item";
    private static final long REFRESH_MS = 1000, FALLBACK_DELAY_MS = 1500;
    private static final int SCAN_BUDGET = 2500;
    /** Ninebot's own inner-control look on the vehicle page: color_select_bg on a 15 dp radius, no outline (background_card_gray_r15). */
    private static final int NINEBOT_FILL_DARK = 0xff1c1f24, NINEBOT_FILL_LIGHT = 0xfff3f5f8, NINEBOT_RADIUS_DP = 15;
    /** The ownership-days line at the bottom of the vehicle page; its text is data-driven, so it is matched by content. */
    private static final Pattern OWNERSHIP = Pattern.compile("拥有爱车");
    /** One installed row; {@code anchor} is the card it was placed against and doubles as the theme reference. */
    public record Row(LinearLayout view, ImageButton display, Button cast, Button settings, View anchor, boolean fallback, MirrorUi theme) {}
    private final WeakHashMap<View, Boolean> ownershipLabels = new WeakHashMap<>();
    private final WeakHashMap<View, Row> rows = new WeakHashMap<>();
    private WeakReference<View> navigationCard = new WeakReference<>(null);
    private final DirectCastController controller;
    private final FrameClient frames;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::refresh;
    private String lastEntry = "", lastAnchor = "";
    private boolean factoryReported;
    public VehicleCardInjector(DirectCastController controller, FrameClient frames) { this.controller = controller; this.frames = frames; }
    /** Either card inflating is the earliest moment the page can be walked; the periodic walk covers everything else. */
    public void inflated(String layout, View view) {
        if (!LOCATION_CARD.equals(layout) && !LOCATION_ITEM.equals(layout) && !NAVIGATION_CARD.equals(layout)) return;
        Activity activity = activity(view.getContext());
        if (activity != null) view.post(() -> scanPage(activity));
    }
    /** Walks the activity's whole window: finds the cards, installs or moves the row, wires the ownership line. */
    public void scanPage(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getWindow() == null) return;
        try { scan(activity.getWindow().getDecorView()); }
        catch (RuntimeException e) { frames.report("DIRECT UI scan " + e.getClass().getSimpleName()); }
    }
    /** Whether this activity shows the module's row, i.e. it is the vehicle page. */
    public boolean rowIn(Activity activity) {
        for (Row row : new ArrayList<>(rows.values())) if (row.view().isAttachedToWindow() && activity(row.view().getContext()) == activity) return true;
        return false;
    }
    /** Ninebot's navigation card, if the page currently shows one. */
    public View navigationCard() { return navigationCard.get(); }
    /** The original cruise entry a cast can click right now: the card is on screen in this activity and the button is live. */
    public View cruiseEntry(Activity activity) {
        View card = navigationCard.get();
        if (card == null || !card.isAttachedToWindow() || !card.isShown()) return null;
        if (activity != null && activity(card.getContext()) != activity) return null;
        View cruise = cruise(card);
        return cruise != null && cruise.isShown() && cruise.isEnabled() && cruise.hasOnClickListeners() ? cruise : null;
    }
    private void scan(View root) {
        if (root == null) return;
        View navigation = null, history = null, anchor = null;
        ArrayDeque<View> queue = new ArrayDeque<>(); queue.add(root);
        for (int count = 0; !queue.isEmpty() && count < SCAN_BUDGET; count++) {
            View view = queue.removeFirst();
            if (MARKER.equals(view.getTag()) || HARDKEY_MARKER.equals(view.getTag())) continue;
            if (view instanceof TextView && !(view instanceof Button)) ownershipEntry((TextView)view);
            String name = name(view);
            if (view instanceof ViewGroup && name.equals("vMainContainer")) {
                View cruise = child((ViewGroup)view, "ivCruise");
                View pastRides = child((ViewGroup)view, "layoutHistory");
                View places = child((ViewGroup)view, "layoutNavigation");
                if (cruise != null && pastRides != null && places != null) { navigation = view; history = pastRides; continue; }
            }
            // The map mask sits in both location card layouts; the map itself is heavy and never holds anything of ours.
            if (name.equals("mapMask") || name.equals("mapContainer") || name.equals("mapContainer2")) {
                if (anchor == null && view.isAttachedToWindow()) anchor = topLevelCard(view);
                continue;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup)view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        navigationCard = new WeakReference<>(navigation);
        String found = "anchor=" + (anchor == null ? "none" : anchor.getClass().getSimpleName() + " in " + (anchor.getParent() == null ? "?" : anchor.getParent().getClass().getSimpleName())) + " navigation=" + (navigation != null);
        if (!found.equals(lastAnchor)) { lastAnchor = found; frames.report("DIRECT UI " + found); }
        Row live = liveRow(root);
        if (anchor != null) {
            if (live == null || live.fallback() || !directlyAbove(live.view(), anchor)) installAbove(anchor);
        } else if (navigation != null && live == null) {
            // The location card normally shows up within the same page build; only a page without one gets the old anchor.
            View card = navigation, pastRides = history;
            card.postDelayed(() -> { if (card.isAttachedToWindow() && liveRow(card.getRootView()) == null) installInside((ViewGroup)card, pastRides); }, FALLBACK_DELAY_MS);
        }
        refresh();
    }
    /** The row must sit right above the anchor, allowing only the hard-key card in between. */
    private static boolean directlyAbove(View row, View anchor) {
        if (row.getParent() == null || row.getParent() != anchor.getParent()) return false;
        ViewGroup parent = (ViewGroup)row.getParent();
        for (int i = parent.indexOfChild(row) + 1; i < parent.getChildCount(); i++) {
            View next = parent.getChildAt(i);
            if (HARDKEY_MARKER.equals(next.getTag())) continue;
            return next == anchor;
        }
        return false;
    }
    /**
     * The page column is a vertical LinearLayout directly inside a scroll view; the anchor is the column's child on the way up from
     * the marker. A page built differently falls back to the nearest vertical column holding at least three cards.
     */
    private static View topLevelCard(View marker) {
        View child = marker, fallback = null;
        for (ViewParent p = marker.getParent(); p instanceof View; p = ((View)p).getParent()) {
            View parent = (View)p;
            boolean column = parent instanceof LinearLayout && ((LinearLayout)parent).getOrientation() == LinearLayout.VERTICAL;
            if (column && scrollView(parent.getParent())) return child;
            if (column && fallback == null && ((ViewGroup)parent).getChildCount() >= 3) fallback = child;
            child = parent;
        }
        return fallback;
    }
    private static boolean scrollView(ViewParent parent) {
        for (Class<?> type = parent == null ? null : parent.getClass(); type != null; type = type.getSuperclass()) if (type.getSimpleName().endsWith("ScrollView")) return true;
        return false;
    }
    private Row liveRow(View rootView) {
        for (Row row : new ArrayList<>(rows.values())) if (row.view().isAttachedToWindow() && row.view().getRootView() == rootView) return row;
        return null;
    }
    /** Takes a row out again, undoing the navigation card's constraint change when it was the fallback anchor. */
    private void removeRow(Row row) {
        if (!(row.view().getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup)row.view().getParent();
        parent.removeView(row.view());
        for (int i = parent.getChildCount() - 1; i >= 0; i--) if (HARDKEY_MARKER.equals(parent.getChildAt(i).getTag())) parent.removeViewAt(i);
        if (row.fallback()) {
            View history = child(parent, "layoutHistory");
            if (history != null) try { ViewGroup.LayoutParams params = history.getLayoutParams(); set(params.getClass(), params, "bottomToBottom", 0); history.setLayoutParams(params); }
            catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        rows.remove(row.view());
    }
    /** The row goes into the page column right before the location card; anything installed earlier in this window goes away first. */
    private void installAbove(View card) {
        if (!(card.getParent() instanceof LinearLayout) || !card.isAttachedToWindow()) return;
        Activity activity = activity(card.getContext());
        if (activity == null || activity.isFinishing()) return;
        LinearLayout group = (LinearLayout)card.getParent();
        for (Row row : new ArrayList<>(rows.values())) if (row.view().getRootView() == card.getRootView() || !row.view().isAttachedToWindow()) removeRow(row);
        int index = group.indexOfChild(card);
        LinearLayout row;
        try {
            row = buildRow(card, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int start = card.getPaddingStart(), end = card.getPaddingEnd();
            if (card.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams cardParams = (ViewGroup.MarginLayoutParams)card.getLayoutParams();
                start += cardParams.getMarginStart(); end += cardParams.getMarginEnd();
            }
            params.setMarginStart(start); params.setMarginEnd(end); params.topMargin = dp(card, 10);
            group.addView(row, index, params);
        } catch (RuntimeException e) { frames.report("DIRECT UI insertion above the location card failed " + e.getClass().getSimpleName()); return; }
        frames.report("DIRECT UI installed above the location card (" + card.getClass().getSimpleName() + ") at index " + index + "; " + entryInfo(navigationCard.get()));
        refresh();
    }
    /** The pre-1.1.5 placement inside the navigation card below layoutHistory, used only when the page has no location card. */
    private void installInside(ViewGroup card, View history) {
        for (int i = 0; i < card.getChildCount(); i++) if (MARKER.equals(card.getChildAt(i).getTag())) { refresh(); return; }
        if (!card.getClass().getName().equals("androidx.constraintlayout.widget.ConstraintLayout")) return;
        Activity activity = activity(card.getContext());
        if (activity == null || activity.isFinishing()) return;
        ViewGroup.LayoutParams original = history.getLayoutParams();
        LinearLayout row = null;
        try {
            Class<?> params = original.getClass();
            if (!params.getName().equals("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")) return;
            // Preserve the original params object so a failed insertion has a complete rollback.
            java.lang.reflect.Constructor<?> copy;
            try { copy = params.getConstructor(params); }
            catch (NoSuchMethodException e) { copy = params.getConstructor(ViewGroup.LayoutParams.class); }
            ViewGroup.LayoutParams historyParams = (ViewGroup.LayoutParams)copy.newInstance(original);
            if (params.getField("bottomToBottom").getInt(original) != 0) return;
            params.getField("bottomToBottom").setInt(historyParams, -1);
            ViewGroup.MarginLayoutParams buttonParams = (ViewGroup.MarginLayoutParams)params.getConstructor(int.class, int.class).newInstance(0, -2);
            set(params, buttonParams, "startToStart", 0); set(params, buttonParams, "endToEnd", 0);
            set(params, buttonParams, "topToBottom", history.getId()); set(params, buttonParams, "bottomToBottom", 0);
            buttonParams.topMargin = dp(card, 12);
            row = buildRow(card, true);
            history.setLayoutParams(historyParams); card.addView(row, buttonParams);
            frames.report("DIRECT UI installed inside " + NAVIGATION_CARD + " below layoutHistory; " + entryInfo(card));
            refresh();
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (row != null && row.getParent() == card) card.removeView(row);
            history.setLayoutParams(original);
            frames.report("DIRECT UI insertion failed " + e.getClass().getSimpleName());
        }
    }
    private LinearLayout buildRow(View reference, boolean fallback) {
        Context context = reference.getContext();
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setId(View.generateViewId()); row.setTag(MARKER);
        MirrorUi theme = new MirrorUi(context, reference);
        int fill = theme.dark ? NINEBOT_FILL_DARK : NINEBOT_FILL_LIGHT;
        // The display control is a small icon-only square at the start; its glyph turns accent while the display runs.
        ImageButton display = new ImageButton(context); display.setId(View.generateViewId());
        display.setScaleType(ImageView.ScaleType.FIT_CENTER); display.setBackground(plain(theme, context, fill));
        int inset = dp(reference, 13); display.setPadding(inset, inset, inset, inset);
        Button cast = button(theme, context, fill), settings = button(theme, context, fill);
        cast.setText("全屏投屏"); settings.setText("设置");
        display.setOnClickListener(v -> controller.clickDisplay(activity(v.getContext()), reference));
        cast.setOnClickListener(v -> controller.clickCast(activity(v.getContext()), reference));
        settings.setOnClickListener(v -> controller.settings(activity(v.getContext()), reference));
        for (View button : new View[]{display, cast, settings})
            button.setOnLongClickListener(v -> { controller.entryDetails(activity(v.getContext()), reference); return true; });
        int height = dp(reference, 50), gap = dp(reference, 8);
        LinearLayout.LayoutParams displayParams = new LinearLayout.LayoutParams(height, height);
        LinearLayout.LayoutParams castParams = new LinearLayout.LayoutParams(0, height, 1); castParams.setMarginStart(gap);
        LinearLayout.LayoutParams settingParams = new LinearLayout.LayoutParams(dp(reference, 84), height); settingParams.setMarginStart(gap);
        row.addView(display, displayParams); row.addView(cast, castParams); row.addView(settings, settingParams);
        rows.put(row, new Row(row, display, cast, settings, reference, fallback, theme));
        return row;
    }
    private static Button button(MirrorUi theme, Context context, int fill) {
        Button button = new Button(context); button.setId(View.generateViewId());
        theme.button(button, null); button.setMaxLines(1); button.setBackground(plain(theme, context, fill));
        button.setPadding(dp(button, 6), button.getPaddingTop(), dp(button, 6), button.getPaddingBottom());
        button.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, TypedValue.COMPLEX_UNIT_SP);
        return button;
    }
    private static RippleDrawable plain(MirrorUi theme, Context context, int fill) {
        return new RippleDrawable(ColorStateList.valueOf(0x22888899), theme.background(context, fill, NINEBOT_RADIUS_DP, false), null);
    }
    /** Button faces follow the session and the cruise entry; refreshed on every event and once a second while a row is on screen. */
    public void refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::refresh); return; }
        main.removeCallbacks(tick);
        List<Row> live = new ArrayList<>();
        for (Row row : new ArrayList<>(rows.values())) if (row.view().isAttachedToWindow()) live.add(row);
        String info = entryInfo(navigationCard.get());
        if (!info.equals(lastEntry)) { lastEntry = info; frames.report("DIRECT ENTRY " + info); }
        for (Row row : live) {
            try { controller.decorate(row); hardkey(row); }
            catch (RuntimeException e) { frames.report("DIRECT UI refresh " + e.getClass().getSimpleName()); }
        }
        if (!live.isEmpty()) main.postDelayed(tick, REFRESH_MS);
    }
    /**
     * Ninebot's own hard-key remote card (view type ext_meter_virtual_key) right below the module row, built by the page's view
     * factory with the page's device identity; removed again when the unlock is switched off. Taps on it are Ninebot's own commands.
     */
    private void hardkey(Row row) {
        if (!(row.view().getParent() instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup)row.view().getParent();
        View existing = null;
        for (int i = 0; i < group.getChildCount(); i++) if (HARDKEY_MARKER.equals(group.getChildAt(i).getTag())) existing = group.getChildAt(i);
        boolean wanted = frames.hiddenFeatures().hardkey();
        if (row.fallback()) { hardkeyConstraint(group, row.view(), existing, wanted); return; }
        if (!wanted) { if (existing != null) { group.removeView(existing); frames.report("FEATURE hardkey card removed"); } return; }
        if (existing != null) return;
        FrameClient.DynamicViewFactory factory = frames.dynamicViewFactory();
        if (factory == null) { if (!factoryReported) { factoryReported = true; frames.report("FEATURE hardkey card waits for the page factory"); } return; }
        try {
            View view = factory.create(group, HiddenFeatures.HARDKEY_TYPE, HiddenFeatures.HARDKEY_CONFIG);
            // An unsupported type yields Ninebot's plain TextView placeholder; never mount that.
            if (view == null || view.getClass() == android.widget.TextView.class) { frames.report("FEATURE hardkey card not built"); return; }
            if (view.getId() == View.NO_ID) view.setId(View.generateViewId());
            view.setTag(HARDKEY_MARKER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams((LinearLayout.LayoutParams)row.view().getLayoutParams());
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            group.addView(view, group.indexOfChild(row.view()) + 1, params);
            frames.report("FEATURE hardkey card installed " + view.getClass().getSimpleName());
        } catch (ReflectiveOperationException | RuntimeException e) { frames.report("FEATURE hardkey card failed " + e.getClass().getSimpleName()); }
    }
    private void hardkeyConstraint(ViewGroup card, LinearLayout row, View existing, boolean wanted) {
        ViewGroup.LayoutParams rowParams = row.getLayoutParams(); Class<?> params = rowParams.getClass();
        try {
            if (!wanted) {
                if (existing != null) { card.removeView(existing); set(params, rowParams, "bottomToBottom", 0); row.setLayoutParams(rowParams); frames.report("FEATURE hardkey card removed"); }
                return;
            }
            if (existing != null) return;
            FrameClient.DynamicViewFactory factory = frames.dynamicViewFactory();
            if (factory == null) { if (!factoryReported) { factoryReported = true; frames.report("FEATURE hardkey card waits for the page factory"); } return; }
            View view = factory.create(card, HiddenFeatures.HARDKEY_TYPE, HiddenFeatures.HARDKEY_CONFIG);
            if (view == null || view.getClass() == android.widget.TextView.class) { frames.report("FEATURE hardkey card not built"); return; }
            ViewGroup.MarginLayoutParams cardParams = (ViewGroup.MarginLayoutParams)params.getConstructor(int.class, int.class).newInstance(0, -2);
            set(params, cardParams, "startToStart", 0); set(params, cardParams, "endToEnd", 0);
            set(params, cardParams, "topToBottom", row.getId()); set(params, cardParams, "bottomToBottom", 0);
            cardParams.topMargin = dp(card, 12);
            if (view.getId() == View.NO_ID) view.setId(View.generateViewId());
            view.setTag(HARDKEY_MARKER);
            set(params, rowParams, "bottomToBottom", -1); row.setLayoutParams(rowParams);
            card.addView(view, cardParams);
            frames.report("FEATURE hardkey card installed " + view.getClass().getSimpleName());
        } catch (ReflectiveOperationException | RuntimeException e) {
            try { set(params, rowParams, "bottomToBottom", 0); row.setLayoutParams(rowParams); } catch (ReflectiveOperationException | RuntimeException ignored) {}
            frames.report("FEATURE hardkey card failed " + e.getClass().getSimpleName());
        }
    }
    /** A second way into the settings: tapping the ownership-days line opens the same dialog as the row button. */
    private void ownershipEntry(TextView label) {
        if (ownershipLabels.containsKey(label)) return;
        CharSequence value = label.getText(); if (value == null || !OWNERSHIP.matcher(value).find()) return;
        if (label.hasOnClickListeners()) { ownershipLabels.put(label, Boolean.FALSE); frames.report("DIRECT UI ownership label already clickable; left alone"); return; }
        label.setOnClickListener(v -> { Activity activity = activity(v.getContext()); if (activity != null && !activity.isFinishing()) controller.settings(activity, v); });
        ownershipLabels.put(label, Boolean.TRUE); frames.report("DIRECT UI ownership label doubles as a settings entry");
    }
    private static void set(Class<?> type, Object value, String name, int number) throws ReflectiveOperationException { type.getField(name).setInt(value, number); }
    public static View child(ViewGroup group, String name) {
        for (int i = 0; i < group.getChildCount(); i++) if (name(group.getChildAt(i)).equals(name)) return group.getChildAt(i);
        return null;
    }
    public static String name(View view) {
        if (view.getId() == View.NO_ID) return "";
        try { return view.getResources().getResourceEntryName(view.getId()); } catch (RuntimeException e) { return ""; }
    }
    public static Activity activity(Context context) {
        for (int i = 0; context != null && i < 12; i++) {
            if (context instanceof Activity) return (Activity)context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper)context).getBaseContext(); if (next == context) break; context = next;
        }
        return null;
    }
    public static View cruise(View card) {
        return card instanceof ViewGroup ? child((ViewGroup)card, "ivCruise") : null;
    }
    public static String entryInfo(View card) {
        View cruise = cruise(card);
        return "cardAttached=" + (card != null && card.isAttachedToWindow())
                + " cardShown=" + (card != null && card.isShown())
                + " cruiseFound=" + (cruise != null)
                + (cruise == null ? "" : " cruiseAttached=" + cruise.isAttachedToWindow()
                + " cruiseVisibility=" + cruise.getVisibility() + " cruiseShown=" + cruise.isShown()
                + " cruiseEnabled=" + cruise.isEnabled() + " cruiseListener=" + cruise.hasOnClickListeners());
    }
    private static int dp(View view, int size) { return Math.round(size * view.getResources().getDisplayMetrics().density); }
}
