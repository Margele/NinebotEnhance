import dev.ichinomiya.ninebotenhance.core.UpdateCheck;
import java.util.Arrays;

public final class UpdateTests {
    private static void check(boolean value, String description) { CoreTests.check(value, description); }
    static void run() {
        check(Arrays.equals(UpdateCheck.parse("v1.2.3"), new int[]{1, 2, 3}) && Arrays.equals(UpdateCheck.parse("1.2"), new int[]{1, 2})
                && Arrays.equals(UpdateCheck.parse("1.2.0-beta"), new int[]{1, 2, 0}) && Arrays.equals(UpdateCheck.parse(" V2.0 "), new int[]{2, 0}), "tags parse with or without the v prefix and ignore a suffix");
        check(UpdateCheck.parse("latest") == null && UpdateCheck.parse("") == null && UpdateCheck.parse(null) == null && UpdateCheck.parse("v") == null && UpdateCheck.parse("1..2") == null, "text without a version is not a version");
        check(UpdateCheck.newer("v1.1.6", "1.1.5") && UpdateCheck.newer("1.2", "1.1.9") && UpdateCheck.newer("2.0.0", "1.9.9") && UpdateCheck.newer("1.1.5.1", "1.1.5")
                && !UpdateCheck.newer("1.1.5", "1.1.5") && !UpdateCheck.newer("1.1.4", "1.1.5") && !UpdateCheck.newer("1.1.5.0", "1.1.5"), "numeric comparison decides newer, equal counts as current");
        check(!UpdateCheck.newer("beta", "1.1.5") && !UpdateCheck.newer("1.2.0", "unknown") && !UpdateCheck.newer(null, "1.1.5"), "an unparseable side never prompts");
        String json = "{\"tag_name\":\"v1.1.6\",\"html_url\":\"https://github.com/Margele/NinebotEnhance/releases/tag/v1.1.6\",\"draft\":false,\"prerelease\":false,"
                + "\"assets\":[{\"name\":\"NinebotEnhance-1.1.6.apk\",\"size\":468000}],\"body\":\"# 更新日志\\n- 换行 \\u4e2d \\\"引号\\\"\"}";
        UpdateCheck.Release release = UpdateCheck.fromJson(json);
        check(release != null && release.version().equals("1.1.6") && release.url().endsWith("/tag/v1.1.6"), "the release answer reduces to version and page");
        check(UpdateCheck.fromJson("{\"tag_name\":\"v1.1.6\",\"prerelease\":true}") == null && UpdateCheck.fromJson("{\"tag_name\":\"v1.1.6\",\"draft\":true}") == null, "drafts and pre-releases are ignored");
        check(UpdateCheck.fromJson("{\"message\":\"Not Found\"}") == null && UpdateCheck.fromJson("not json") == null && UpdateCheck.fromJson("[]") == null && UpdateCheck.fromJson(null) == null, "answers without a tag or malformed answers yield nothing");
        UpdateCheck.Release bare = UpdateCheck.fromJson("{\"tag_name\":\"1.2.0\"}");
        check(bare != null && bare.version().equals("1.2.0") && bare.url().equals(UpdateCheck.RELEASES_URL), "a missing page falls back to the releases list");
        check(UpdateCheck.RELEASES_URL.startsWith("https://github.com/") && UpdateCheck.LATEST_API.startsWith("https://api.github.com/repos/"), "only GitHub endpoints are used");
    }
}
