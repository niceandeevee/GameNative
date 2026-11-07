package com.winlator.alsaserver;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.KeyValueSet;
import com.winlator.math.Mathf;
import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xenvironment.ImageFs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ALSAClient {
    private static short framesPerBuffer = 256;
    private int bufferCapacity;
    private int bufferSize;
    private byte frameBytes;
    protected final Options options;
    private int position;
    private ByteBuffer sharedBuffer;
    private DataType dataType = DataType.U8;
    private AudioTrack audioTrack = null;
    private byte channels = 2;
    private int sampleRate = 0;
    private short previousUnderrunCount = 0;
    private String containerVariant = null;

    /*
     * When Android's audio server dies (it restarts under heavy load), AudioTrack quietly reconnects
     * ("restoreTrack_l: dead IAudioTrack") and keeps accepting writes, but on some devices the
     * restored track plays silence for the rest of the session. The reconnect shows up as a routing
     * change, so a routing change after the track has settled marks it for a rebuild, which the
     * writer thread does before its next write. A headphone or speaker switch also rebuilds the
     * track; that only costs a few milliseconds of audio.
     */
    private static final long ROUTING_SETTLE_MS = 1000;
    private volatile boolean rebuildTrack = false;
    private volatile long trackBuiltAt = 0;
    private final AudioRouting.OnRoutingChangedListener routingListener = router -> {
        if (SystemClock.elapsedRealtime() - trackBuiltAt > ROUTING_SETTLE_MS) rebuildTrack = true;
    };

    public enum DataType {
        U8(1),
        S16LE(2),
        S16BE(2),
        FLOATLE(4),
        FLOATBE(4);

        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte) byteCount;
        }
    }

    public static class Options {
        public short latencyMillis = 40;
        public byte performanceMode = 0;
        public float volume = 1.0f;

        public static Options fromKeyValueSet(KeyValueSet config) {
            Options options;
            if (config == null || config.isEmpty()) {
                return new Options();
            }
            options = new Options();
            switch (config.get("performanceMode")) {
                case "0":
                    options.performanceMode = (byte) 0;
                    break;
                case "1":
                    options.performanceMode = (byte) 1;
                    break;
                case "2":
                    options.performanceMode = (byte) 2;
                    break;
            }
            options.volume = config.getFloat("volume", 1.0f);
            options.latencyMillis = (short) config.getInt("latencyMillis", 40);
            return options;
        }
    }

    public ALSAClient(Options options, String containerVariant) {
        this.options = options;
        this.containerVariant = containerVariant;
    }

    public void release() {
        ByteBuffer byteBuffer = this.sharedBuffer;
        if (byteBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(byteBuffer, byteBuffer.capacity());
            this.sharedBuffer = null;
        }
        releaseTrack();
    }

    private void releaseTrack() {
        AudioTrack audioTrack = this.audioTrack;
        if (audioTrack != null) {
            audioTrack.removeOnRoutingChangedListener(routingListener);
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.release();
            this.audioTrack = null;
        }
    }

    public static int getPCMEncoding(DataType dataType) {
        switch (dataType) {
            case U8:
                return 3;  // AudioFormat.ENCODING_PCM_8BIT
            case S16LE:
            case S16BE:
                return 2;  // AudioFormat.ENCODING_PCM_16BIT
            case FLOATLE:
            case FLOATBE:
                return 4;  // AudioFormat.ENCODING_PCM_FLOAT
            default:
                return 1;  // AudioFormat.ENCODING_DEFAULT
        }
    }

    /**
     * Map channel count → channel mask.
     * 1 channel → MONO, 2 + channels → STEREO (or wider).
     */
    public static int getChannelConfig(int channels) {
        return (channels <= 1) ? 4   // AudioFormat.CHANNEL_OUT_MONO
                : 12; // AudioFormat.CHANNEL_OUT_STEREO | FRONT_LEFT/RIGHT
    }

    public void prepare() {
        this.position = 0;
        this.previousUnderrunCount = (short) 0;
        this.frameBytes = (byte) (this.channels * this.dataType.byteCount);
        release();
        if (isValidBufferSize()) buildTrack();
    }

    private void buildTrack() {
        AudioFormat format = new AudioFormat.Builder().setEncoding(getPCMEncoding(this.dataType)).setSampleRate(this.sampleRate).setChannelMask(getChannelConfig(this.channels)).build();
        AudioTrack build = new AudioTrack.Builder().setPerformanceMode(this.options.performanceMode).setAudioFormat(format).setBufferSizeInBytes(getBufferSizeInBytes()).build();
        this.rebuildTrack = false;
        this.trackBuiltAt = SystemClock.elapsedRealtime();
        this.audioTrack = build;
        this.bufferCapacity = build.getBufferCapacityInFrames();
        float f = this.options.volume;
        if (f != 1.0f) {
            build.setVolume(f);
        }
        build.addOnRoutingChangedListener(routingListener, null);
        build.play();
    }

    /** Replaces a track whose output was rerouted or restored, keeping the stream position. */
    private void rebuildTrackIfNeeded() {
        if (!rebuildTrack || this.audioTrack == null) return;
        Log.w("ALSAClient", "Audio output was rerouted or restored; rebuilding the track");
        boolean playing = this.audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
        releaseTrack();
        this.previousUnderrunCount = 0;
        try {
            buildTrack();
            if (!playing) this.audioTrack.pause();
        } catch (Exception e) {
            Log.e("ALSAClient", "Could not rebuild the audio track", e);
            this.audioTrack = null;
        }
    }

    public void start() {
        AudioTrack audioTrack = this.audioTrack;
        if (audioTrack != null && audioTrack.getPlayState() != 3) {
            this.audioTrack.play();
        }
    }

    public void stop() {
        AudioTrack audioTrack = this.audioTrack;
        if (audioTrack != null) {
            audioTrack.stop();
            this.audioTrack.flush();
        }
    }

    public void pause() {
        AudioTrack audioTrack = this.audioTrack;
        if (audioTrack != null) {
            audioTrack.pause();
        }
    }

    public void drain() {
        AudioTrack audioTrack = this.audioTrack;
        if (audioTrack != null) {
            audioTrack.flush();
        }
    }

    public void writeDataToTrack(ByteBuffer data) {
        DataType dataType = this.dataType;
        if (dataType == DataType.S16LE || dataType == DataType.FLOATLE) {
            data.order(ByteOrder.LITTLE_ENDIAN);
        } else if (dataType == DataType.S16BE || dataType == DataType.FLOATBE) {
            data.order(ByteOrder.BIG_ENDIAN);
        }
        rebuildTrackIfNeeded();
        if (this.audioTrack != null) {
            data.position(0);
            do {
                try {
                    int bytesWritten = this.audioTrack.write(data, data.remaining(), 0);
                    if (bytesWritten <= 0) {
                        break;
                    } else {
                        increaseBufferSizeIfUnderrunOccurs();
                    }
                } catch (Exception e) {
                    break;
                }
            } while (data.position() != data.limit());
            this.position += data.position();
            data.rewind();
        }
    }

    private void increaseBufferSizeIfUnderrunOccurs() {
        int i;
        int underrunCount = this.audioTrack.getUnderrunCount();
        if (underrunCount > this.previousUnderrunCount && (i = this.bufferSize) < this.bufferCapacity) {
            this.previousUnderrunCount = (short) underrunCount;
            int i2 = i + framesPerBuffer;
            this.bufferSize = i2;
            this.audioTrack.setBufferSizeInFrames(i2);
        }
    }

    public int pointer() {
        if (this.audioTrack != null) {
            return this.position / this.frameBytes;
        }
        return 0;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setContainerVariant(String containerVariant) {
        this.containerVariant = containerVariant;
    }

    public String getContainerVariant() {
        return containerVariant;
    }

    public void setChannels(int channels) {
        this.channels = (byte) channels;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return this.sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        this.sharedBuffer = sharedBuffer != null ? sharedBuffer.order(ByteOrder.LITTLE_ENDIAN) : null;
    }

    public int getBufferSizeInBytes() {
        return this.bufferSize * this.frameBytes;
    }

    public static int latencyMillisToBufferSize(int latencyMillis, int channels, DataType dataType, int sampleRate) {
        byte frameBytes = (byte) (dataType.byteCount * channels);
        int bufferSize = (int) Mathf.roundTo((latencyMillis * sampleRate) / 1000.0f, framesPerBuffer, false);
        return bufferSize * frameBytes;
    }

    private boolean isValidBufferSize() {
        int i = this.bufferSize;
        return i % this.frameBytes == 0 && i > 0;
    }

    public static void assignFramesPerBuffer(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService("audio");
            String framesPerBufferStr = am.getProperty("android.media.property.OUTPUT_FRAMES_PER_BUFFER");
            short parseShort = Short.parseShort(framesPerBufferStr);
            framesPerBuffer = parseShort;
            if (parseShort == 0) {
                framesPerBuffer = (short) 256;
            }
        } catch (Exception e) {
            framesPerBuffer = (short) 256;
        }
    }
}
