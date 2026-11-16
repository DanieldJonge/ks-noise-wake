// SPDX-License-Identifier: Apache-2.0
package ks.noisewake;

/**
 * Decides when to reset the screensaver's idle timer while the room is loud. Sound counts as loud once it stays at or
 * above the threshold for the minimum duration. While loud, the timer is reset once a second. Short pauses, such as
 * between words, keep it loud. After RELEASE_MS of quiet the timer is reset one last time, so the normal countdown
 * starts from the moment the sound stopped.
 */
final class KeepAwake {
    static final long INTERVAL_MS = 1000;
    static final long RELEASE_MS = 500;

    private double thresholdDb;
    private long minDurationMs;
    private long aboveSince = -1;
    private long lastLoud;
    private long lastSent = Long.MIN_VALUE / 2;
    private boolean loud;

    KeepAwake(double thresholdDb, long minDurationMs) {
        configure(thresholdDb, minDurationMs);
    }

    synchronized void configure(double thresholdDb, long minDurationMs) {
        this.thresholdDb = thresholdDb;
        this.minDurationMs = Math.max(0, minDurationMs);
    }

    /** Feed the level of one chunk that ended at nowMs and lasted chunkMs. Returns true when the timer should reset. */
    synchronized boolean feed(double db, long nowMs, long chunkMs) {
        if (db >= thresholdDb) {
            if (loud) {
                lastLoud = nowMs;
            } else {
                if (aboveSince < 0) aboveSince = nowMs - chunkMs;
                if (nowMs - aboveSince >= minDurationMs) {
                    loud = true;
                    lastLoud = nowMs;
                }
            }
        } else {
            aboveSince = -1;
        }
        if (!loud) return false;
        if (nowMs - lastLoud >= RELEASE_MS) {
            loud = false;
            aboveSince = -1;
            lastSent = nowMs;
            return true;
        }
        if (nowMs - lastSent >= INTERVAL_MS) {
            lastSent = nowMs;
            return true;
        }
        return false;
    }

    synchronized boolean loud() {
        return loud;
    }

    /** Forget the current sound without a final reset, for example when the screensaver starts. */
    synchronized void reset() {
        loud = false;
        aboveSince = -1;
    }
}
