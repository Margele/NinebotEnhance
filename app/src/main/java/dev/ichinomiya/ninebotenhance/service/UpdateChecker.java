package dev.ichinomiya.ninebotenhance.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import dev.ichinomiya.ninebotenhance.core.UpdateCheck;
import dev.ichinomiya.ninebotenhance.diagnostics.Diagnostics;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The module's only network access: at most one GET of GitHub's "latest release" endpoint every six hours, on a background
 * thread, answered from the cached result. Nothing is downloaded or sent; the host only learns the version and the page to open.
 */
public final class UpdateChecker {
    private static final String PREFERENCES = "update_check";
    private static UpdateChecker instance;
    public static synchronized UpdateChecker get(Context context) {
        if (instance == null) instance = new UpdateChecker(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private volatile boolean running;
    private UpdateChecker(Context context) { this.context = context; }
    /** The cached answer; a fresh lookup starts when it is stale (or {@code force}) and none is in flight. */
    public Bundle snapshot(boolean force) {
        SharedPreferences p = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), checked = p.getLong("checked_at", 0), failed = p.getLong("failed_at", 0);
        String latest = p.getString("latest", ""), url = p.getString("url", UpdateCheck.RELEASES_URL);
        Bundle result = new Bundle();
        result.putString("current", Protocol.VERSION); result.putString("latest", latest); result.putString("url", url);
        result.putLong("checked_at", checked);
        result.putBoolean("newer", !latest.isEmpty() && UpdateCheck.newer(latest, Protocol.VERSION));
        boolean stale = now - checked >= UpdateCheck.INTERVAL_MS && now - failed >= UpdateCheck.RETRY_MS;
        if ((force || stale) && !running) { running = true; new Thread(this::check, "Enhance-Update").start(); }
        result.putBoolean("checking", running);
        return result;
    }
    private void check() {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(UpdateCheck.LATEST_API).openConnection();
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "NinebotEnhance/" + Protocol.VERSION);
            int status = connection.getResponseCode();
            if (status != 200) throw new IllegalStateException("HTTP " + status);
            String body;
            try (InputStream in = connection.getInputStream()) { body = new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8); }
            UpdateCheck.Release release = UpdateCheck.fromJson(body);
            if (release == null) throw new IllegalStateException("no usable release in the answer");
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString("latest", release.version()).putString("url", release.url())
                    .putLong("checked_at", System.currentTimeMillis()).remove("failed_at").apply();
            Diagnostics.add("UPDATE latest=" + release.version() + " current=" + Protocol.VERSION + " newer=" + UpdateCheck.newer(release.version(), Protocol.VERSION));
        } catch (Exception e) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putLong("failed_at", System.currentTimeMillis()).apply();
            Diagnostics.add("UPDATE check failed " + Ipc.error(e));
        } finally { running = false; }
    }
}
