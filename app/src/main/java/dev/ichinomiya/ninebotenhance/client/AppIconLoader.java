package dev.ichinomiya.ninebotenhance.client;

import dev.ichinomiya.ninebotenhance.ipc.Ipc;

import dev.ichinomiya.ninebotenhance.client.ServiceBridge;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.platform.AppCatalog;

import android.graphics.Bitmap;
import android.os.*;
import android.util.LruCache;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, lazy icon IPC; never shares capture/control/settings workers or sends all icons in one Bundle. */
public final class AppIconLoader {
    private final ServiceBridge bridge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<>(48);
    private final Map<String, ArrayList<WeakReference<ImageView>>> pending = new HashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), action -> { Thread thread = new Thread(action, "Mirror-AppIcons"); thread.setDaemon(true); return thread; });
    public AppIconLoader(ServiceBridge bridge) { this.bridge = bridge; }
    public void load(String component, ImageView target) {
        target.setTag(component);
        Bitmap cached = cache.get(component);
        if (cached != null) { target.setImageBitmap(cached); return; }
        ArrayList<WeakReference<ImageView>> waiting = pending.get(component);
        if (waiting != null) { waiting.add(new WeakReference<>(target)); return; }
        waiting = new ArrayList<>(); waiting.add(new WeakReference<>(target)); pending.put(component, waiting);
        try { worker.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (bridge.connected()) {
                    Bundle args = new Bundle(); args.putString(AppCatalog.SELECTED, component);
                    bitmap = Ipc.parcelable(bridge.call(Protocol.APP_ICON, args), "icon", Bitmap.class);
                }
            } catch (Exception ignored) { /* Keep the generic icon; settings and selection remain usable. */ }
            Bitmap result = bitmap;
            main.post(() -> {
                ArrayList<WeakReference<ImageView>> views = pending.remove(component);
                if (result == null) return;
                cache.put(component, result);
                if (views != null) for (WeakReference<ImageView> reference : views) {
                    ImageView view = reference.get();
                    if (view != null && component.equals(view.getTag())) view.setImageBitmap(result);
                }
            });
        }); } catch (RejectedExecutionException ignored) { pending.remove(component); }
    }
}
