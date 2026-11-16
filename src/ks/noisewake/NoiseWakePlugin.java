// SPDX-License-Identifier: Apache-2.0
package ks.noisewake;

import java.util.Collections;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Closes the screensaver when the microphone hears sound above a threshold. The microphone is open only while the
 * screensaver shows, while the display is off with "Also wake a display that is off", or while "Measure the room" is
 * on. Audio never leaves the device: each 50 ms chunk becomes one loudness number and is then discarded.
 */
public final class NoiseWakePlugin implements KioskPlugin {
    static final int CHUNK_SAMPLES = Microphone.SAMPLE_RATE / 20;
    private static final long STATUS_INTERVAL_MS = 1000;
    private static final long PEAK_WINDOW_MS = 10000;
    private static final long SILENCE_WARNING_MS = 3000;

    /** One complete, immutable set of settings, swapped atomically by configure. */
    static final class Config {
        final double threshold;
        final long minDurationMs;
        final long cooldownMs;
        final boolean wakeScreenOff;
        final boolean keepAwake;
        final boolean measure;
        final String source;

        Config(Map<String, Object> s) {
            threshold = number(s, "threshold", -35);
            minDurationMs = (long) number(s, "minDuration", 100);
            cooldownMs = (long) (number(s, "cooldown", 10) * 1000);
            wakeScreenOff = Boolean.TRUE.equals(s.get("wakeScreenOff"));
            keepAwake = !Boolean.FALSE.equals(s.get("keepAwake"));
            measure = Boolean.TRUE.equals(s.get("measure"));
            Object source = s.get("audioSource");
            this.source = source instanceof String ? (String) source : "Microphone";
        }

        private static double number(Map<String, Object> s, String key, double fallback) {
            Object v = s.get(key);
            return v instanceof Number ? ((Number) v).doubleValue() : fallback;
        }
    }

    private final Microphone.Factory microphones;
    private final SoundTrigger trigger = new SoundTrigger(-35, 100, 10000);
    private final KeepAwake keepAwake = new KeepAwake(-35, 100);
    private volatile PluginHost host;
    private volatile Config config = new Config(Collections.<String, Object>emptyMap());
    private volatile boolean screensaverActive;
    private volatile boolean screenOn = true;
    private volatile String lastWake = "";
    private boolean screensaverEventSeen;
    private boolean screenEventSeen;
    private Listener listener;

    public NoiseWakePlugin() {
        this(new Microphone.Factory() {
            @Override
            public Microphone create() {
                return new AndroidMicrophone();
            }
        });
    }

    NoiseWakePlugin(Microphone.Factory microphones) {
        this.microphones = microphones;
    }

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        apply(new Config(settings));
        try {
            host.subscribe("screensaver.state");
            host.subscribe("screen.state");
        } catch (RuntimeException e) {
            status("Could not follow the screensaver: " + e.getMessage(), true);
            return;
        }
        host.executeCommand("isScreensaverActive", Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
            @Override
            public void onResult(boolean ok, Object data, String error) {
                // A screensaver.state event that already arrived is newer than this reading.
                if (ok && data instanceof Boolean && !screensaverEventSeen) {
                    screensaverActive = (Boolean) data;
                    update();
                }
            }
        });
        host.executeCommand("isScreenOn", Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
            @Override
            public void onResult(boolean ok, Object data, String error) {
                if (ok && data instanceof Boolean && !screenEventSeen) {
                    screenOn = (Boolean) data;
                    update();
                }
            }
        });
        update();
    }

    @Override
    public void configure(Map<String, Object> settings) {
        apply(new Config(settings));
        update();
    }

    private void apply(Config next) {
        Config previous = config;
        config = next;
        trigger.configure(next.threshold, next.minDurationMs, next.cooldownMs);
        keepAwake.configure(next.threshold, next.minDurationMs);
        // A new audio source needs the microphone opened again. Other settings apply to the running listener.
        if (listener != null && !next.source.equals(previous.source)) stopListening();
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {}

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if ("ks.screensaver.state".equals(event)) {
            screensaverEventSeen = true;
            screensaverActive = Boolean.TRUE.equals(payload.get("active"));
            update();
        } else if ("ks.screen.state".equals(event)) {
            screenEventSeen = true;
            screenOn = !Boolean.FALSE.equals(payload.get("on"));
            update();
        }
    }

    @Override
    public void stop() {
        stopListening();
        host = null;
    }

    /** True while sound should wake the screen. */
    private boolean wakeWanted(Config c) {
        return screensaverActive || (c.wakeScreenOff && !screenOn);
    }

    /** True while sound should hold off the screensaver: the screen is on and the screensaver is not showing. */
    private boolean keepAwakeWanted(Config c) {
        return c.keepAwake && screenOn && !screensaverActive;
    }

    /** Open or close the microphone to match the current state. Runs on the plugin worker. */
    private void update() {
        Config c = config;
        boolean listen = host != null && (c.measure || wakeWanted(c) || keepAwakeWanted(c));
        if (listen && listener == null) {
            listener = new Listener(c.source);
            listener.start();
        } else if (!listen) {
            stopListening();
            if (host != null) status(idleMessage(c), false);
        }
    }

    private void stopListening() {
        Listener l = listener;
        listener = null;
        if (l != null) l.shutdown();
    }

    private String idleMessage(Config c) {
        String waiting = c.keepAwake && !screenOn ? "Waiting for the display to turn on."
                : c.wakeScreenOff ? "Waiting for the screensaver or for the display to turn off." : "Waiting for the screensaver.";
        return lastWake.isEmpty() ? waiting : waiting + " " + lastWake;
    }

    private void wake(double db) {
        PluginHost h = host;
        if (h == null) return;
        final String command = screensaverActive ? "stopScreensaver" : "screenOn";
        final String level = format(db);
        try {
            h.executeCommand(command, Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
                @Override
                public void onResult(boolean ok, Object data, String error) {
                    if (ok) lastWake = "Last woke the screen at " + level + ".";
                    else status("Could not wake the screen: " + error, true);
                }
            });
        } catch (RuntimeException e) {
            status("Could not wake the screen: " + e.getMessage(), true);
        }
    }

    /** Reset the idle timer so the screensaver countdown starts again. */
    private void postpone() {
        PluginHost h = host;
        if (h == null) return;
        try {
            h.executeCommand("postponeScreensaver", Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
                @Override
                public void onResult(boolean ok, Object data, String error) {
                    if (!ok) status("Could not hold off the screensaver: " + error, true);
                }
            });
        } catch (RuntimeException e) {
            status("Could not hold off the screensaver: " + e.getMessage(), true);
        }
    }

    private void status(String message, boolean error) {
        PluginHost h = host;
        if (h == null) return;
        try {
            h.status(message, error);
        } catch (RuntimeException ignored) {
            // The session ended while this thread was still finishing.
        }
    }

    static String format(double db) {
        return db <= SoundTrigger.SILENCE_DB ? "silence" : Math.round(db) + " dBFS";
    }

    private static long nowMs() {
        return System.nanoTime() / 1000000L;
    }

    /** Reads the microphone on its own thread so plugin callbacks never wait for audio. */
    private final class Listener extends Thread {
        private final String source;
        private volatile boolean stopping;

        Listener(String source) {
            super("Noise Wake microphone");
            this.source = source;
            setDaemon(true);
        }

        void shutdown() {
            stopping = true;
            interrupt();
            try {
                // A read returns after one 50 ms chunk, well inside the three second callback limit.
                join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            Microphone mic = microphones.create();
            String error = mic.open(source);
            if (error != null) {
                mic.close();
                if (!stopping) status(error, true);
                return;
            }
            try {
                listen(mic);
            } finally {
                mic.close();
            }
        }

        private void listen(Microphone mic) {
            short[] buffer = new short[CHUNK_SAMPLES];
            long silentMs = 0;
            long lastStatus = 0;
            double windowMax = SoundTrigger.SILENCE_DB;
            double peak = SoundTrigger.SILENCE_DB;
            long peakAt = 0;
            String shown = null;
            trigger.reset();
            while (!stopping) {
                int count = mic.read(buffer);
                if (stopping) return;
                if (count < 0) {
                    status("Recording stopped with Android error " + count + ". Turn the plugin off and on to try again.", true);
                    return;
                }
                if (count == 0) continue;
                long now = nowMs();
                long chunkMs = count * 1000L / Microphone.SAMPLE_RATE;
                double db = SoundTrigger.dbfs(buffer, count);
                silentMs = db <= SoundTrigger.SILENCE_DB ? silentMs + chunkMs : 0;

                Config c = config;
                if (wakeWanted(c)) {
                    keepAwake.reset();
                    if (trigger.feed(db, now, chunkMs)) wake(db);
                } else {
                    trigger.reset();
                    if (!keepAwakeWanted(c)) keepAwake.reset();
                    else if (keepAwake.feed(db, now, chunkMs)) postpone();
                }

                windowMax = Math.max(windowMax, db);
                if (now - lastStatus < STATUS_INTERVAL_MS) continue;
                if (windowMax >= peak || now - peakAt > PEAK_WINDOW_MS) {
                    peak = windowMax;
                    peakAt = now;
                }
                String text;
                if (silentMs >= SILENCE_WARNING_MS) {
                    text = "The microphone only returns silence. Another app may be holding it. Try another Audio source.";
                } else {
                    text = (c.measure ? "Measuring" : "Listening") + ": now " + format(windowMax) + ", loudest in the last 10 s "
                            + format(peak) + ", threshold " + format(c.threshold) + ".";
                    if (keepAwake.loud()) text += " Keeping the screen awake.";
                    if (!lastWake.isEmpty()) text += " " + lastWake;
                }
                if (!text.equals(shown)) {
                    status(text, silentMs >= SILENCE_WARNING_MS);
                    shown = text;
                }
                lastStatus = now;
                windowMax = SoundTrigger.SILENCE_DB;
            }
        }
    }
}
