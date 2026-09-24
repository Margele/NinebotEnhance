import dev.ichinomiya.ninebotenhance.core.DirectSession;
import dev.ichinomiya.ninebotenhance.hook.BooleanResultObserver;
import dev.ichinomiya.ninebotenhance.hook.HookPolicy;
import java.util.ArrayList;
import java.util.List;
import static java.lang.Boolean.*;

public final class VehicleStartTests {
    private static void check(boolean value, String description) { CoreTests.check(value, description); }
    public interface Continuation { Object getContext(); void resumeWith(Object result); }
    public static final class RecordingContinuation implements Continuation {
        final Object context = new Object(); Object result; int calls;
        public Object getContext() { return context; }
        public void resumeWith(Object value) { result = value; calls++; }
    }
    public static final class StateOwner { public static final class isPowerOn$1 {} }
    static void run() {
        String a = "0123456789abcdef0123456789abcdef", b = "fedcba9876543210fedcba9876543210";
        DirectSession session = new DirectSession();
        check(session.beginCast(a) && session.cast() == DirectSession.Cast.CHECKING_VEHICLE && a.equals(session.vehicleCheckRequest()) && !session.displayRunning(), "a cast starts with the vehicle check and no display allocation");
        check(!session.vehicleConfirmed(a) && !session.launch(a), "unknown vehicle state blocks the cast even if the module is authorized");
        check(session.cruiseReady(a) && !session.vehicleConfirmed(a), "an activity alone is not proof of vehicle power");
        check(session.powerChecked(a, false) && !session.vehicleConfirmed(a), "vehicle off cannot bind a display");
        check(session.powerChecked(a, true) && !session.vehicleConfirmed(a), "concurrent positive query cannot override rejection");
        check(session.endCast(a) && session.beginCast(b), "failed check can be retried with fresh request");
        check(!session.powerChecked(a, true) && !session.cruiseReady(a) && !session.vehicleConfirmed(a), "old callbacks cannot authorize another vehicle attempt");
        check(session.powerChecked(b, true) && !session.vehicleConfirmed(b), "power alone cannot bypass original cruise checks");
        check(session.cruiseReady(b) && session.vehicleConfirmed(b) && session.cast() == DirectSession.Cast.WAITING_DISPLAY, "both prerequisites grant exactly one binding");
        check(!session.vehicleConfirmed(b) && !session.powerChecked(b, true) && session.vehicleCheckRequest() == null, "repeated callbacks do not confirm twice");
        check(!session.launch(b), "a confirmed cast waits for a display");
        String d = "00112233445566778899aabbccddeeff";
        check(session.beginDisplay(d) && session.display() == DirectSession.Display.STARTING && !session.launch(b), "a starting display is not bound yet");
        check(!session.beginDisplay(a) && session.displayReady(d) && session.launch(b) && session.running(b) && session.cast() == DirectSession.Cast.RUNNING, "one display at a time; the ready display carries the cast through frames and capture");
        check(session.endCast(b) && session.displayRunning() && d.equals(session.displayRequest()) && !session.casting(), "stopping the cast leaves the display running");
        check(session.beginCast(a) && session.powerChecked(a, true) && session.cruiseReady(a) && session.vehicleConfirmed(a) && session.launch(a) && session.running(a), "the running display takes a second cast without restarting");
        check(session.endDisplay(d) && !session.casting() && !session.displayRunning() && !session.running(a), "closing the display drops the cast bound to it");
        check(!session.endDisplay(d) && !session.displayReady(d), "stale display callbacks are ignored");
        check(session.beginDisplay(a) && !session.casting() && session.vehicleCheckRequest() == null, "the display starts on its own without any vehicle check");
        check(!session.powerChecked(a, false) && !session.cruiseReady(a), "vehicle callbacks cannot affect a display without a cast");
        check(session.displayReady(a) && session.display() == DirectSession.Display.READY && session.endDisplay(a), "the display works with the vehicle off or absent");
        List<Boolean> observed = new ArrayList<>();
        BooleanResultObserver observer = new BooleanResultObserver(observed::add);
        observer.returned(null); observer.returned(new Object()); observer.returned(new IllegalStateException());
        check(observed.isEmpty(), "suspended, failure and unknown results never become power on");
        observer.returned(FALSE); observer.returned(TRUE);
        check(observed.equals(List.of(FALSE)), "synchronous rejection observed once unchanged");
        observed.clear(); observer = new BooleanResultObserver(observed::add);
        RecordingContinuation original = new RecordingContinuation();
        Continuation proxy = (Continuation) observer.continuation(Continuation.class, original);
        check(proxy.getContext() == original.context, "wrapped continuation retains original coroutine context");
        Object failure = new Object(); proxy.resumeWith(failure);
        check(original.calls == 1 && original.result == failure && observed.isEmpty(), "failed coroutine result forwarded unchanged and cannot authorize");
        // A separate successful coroutine, not a second resume of the failed one.
        original = new RecordingContinuation();
        observer = new BooleanResultObserver(observed::add);
        proxy = (Continuation) observer.continuation(Continuation.class, original);
        observer.returned(new Object()); proxy.resumeWith(TRUE); observer.returned(TRUE);
        check(original.calls == 1 && original.result == TRUE && observed.equals(List.of(TRUE)), "asynchronous Boolean forwarded exactly once and observed once");
        observed.clear();
        IllegalStateException exception = new IllegalStateException("original failure");
        Continuation throwing = new Continuation() {
            public Object getContext() { throw exception; }
            public void resumeWith(Object value) { throw exception; }
        };
        proxy = (Continuation) new BooleanResultObserver(observed::add).continuation(Continuation.class, throwing);
        try { proxy.resumeWith(TRUE); check(false, "original exception expected"); }
        catch (IllegalStateException e) { check(e == exception && observed.isEmpty(), "reflection unwraps the exact original exception without granting"); }
        RecordingContinuation unchanged = new RecordingContinuation();
        proxy = (Continuation) new BooleanResultObserver(value -> { throw new IllegalStateException(); }).continuation(Continuation.class, unchanged);
        proxy.resumeWith(TRUE);
        check(unchanged.calls == 1 && unchanged.result == TRUE, "observer errors do not alter original continuation completion");
        check(BooleanResultObserver.ownStateMachine(StateOwner.class.getName(), "isPowerOn", new StateOwner.isPowerOn$1()), "suspend state machine reentry is excluded from wrapping");
        check(!BooleanResultObserver.ownStateMachine(StateOwner.class.getName(), "isPowerOn", unchanged), "caller continuation may be observed on initial invocation");
        String messenger = "cn.ninebot.device.motor.navi.DashNaviDataMessenger$Companion";
        check(HookPolicy.interestingClass(messenger) && HookPolicy.vehiclePowerMethod(messenger, "isPowerOn"), "known original power predicate is observable");
        check(!HookPolicy.vehiclePowerMethod(messenger, "setPowerOn") && !HookPolicy.vehiclePowerMethod("other.Vehicle", "isPowerOn"), "observer excludes commands and unrelated app methods");
    }
}
