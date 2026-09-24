package dev.ichinomiya.ninebotenhance.core;

import java.util.Map;

/**
 * Release lookup against GitHub, kept free of Android: tags compared numerically ("v1.2.3" and "1.2.3" alike), the API answer
 * reduced to the tag and the page to open. The module never downloads anything; the user is only pointed at the Releases page.
 */
public final class UpdateCheck {
    public static final String RELEASES_URL = OpenSourceNotice.REPOSITORY + "/releases";
    public static final String LATEST_API = "https://api.github.com/repos/Margele/NinebotEnhance/releases/latest";
    /** How often the module asks GitHub at most; a failed attempt waits an hour before the next. */
    public static final long INTERVAL_MS = 6 * 3_600_000L, RETRY_MS = 3_600_000L;
    public record Release(String version, String url) {}
    /** "v1.2.3", "1.2.3" or "1.2.3-beta" to its numeric parts; null when the text carries no version. */
    public static int[] parse(String tag) {
        if (tag == null) return null;
        String text = tag.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        int end = 0;
        while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) end++;
        String[] parts = text.substring(0, end).split("\\.");
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) return null;
            try { numbers[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException e) { return null; }
        }
        return numbers;
    }
    /** Negative, zero or positive as {@code a} is older than, equal to or newer than {@code b}; missing parts count as zero. */
    public static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }
    public static boolean newer(String latest, String current) {
        int[] l = parse(latest), c = parse(current);
        return l != null && c != null && compare(l, c) > 0;
    }
    /** The GitHub release object reduced to what the prompt needs; null for drafts, pre-releases or an answer without a tag. */
    public static Release fromJson(String json) {
        Object root;
        try { root = Json.parse(json); } catch (RuntimeException e) { return null; }
        Map<String, Object> release = Json.object(root);
        if (release == null) return null;
        if (Boolean.TRUE.equals(release.get("draft")) || Boolean.TRUE.equals(release.get("prerelease"))) return null;
        String tag = Json.string(release.get("tag_name"));
        if (parse(tag) == null) return null;
        String url = Json.string(release.get("html_url"));
        String version = tag.trim();
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        return new Release(version, url == null || url.isEmpty() ? RELEASES_URL : url);
    }
    private UpdateCheck() {}
}
