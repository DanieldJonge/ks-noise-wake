// SPDX-License-Identifier: Apache-2.0
package ks.noisewake;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** Records through AudioRecord with Kiosk Satellite's own microphone permission. */
final class AndroidMicrophone implements Microphone {
    private AudioRecord record;

    static int sourceFor(String name) {
        if ("Voice recognition".equals(name)) return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        if ("Camcorder".equals(name)) return MediaRecorder.AudioSource.CAMCORDER;
        if ("Unprocessed".equals(name)) return MediaRecorder.AudioSource.UNPROCESSED;
        return MediaRecorder.AudioSource.MIC;
    }

    @Override
    public String open(String source) {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return "This device cannot record 16 kHz mono audio";
        try {
            record = new AudioRecord(sourceFor(source), SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLE_RATE / 2 * 2));
        } catch (RuntimeException e) {
            record = null;
            return "Could not open the microphone: " + e.getMessage();
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            close();
            return "Could not open the microphone. Check that Kiosk Satellite has the microphone permission";
        }
        try {
            record.startRecording();
        } catch (RuntimeException e) {
            close();
            return "Could not start recording: " + e.getMessage();
        }
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            close();
            return "The microphone is busy";
        }
        return null;
    }

    @Override
    public int read(short[] buffer) {
        AudioRecord r = record;
        return r == null ? AudioRecord.ERROR_INVALID_OPERATION : r.read(buffer, 0, buffer.length);
    }

    @Override
    public void close() {
        AudioRecord r = record;
        record = null;
        if (r == null) return;
        try {
            r.stop();
        } catch (RuntimeException ignored) {
            // Not recording.
        }
        r.release();
    }
}
