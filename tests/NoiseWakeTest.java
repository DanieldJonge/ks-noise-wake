// SPDX-License-Identifier: Apache-2.0
package ks.noisewake;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import me.jxl.kiosk.plugins.PluginHost;

public final class NoiseWakeTest {
    static final class Host implements PluginHost {
        final List<String> commands = new CopyOnWriteArrayList<>();
        final List<String> subscriptions = new CopyOnWriteArrayList<>();
        final List<String> statuses = new CopyOnWriteArrayList<>();
        volatile boolean screensaver;
        volatile boolean screen = true;

        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            commands.add(command);
            if ("isScreensaverActive".equals(command)) callback.onResult(true, screensaver, null);
            else if ("isScreenOn".equals(command)) callback.onResult(true, screen, null);
            else callback.onResult(true, null, null);
        }
        public void subscribe(String event) { subscriptions.add(event); }
        public void status(String message, boolean error) { statuses.add((error ? "ERROR " : "") + message); }
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}

        int count(String command) {
            int n = 0;
            for (String c : commands) if (c.equals(command)) n++;
            return n;
        }
        String lastStatus() { return statuses.isEmpty() ? "" : statuses.get(statuses.size() - 1); }
    }

    /** Fake microphone: full-scale square wave while loud, quiet noise otherwise. */
    static final class Mic implements Microphone {
        static final AtomicInteger opened = new AtomicInteger();
        static final AtomicInteger closed = new AtomicInteger();
        static volatile boolean loud;
        static volatile String failure;
        static volatile String lastSource;

        public String open(String source) {
            lastSource = source;
            if (failure != null) return failure;
            opened.incrementAndGet();
            return null;
        }
        public int read(short[] buffer) {
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int i = 0; i < buffer.length; i++) buffer[i] = (short) (loud ? (i % 2 == 0 ? 32767 : -32767) : (i % 2 == 0 ? 30 : -30));
            return buffer.length;
        }
        public void close() { closed.incrementAndGet(); }
    }

    static Map<String, Object> settings(Object... pairs) {
        Map<String, Object> s = new HashMap<>();
        s.put("threshold", -30.0);
        s.put("minDuration", 0.0);
        s.put("cooldown", 0.0);
        s.put("wakeScreenOff", false);
        s.put("keepAwake", false);
        s.put("measure", false);
        s.put("audioSource", "Microphone");
        for (int i = 0; i < pairs.length; i += 2) s.put((String) pairs[i], pairs[i + 1]);
        return s;
    }

    static Map<String, Object> payload(String key, boolean value) {
        return Collections.<String, Object>singletonMap(key, value);
    }

    interface Check { boolean ok(); }

    static void waitFor(String what, Check check) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (!check.ok()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("Timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    static void testLevels() {
        short[] full = new short[800];
        for (int i = 0; i < full.length; i++) full[i] = (short) (i % 2 == 0 ? 32767 : -32768);
        assert Math.abs(SoundTrigger.dbfs(full, full.length)) < 0.01 : SoundTrigger.dbfs(full, full.length);
        short[] tenth = new short[800];
        for (int i = 0; i < tenth.length; i++) tenth[i] = (short) (i % 2 == 0 ? 3277 : -3277);
        assert Math.abs(SoundTrigger.dbfs(tenth, tenth.length) + 20) < 0.01 : SoundTrigger.dbfs(tenth, tenth.length);
        assert SoundTrigger.dbfs(new short[800], 800) == SoundTrigger.SILENCE_DB;
        assert SoundTrigger.dbfs(full, 0) == SoundTrigger.SILENCE_DB;
        assert "silence".equals(NoiseWakePlugin.format(SoundTrigger.SILENCE_DB));
        assert "-35 dBFS".equals(NoiseWakePlugin.format(-35));
    }

    static void testTrigger() {
        SoundTrigger t = new SoundTrigger(-30, 100, 1000);
        assert !t.feed(-50, 50, 50);
        assert !t.feed(-20, 100, 50) : "50 ms is shorter than the minimum duration";
        assert t.feed(-20, 150, 50) : "100 ms above the threshold fires";
        assert !t.feed(-20, 200, 50);
        assert !t.feed(-20, 1100, 50) : "still cooling down";
        assert t.feed(-20, 1150, 50) : "cooldown over and loud for long enough";
        assert !t.feed(-40, 2200, 50);
        assert !t.feed(-30, 2250, 50) : "the threshold is inclusive, one chunk is too short";
        t.reset();
        assert !t.feed(-30, 2300, 50) : "reset starts the duration again";
        assert t.feed(-30, 2350, 50);

        SoundTrigger instant = new SoundTrigger(-30, 0, 0);
        assert instant.feed(-10, 50, 50) : "no minimum duration reacts to one chunk";
        assert instant.feed(-10, 100, 50) : "no cooldown fires again";

        t.configure(-10, 0, 1000);
        assert !t.feed(-5, 2400, 50) : "a running cooldown survives new settings";
        assert t.feed(-5, 3350, 50);
    }

    static void testKeepAwake() {
        KeepAwake k = new KeepAwake(-30, 100);
        assert !k.feed(-50, 50, 50);
        assert !k.feed(-20, 100, 50) : "50 ms is shorter than the minimum duration";
        assert k.feed(-20, 150, 50) : "loud for 100 ms resets the timer at once";
        assert k.loud();
        for (long t = 200; t < 1150; t += 50) assert !k.feed(-20, t, 50) : "at most once a second, " + t;
        assert k.feed(-20, 1150, 50) : "a second later it resets again";
        // A pause shorter than the release time, such as between words, keeps it loud.
        assert !k.feed(-50, 1200, 50);
        assert !k.feed(-50, 1500, 50);
        assert !k.feed(-20, 1550, 50);
        assert k.loud();
        // Quiet for the release time: one last reset, then nothing.
        assert !k.feed(-50, 1600, 50);
        assert !k.feed(-50, 2000, 50);
        assert k.feed(-50, 2050, 50) : "500 ms of quiet resets one last time";
        assert !k.loud();
        assert !k.feed(-50, 2100, 50);
        assert !k.feed(-50, 5000, 50);
        // A new sound needs the minimum duration again.
        assert !k.feed(-20, 5050, 50);
        assert k.feed(-20, 5100, 50);
        k.reset();
        assert !k.loud();
        assert !k.feed(-50, 5700, 50) : "reset drops the sound without a last reset";
    }

    static void testPlugin() throws Exception {
        Host host = new Host();
        NoiseWakePlugin plugin = new NoiseWakePlugin(new Microphone.Factory() {
            public Microphone create() { return new Mic(); }
        });
        plugin.start(host, settings());
        assert host.subscriptions.equals(Arrays.asList("screensaver.state", "screen.state")) : host.subscriptions;
        assert Mic.opened.get() == 0 : "no microphone without a screensaver";
        assert host.lastStatus().equals("Waiting for the screensaver.") : host.lastStatus();

        // The screensaver starts, sound wakes the screen.
        plugin.onEvent("ks.screensaver.state", payload("active", true));
        waitFor("microphone", () -> Mic.opened.get() == 1);
        assert host.count("stopScreensaver") == 0 : "quiet room does not wake";
        Mic.loud = true;
        waitFor("stopScreensaver", () -> host.count("stopScreensaver") >= 1);
        Mic.loud = false;

        // The screensaver closes, the microphone closes with it.
        plugin.onEvent("ks.screensaver.state", payload("active", false));
        assert Mic.closed.get() == 1 : Mic.closed.get();
        assert host.lastStatus().startsWith("Waiting for the screensaver. Last woke the screen at 0 dBFS") : host.lastStatus();
        int woke = host.count("stopScreensaver");

        // Measuring listens without a screensaver and never wakes anything.
        plugin.configure(settings("measure", true));
        waitFor("microphone", () -> Mic.opened.get() == 2);
        Mic.loud = true;
        waitFor("measure status", () -> host.lastStatus().startsWith("Measuring: now 0 dBFS"));
        assert host.lastStatus().contains("threshold -30 dBFS") : host.lastStatus();
        assert host.count("stopScreensaver") == woke && host.count("screenOn") == 0;
        Mic.loud = false;

        // A new audio source opens the microphone again with it.
        plugin.configure(settings("measure", true, "audioSource", "Unprocessed"));
        waitFor("reopened microphone", () -> Mic.opened.get() == 3);
        assert "Unprocessed".equals(Mic.lastSource);
        assert Mic.closed.get() == 2;

        plugin.configure(settings());
        assert Mic.closed.get() == 3;

        // Staying awake listens while the screen is on and holds off the screensaver while it is loud.
        plugin.configure(settings("keepAwake", true));
        waitFor("microphone", () -> Mic.opened.get() == 4);
        assert host.count("postponeScreensaver") == 0 : "a quiet room does not hold off the screensaver";
        Mic.loud = true;
        waitFor("postponeScreensaver", () -> host.count("postponeScreensaver") >= 1);
        int held = host.count("postponeScreensaver");
        Mic.loud = false;
        // Once quiet, one last reset starts the countdown from the moment the sound stopped.
        waitFor("final postponeScreensaver", () -> host.count("postponeScreensaver") > held);
        Thread.sleep(300);
        int afterQuiet = host.count("postponeScreensaver");
        Thread.sleep(1200);
        assert host.count("postponeScreensaver") == afterQuiet : "nothing more while quiet";
        assert host.count("stopScreensaver") == woke && host.count("screenOn") == 0;
        // The screensaver showing hands over to waking. Its end hands back.
        plugin.onEvent("ks.screensaver.state", payload("active", true));
        assert Mic.opened.get() == 4 : "the same microphone keeps listening";
        plugin.onEvent("ks.screensaver.state", payload("active", false));
        assert Mic.opened.get() == 4 && Mic.closed.get() == 3;
        // A display that is off has nothing to keep awake.
        plugin.onEvent("ks.screen.state", payload("on", false));
        assert Mic.closed.get() == 4;
        assert host.lastStatus().startsWith("Waiting for the display to turn on.") : host.lastStatus();

        // A display that is off wakes only with the setting on.
        plugin.configure(settings());
        assert Mic.opened.get() == 4 : "the setting is off";
        plugin.configure(settings("wakeScreenOff", true));
        waitFor("microphone", () -> Mic.opened.get() == 5);
        Mic.loud = true;
        waitFor("screenOn", () -> host.count("screenOn") >= 1);
        Mic.loud = false;

        plugin.stop();
        assert Mic.closed.get() == 5 : "stop releases the microphone";

        // A microphone that will not open is reported.
        Host failing = new Host();
        failing.screensaver = true;
        Mic.failure = "Could not open the microphone. Check that Kiosk Satellite has the microphone permission";
        NoiseWakePlugin denied = new NoiseWakePlugin(new Microphone.Factory() {
            public Microphone create() { return new Mic(); }
        });
        denied.start(failing, settings());
        waitFor("error status", () -> failing.lastStatus().startsWith("ERROR Could not open the microphone"));
        denied.stop();
        Mic.failure = null;
    }

    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new AssertionError("Run with -ea");
        testLevels();
        testTrigger();
        testKeepAwake();
        testPlugin();
        System.out.println("NoiseWakeTest passed");
    }
}
