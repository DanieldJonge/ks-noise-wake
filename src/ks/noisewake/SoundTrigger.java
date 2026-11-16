// SPDX-License-Identifier: Apache-2.0
package ks.noisewake;

/** Decides when sound has been loud for long enough. Pure Java so it is tested without a device. */
final class SoundTrigger {
    /** Level reported for digital silence, where the logarithm has no value. */
    static final double SILENCE_DB = -120;

    private double thresholdDb;
    private long minDurationMs;
    private long cooldownMs;
    private long aboveSince = -1;
    private long lastFired = Long.MIN_VALUE / 2;

    SoundTrigger(double thresholdDb, long minDurationMs, long cooldownMs) {
        configure(thresholdDb, minDurationMs, cooldownMs);
    }

    /** Apply new settings. A running cooldown carries over. */
    synchronized void configure(double thresholdDb, long minDurationMs, long cooldownMs) {
        this.thresholdDb = thresholdDb;
        this.minDurationMs = Math.max(0, minDurationMs);
        this.cooldownMs = Math.max(0, cooldownMs);
    }

    /** RMS level of 16-bit PCM samples in dBFS, where 0 is full scale. */
    static double dbfs(short[] samples, int count) {
        if (count <= 0) return SILENCE_DB;
        double sum = 0;
        for (int i = 0; i < count; i++) {
            double s = samples[i];
            sum += s * s;
        }
        double rms = Math.sqrt(sum / count);
        if (rms <= 0) return SILENCE_DB;
        return Math.max(SILENCE_DB, 20 * Math.log10(rms / 32768.0));
    }

    /**
     * Feed the level of one chunk that ended at nowMs and lasted chunkMs. Returns true once when the level has stayed
     * at or above the threshold for the minimum duration and the cooldown has passed.
     */
    synchronized boolean feed(double db, long nowMs, long chunkMs) {
        if (db < thresholdDb) {
            aboveSince = -1;
            return false;
        }
        if (aboveSince < 0) aboveSince = nowMs - chunkMs;
        if (nowMs - aboveSince < minDurationMs || nowMs - lastFired < cooldownMs) return false;
        lastFired = nowMs;
        aboveSince = -1;
        return true;
    }

    /** Forget a sound in progress, for example while waking is not wanted. The cooldown is kept. */
    synchronized void reset() {
        aboveSince = -1;
    }
}
