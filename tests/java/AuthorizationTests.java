import dev.ichinomiya.ninebotenhance.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

final class AuthorizationTests {
    static void check(boolean value, String label) { CoreTests.check(value, label); }
    static void await(BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        check(condition.getAsBoolean(), label);
    }
    interface Action { void run() throws Exception; }
    static void blocked(Action action, String label) throws Exception {
        boolean failed = false; try { action.run(); } catch (IllegalStateException expected) { failed = true; }
        check(failed, label);
    }
    static String marker(String command) {
        var match = java.util.regex.Pattern.compile("ENHANCE_[a-f0-9]{32}").matcher(command);
        if (!match.find()) throw new AssertionError("Missing command completion marker");
        return match.group();
    }
    static final class FakeProcess extends Process {
        final PipedInputStream input = new PipedInputStream(16384);
        final PipedOutputStream output = new PipedOutputStream(input);
        final BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        volatile boolean dead;
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        final OutputStream writer = new OutputStream() {
            @Override public synchronized void write(int value) throws IOException {
                if (dead) throw new IOException("Process closed");
                line.write(value);
                if (value == '\n') { commands.add(line.toString(StandardCharsets.UTF_8)); line.reset(); }
            }
        };
        FakeProcess() throws IOException {}
        String command() throws Exception { String text = commands.poll(2, TimeUnit.SECONDS); if (text == null) throw new AssertionError("No command"); return text; }
        void supply(String text) throws IOException { output.write((text + "\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }
        @Override public OutputStream getOutputStream() { return writer; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { while (!dead) Thread.sleep(1); return 0; }
        @Override public int exitValue() { if (!dead) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { dead = true; try { output.close(); } catch (IOException ignored) {} }
    }
    static void run() throws Exception {
        check(StartPermission.needsRootCheck(PictureSource.VIRTUAL, PrivilegeMode.ROOT, false, false), "lost Root connection triggers a real check without manual verification");
        check(StartPermission.needsRootCheck(PictureSource.VIRTUAL, PrivilegeMode.ROOT, true, false), "explicit Root mode checks Root even when Shizuku is granted");
        check(!StartPermission.needsRootCheck(PictureSource.VIRTUAL, PrivilegeMode.SHIZUKU, false, false), "explicit Shizuku mode never checks Root");
        check(!StartPermission.needsRootCheck(PictureSource.VIRTUAL, PrivilegeMode.ROOT, false, true), "live verified connection does not invoke su again");
        check(!StartPermission.needsRootCheck(PictureSource.CAST, PrivilegeMode.ROOT, false, false) && !StartPermission.needsRootCheck(PictureSource.DRAW, PrivilegeMode.ROOT, false, false),
                "sources without a daemon never check Root");
        StartPermission.Check gate = new StartPermission.Check();
        long firstCheck = gate.begin(100);
        check(gate.accept(firstCheck, false, true, 110) == StartPermission.Check.Result.WAIT && gate.active(), "checking a new connection is pending rather than denied");
        check(gate.accept(firstCheck, false, true, 400) == StartPermission.Check.Result.WAIT, "pending polls keep the same user request");
        check(gate.accept(firstCheck, true, false, 600) == StartPermission.Check.Result.START, "Root reconnection continues the original start");
        check(gate.accept(firstCheck, true, false, 601) == StartPermission.Check.Result.STALE, "duplicate grant callback cannot start twice");
        long cancelled = gate.begin(1000); gate.cancel();
        check(!gate.owns(cancelled) && gate.accept(cancelled, true, false, 1200) == StartPermission.Check.Result.STALE, "cancelled permission check cannot launch after Root succeeds");
        long newer = gate.begin(2000);
        check(gate.accept(cancelled, false, false, 2100) == StartPermission.Check.Result.STALE && gate.owns(newer), "late failure cannot cancel a newer check");
        check(gate.accept(newer, false, false, 2300) == StartPermission.Check.Result.SETTINGS, "actual denial leads to settings");
        long timed = gate.begin(3000);
        check(gate.accept(timed, false, true, 52999) == StartPermission.Check.Result.WAIT, "manual authorization may remain pending within deadline");
        check(gate.accept(timed, false, true, 53000) == StartPermission.Check.Result.TIMEOUT && !gate.active(), "permission waits are bounded");
        check(gate.accept(timed, true, false, 53001) == StartPermission.Check.Result.STALE, "late authorization cannot revive a timed-out cast");
        for (PrivilegeMode mode : PrivilegeMode.values()) for (boolean shizuku : new boolean[]{false, true}) for (boolean root : new boolean[]{false, true}) {
            StartPermission.Backend expected;
            if (mode == PrivilegeMode.SHIZUKU) expected = shizuku ? StartPermission.Backend.SHIZUKU : StartPermission.Backend.NONE;
            else expected = root ? StartPermission.Backend.ROOT : StartPermission.Backend.NONE;
            check(StartPermission.select(PictureSource.VIRTUAL, mode, shizuku, root) == expected, "preflight respects chosen mode and only existing authorization");
        }
        check(PrivilegeMode.values().length == 2 && PrivilegeMode.read("AUTO", PrivilegeMode.ROOT) == PrivilegeMode.ROOT
                && PrivilegeMode.read("NONE", PrivilegeMode.SHIZUKU) == PrivilegeMode.SHIZUKU && PrivilegeMode.read("SHIZUKU", PrivilegeMode.ROOT) == PrivilegeMode.SHIZUKU,
                "the old automatic and recording modes read as the fallback backend");
        FakeProcess process = new FakeProcess();
        try (AuthorizedShell shell = new AuthorizedShell(process)) {
            String hello = process.command();
            check(hello.contains("id -u") && !hello.contains("app_process"), "authorization check verifies identity without creating a display");
            blocked(() -> shell.launch("child"), "unverified shell cannot launch a daemon");
            process.supply(marker(hello) + ":2000"); await(shell::ready, "correct shell identity activates authorization");
            Process first = shell.launch("child_one"); String firstCommand = process.command();
            blocked(() -> shell.launch("overlapping"), "only one child can own the stream");
            process.supply("first log"); process.supply("ENHANCE_unknown:0");
            check(first.isAlive(), "unrelated output cannot forge completion");
            process.supply(marker(firstCommand) + ":0"); await(() -> !first.isAlive(), "child completion has its own EOF");
            check(first.waitFor() == 0 && new String(first.getInputStream().readAllBytes(), StandardCharsets.UTF_8).equals("first log\nENHANCE_unknown:0\n"),
                    "job preserves log output while withholding its protocol marker");
            check(shell.ready(), "completed display retains an already authorized connection");
            Process second = shell.launch("child_two"); String secondCommand = process.command();
            first.destroy(); check(shell.ready() && second.isAlive(), "late cleanup cannot close a later job");
            process.supply(marker(secondCommand) + ":7"); await(() -> !second.isAlive(), "second child completes independently");
            check(second.waitFor() == 7, "nonzero daemon exit is preserved");
            Process third = shell.launch("child_three"); process.command(); third.destroy();
            await(() -> !third.isAlive(), "cancelling an active job unblocks its waiter");
            check(!shell.ready() && process.dead && third.exitValue() == -1, "cancelled connection requires a new permission check");
            blocked(() -> shell.launch("unauthorized"), "dead authorization never launches a replacement su process");
        }
        FakeProcess restored = new FakeProcess();
        try (AuthorizedShell shell = new AuthorizedShell(restored)) {
            long restart = gate.begin(60000);
            String hello = restored.command();
            check(gate.accept(restart, shell.ready(), shell.pending(), 60001) == StartPermission.Check.Result.WAIT, "fresh service connection is not mistaken for revoked Root");
            blocked(() -> shell.launch("child"), "restored connection must verify actual UID before launching");
            restored.supply(marker(hello) + ":2000"); await(shell::ready, "manager-authorized replacement shell verifies successfully");
            check(gate.accept(restart, shell.ready(), shell.pending(), 60200) == StartPermission.Check.Result.START, "verified replacement shell unblocks the same cast request");
        }
        FakeProcess wrong = new FakeProcess();
        try (AuthorizedShell shell = new AuthorizedShell(wrong)) {
            wrong.supply(marker(wrong.command()) + ":0"); await(() -> wrong.dead, "incorrect identity is rejected");
            check(!shell.ready(), "root UID is not substituted for required shell UID");
        }
        FakeProcess rootKept = new FakeProcess();
        try (AuthorizedShell shell = new AuthorizedShell(rootKept, 0)) {
            rootKept.supply(marker(rootKept.command()) + ":0"); await(shell::ready, "keep-root expects and accepts the root identity");
        }
        FakeProcess rootDropped = new FakeProcess();
        try (AuthorizedShell shell = new AuthorizedShell(rootDropped, 0)) {
            rootDropped.supply(marker(rootDropped.command()) + ":2000"); await(() -> rootDropped.dead, "keep-root rejects a shell identity");
            check(!shell.ready(), "shell UID is not substituted for required root UID");
        }
        FakeProcess disconnected = new FakeProcess();
        try (AuthorizedShell shell = new AuthorizedShell(disconnected)) {
            disconnected.supply(marker(disconnected.command()) + ":2000"); await(shell::ready, "EOF scenario establishes authorization");
            Process child = shell.launch("child"); disconnected.command(); disconnected.output.close();
            await(() -> !child.isAlive(), "connection EOF completes outstanding child");
            check(!shell.ready() && child.exitValue() == -1, "connection EOF revokes authorization");
        }
    }
}
