import dev.ichinomiya.ninebotenhance.core.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.io.File;
import java.util.*;

final class ProjectionTests {
    private static void check(boolean value, String message) { CoreTests.check(value, message); }
    static void run() throws Exception {
        for (boolean root : new boolean[]{false, true}) for (boolean shizuku : new boolean[]{false, true}) for (PrivilegeMode mode : PrivilegeMode.values()) {
            check(StartPermission.select(PictureSource.CAST, mode, shizuku, root) == StartPermission.Backend.MEDIA_PROJECTION,
                    "recording selects system consent regardless of privileged backends");
            check(StartPermission.select(PictureSource.DRAW, mode, shizuku, root) == StartPermission.Backend.DRAW, "drawing needs no backend at all");
            check(!StartPermission.needsRootCheck(PictureSource.CAST, mode, shizuku, root) && !StartPermission.needsRootCheck(PictureSource.DRAW, mode, shizuku, root),
                    "recording and drawing preflights never invoke su");
        }
        check(!PictureSource.CAST.virtual() && !PictureSource.DRAW.virtual() && PictureSource.VIRTUAL.virtual() && PictureSource.DRAW.draws() && PictureSource.CAST.captures(),
                "only the virtual display keeps the app and display settings");
        check(PictureSource.VIRTUAL.allowed(34) && !PictureSource.VIRTUAL.allowed(33) && PictureSource.CAST.allowed(30) && PictureSource.DRAW.allowed(30),
                "the virtual display is limited to Android 14, the other sources are not");
        check(PictureSource.migrate("NONE", 34) == PictureSource.CAST && PictureSource.migrate("AUTO", 34) == PictureSource.VIRTUAL
                && PictureSource.migrate("ROOT", 33) == PictureSource.CAST && PictureSource.migrate(null, 34) == PictureSource.CAST,
                "old privilege modes map to a picture source the phone can run");
        check(PictureSource.read("DRAW", PictureSource.CAST) == PictureSource.DRAW && PictureSource.read("x", PictureSource.CAST) == PictureSource.CAST
                && PictureSource.read(null, PictureSource.VIRTUAL) == PictureSource.VIRTUAL, "saved sources read with a fallback");
        CoreTests.rejects(() -> PictureSource.parse("AUTO"), "there is no automatic picture source");
        ProjectionGrant gate = new ProjectionGrant();
        check(!gate.consume() && !gate.ready() && !gate.open(true), "no consent means no token consumption, ready state or invented recreation");
        check(gate.open(false) && !gate.open(false), "one initial system picker per session");
        check(gate.open(true) && gate.open(true), "rotation can reattach without launching another picker");
        check(gate.consume() && !gate.consume(), "the result can obtain MediaProjection only once");
        check(!gate.open(false) && !gate.open(true), "consumed consent cannot be reopened or recreated");
        check(gate.ready() && !gate.ready(), "a grant creates exactly one active projection");
        gate.stop();
        check(!gate.ready() && !gate.consume() && !gate.open(true), "system stop never revives an invalid token");
        for (int phase = 0; phase < 3; phase++) {
            ProjectionGrant cancelled = new ProjectionGrant();
            if (phase > 0) cancelled.open(false);
            if (phase > 1) cancelled.consume();
            cancelled.stop();
            check(!cancelled.open(false) && !cancelled.open(true) && !cancelled.consume() && !cancelled.ready(),
                    "cancellation before consent, during picker and during startup rejects late callbacks");
        }
        ProjectionGrant newer = new ProjectionGrant();
        check(newer.open(false) && newer.consume() && newer.ready(), "new session requires and accepts fresh consent after a previous stop");
        for (int[] source : new int[][]{{1080, 2400}, {2400, 1080}, {2160, 2160}, {2520, 1116}, {1116, 2520},
                {848, 480}, {480, 848}, {335, 501}, {5000, 3000}, {100, 100}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            CaptureSize size = CaptureSize.fit(source[0], source[1]);
            check(size.width() <= CaptureSize.MAX_EDGE && size.height() <= CaptureSize.MAX_EDGE && (long) size.width() * size.height() <= CaptureSize.MAX_PIXELS,
                    "phone, tablet, folded and app-window captures keep buffers bounded");
            check(size.width() % 2 == 0 && size.height() % 2 == 0 && size.width() <= source[0] && size.height() <= source[1],
                    "automatic dimensions are even and never upscale normal source content");
            check(Math.abs((double) size.width() * source[1] - (double) size.height() * source[0]) < 4.0 * Math.max(source[0], source[1]),
                    "capture aspect ratio survives downscaling within even-pixel rounding");
            CaptureSize rotated = CaptureSize.fit(source[1], source[0]);
            check(rotated.width() == size.height() && rotated.height() == size.width(), "rotation changes the Surface dimensions, not the recording's orientation policy");
        }
        check(CaptureSize.fit(848, 480).equals(new CaptureSize(848, 480)), "small landscape app needs no rescale");
        CoreTests.rejects(() -> CaptureSize.fit(0, 480), "unknown content dimensions rejected");
        CoreTests.rejects(() -> CaptureSize.fit(480, -1), "negative content dimensions rejected");
        CoreTests.rejects(() -> new CaptureSize(1280, 1280), "excessive pixel allocation rejected");
        CoreTests.rejects(() -> new CaptureSize(849, 480), "odd IPC dimensions rejected");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document manifest = factory.newDocumentBuilder().parse(new File("app/src/main/AndroidManifest.xml"));
        NodeList activities = manifest.getElementsByTagName("activity");
        Element consent = null;
        for (int i = 0; i < activities.getLength(); i++) {
            Element item = (Element) activities.item(i);
            if (".ui.ScreenCaptureConsentActivity".equals(item.getAttribute("android:name"))) consent = item;
        }
        check(consent != null && ".ui.ScreenCaptureConsentActivity".equals(consent.getAttribute("android:name"))
                && "false".equals(consent.getAttribute("android:exported")) && "true".equals(consent.getAttribute("android:resizeableActivity"))
                && consent.getElementsByTagName("intent-filter").getLength() == 0,
                "recording consent remains private and resizable, with no launcher or public filter");
        Element service = null; NodeList services = manifest.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element candidate = (Element) services.item(i);
            if (".service.ScreenCaptureService".equals(candidate.getAttribute("android:name"))) service = candidate;
        }
        check(service != null && "false".equals(service.getAttribute("android:exported"))
                && "mediaProjection".equals(service.getAttribute("android:foregroundServiceType")), "recording service is private and uses the required foreground type");
        Set<String> permissions = new HashSet<>(); NodeList permissionNodes = manifest.getElementsByTagName("uses-permission");
        for (int i = 0; i < permissionNodes.getLength(); i++) permissions.add(((Element) permissionNodes.item(i)).getAttribute("android:name"));
        check(permissions.contains("android.permission.FOREGROUND_SERVICE") && permissions.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")
                && !permissions.contains("android.permission.RECORD_AUDIO"), "screen-only capture declares both foreground permissions without recording audio");
    }
}
