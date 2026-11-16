// SPDX-License-Identifier: Apache-2.0
package ks.noisewake;

/** 16-bit mono PCM capture. The Android implementation is swapped for a fake in tests. */
interface Microphone {
    int SAMPLE_RATE = 16000;

    /** Open and start recording. Returns null on success or a message saying what went wrong. */
    String open(String source);

    /** Blocking read of up to buffer.length samples. Returns the count read, or a negative Android error code. */
    int read(short[] buffer);

    /** Stop recording and release the microphone. Safe to call more than once. */
    void close();

    interface Factory {
        Microphone create();
    }
}
