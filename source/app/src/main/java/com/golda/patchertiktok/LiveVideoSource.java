package com.golda.patchertiktok;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live from gallery video: replaces the live camera feed with frames decoded
 * from user-picked videos.
 *
 * <p>Verified from TikTok 47.0.3 (androguard, dex_split/classes4.dex):
 * <ul>
 *   <li>{@code com.ss.ttlivestreamer.livestreamv2.capture.LiveStreamVideoCapture}
 *       method {@code LLJJI(Lcom/ss/ttlivestreamer/core/buffer/VideoFrame$Buffer;IIIJ)I}
 *       (obfuscated name; found by signature: (Buffer, width, height, rotation, timestampNs) -&gt; int)
 *       is the entry point of frames into the live pipeline.</li>
 *   <li>{@code com.ss.ttlivestreamer.core.buffer.NV21Buffer} has a public constructor
 *       {@code ([B I I Ljava/lang/Runnable;)V} (byte[], width, height, releaseCallback).</li>
 * </ul>
 *
 * <p>Strategy: hook {@code LLJJI} with a <em>before</em> hook. When the feature is on and a
 * decoded video frame is ready, the camera buffer in {@code args[0]} is replaced with an
 * {@code NV21Buffer} wrapping our frame (width/height in {@code args[1]}/{@code args[2]}
 * are updated to match). The camera keeps running underneath; its frames are simply
 * discarded. A background decoder thread (MediaExtractor + MediaCodec + ImageReader)
 * decodes the playlist in real time and publishes the latest NV21 frame.
 *
 * <p>Honest limits:
 * <ul>
 *   <li>Audio is NOT replaced — the microphone keeps feeding the stream. The video file's
 *       audio track is ignored (no mapped audio injection point was found in 47.0.3).</li>
 *   <li>TikTok's server can flag the stream as pre-recorded
 *       ({@code RoomPrivateInfo.preRecorded}) and may restrict or ban the account.
 *       This is shown as a warning in the settings UI.</li>
 *   <li>Needs runtime verification on a real device.</li>
 * </ul>
 */
final class LiveVideoSource {
    private static final String CAPTURE =
            "com.ss.ttlivestreamer.livestreamv2.capture.LiveStreamVideoCapture";
    private static final String NV21_BUFFER =
            "com.ss.ttlivestreamer.core.buffer.NV21Buffer";
    private static final String FRAME_BUFFER =
            "com.ss.ttlivestreamer.core.buffer.VideoFrame$Buffer";

    /** Play modes, stored in {@link Prefs#LIVE_VIDEO_MODE}. */
    static final int MODE_ONCE = 0;
    static final int MODE_LOOP_LIST = 1;
    static final int MODE_LOOP_ONE = 2;

    private static volatile Constructor<?> nv21Ctor;
    private static volatile boolean hooked;

    private static final AtomicBoolean playing = new AtomicBoolean(false);
    private static final AtomicBoolean finished = new AtomicBoolean(false);
    private static final AtomicReference<Frame> current = new AtomicReference<>();

    /**
     * Resolution negotiated by TikTok's camera pipeline, captured from the
     * hook args on every frame. The decoder scales video frames to this size
     * so the pipeline's width/height never change mid-stream.
     */
    private static volatile int targetW = 0;
    private static volatile int targetH = 0;

    /** Latest decoded video frame, shared with the hook. */
    static final class Frame {
        final byte[] nv21;
        final int width;
        final int height;
        Frame(byte[] nv21, int width, int height) {
            this.nv21 = nv21;
            this.width = width;
            this.height = height;
        }
    }

    private LiveVideoSource() { }

    /** Signature frame kamera: (VideoFrame$Buffer, int, int, int, long) -> int. */
    private static boolean matchesFrameSignature(Method m, Class<?> frameBuffer) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length != 5) return false;
        if (!frameBuffer.isAssignableFrom(p[0])) return false;
        if (p[1] != int.class || p[2] != int.class || p[3] != int.class) return false;
        if (p[4] != long.class) return false;
        return m.getReturnType() == int.class;
    }

    static void install(ClassLoader loader) {
        Class<?> capture = Hooks.findClass(CAPTURE, loader);
        if (capture == null) {
            RuntimeLog.log("live video unavailable: LiveStreamVideoCapture not found");
            return;
        }
        Class<?> nv21 = Hooks.findClass(NV21_BUFFER, loader);
        if (nv21 == null) {
            RuntimeLog.log("live video unavailable: NV21Buffer not found");
            return;
        }
        Class<?> frameBuffer = Hooks.findClass(FRAME_BUFFER, loader);
        if (frameBuffer == null) {
            RuntimeLog.log("live video unavailable: VideoFrame$Buffer not found");
            return;
        }
        try {
            nv21Ctor = nv21.getConstructor(byte[].class, int.class, int.class, Runnable.class);
        } catch (NoSuchMethodException e) {
            RuntimeLog.log("live video unavailable: NV21Buffer([BII R) not found");
            return;
        }
        Method target = null;
        // Cari berdasarkan signature, bukan nama — nama method hasil obfuscation
        // (mis. "LLJJI") bisa berubah tiap update TikTok.
        for (Method m : capture.getDeclaredMethods()) {
            if (!matchesFrameSignature(m, frameBuffer)) continue;
            // Prefer nama lama bila masih ada (kompatibilitas mundur).
            if ("LLJJI".equals(m.getName())) {
                target = m;
                break;
            }
            if (target == null) target = m;
        }
        if (target == null) {
            RuntimeLog.log("live video unavailable: frame method (Buffer,IIIJ)I not found");
            return;
        }
        RuntimeLog.log("live video: hooking " + target.getName());
        Hooks.hook(target, new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                inject(param);
            }
        });
        hooked = true;
        RuntimeLog.log("live video installed");
    }

    /** Called on the camera frame thread; swaps in our video frame when active. */
    private static void inject(Hooks.Call param) {
        if (!Prefs.on(Prefs.LIVE_VIDEO)) return;
        if (finished.get()) return;
        int tw = (Integer) param.args[1];
        int th = (Integer) param.args[2];
        if (tw > 0 && th > 0) {
            targetW = tw;
            targetH = th;
        }
        // Start the decoder lazily on the first camera frame.
        if (!playing.get()) startPlayer();
        Frame f = current.get();
        if (f == null || f.nv21 == null) return;
        // Only inject when the frame already matches the pipeline's expected
        // resolution. The encoder is configured once at stream start; changing
        // width/height mid-stream breaks it and the live dies ~1s after start.
        // The decoder scales frames to (targetW, targetH) before publishing,
        // so args[1]/args[2] must pass through UNCHANGED here.
        if (f.width != tw || f.height != th) return;
        try {
            // No-op release: the byte[] is shared until the decoder replaces it;
            // the old array stays alive via the buffer reference until released.
            Object buf = nv21Ctor.newInstance(f.nv21, f.width, f.height, (Runnable) () -> { });
            param.args[0] = buf;
            // args[1]..args[4] (width, height, rotation, timestampNs) pass through unchanged.
        } catch (Throwable t) {
            RuntimeLog.log("live video inject failed: " + t);
        }
    }

    /** Playlist as absolute file paths, one per line. */
    static List<String> playlist() {
        List<String> out = new ArrayList<>();
        String raw = Prefs.text(Prefs.LIVE_VIDEO_LIST);
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    static void setPlaylist(List<String> paths) {
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(p.trim());
        }
        Prefs.setText(Prefs.LIVE_VIDEO_LIST, sb.toString());
    }

    static boolean isHooked() { return hooked; }

    /** Stops the decoder; the camera feed passes through again. */
    static void stop() {
        playing.set(false);
        finished.set(false);
        current.set(null);
    }

    private static void startPlayer() {
        if (!playing.compareAndSet(false, true)) return;
        finished.set(false);
        Thread t = new Thread(() -> {
            try {
                playLoop();
            } catch (Throwable e) {
                RuntimeLog.log("live video player failed: " + e);
            } finally {
                playing.set(false);
            }
        }, "LiveVideoPlayer");
        t.setDaemon(true);
        t.start();
    }

    private static void playLoop() {
        List<String> list = playlist();
        if (list.isEmpty()) {
            RuntimeLog.log("live video: playlist empty, not starting");
            playing.set(false);
            return;
        }
        int mode = (int) Prefs.number(Prefs.LIVE_VIDEO_MODE);
        int index = 0;
        while (playing.get() && Prefs.on(Prefs.LIVE_VIDEO)) {
            String path = list.get(index);
            playOne(path);
            if (!playing.get() || !Prefs.on(Prefs.LIVE_VIDEO)) break;
            if (mode == MODE_LOOP_ONE) {
                continue; // replay the same video
            }
            index++;
            if (index >= list.size()) {
                if (mode == MODE_LOOP_LIST) {
                    index = 0;
                } else {
                    break; // MODE_ONCE: finished
                }
            }
        }
        // MODE_ONCE (or toggle off): stop injecting; camera passes through.
        finished.set(true);
        playing.set(false);
        current.set(null);
        RuntimeLog.log("live video: playback ended (mode=" + mode + ")");
    }

    /** Decodes one video file in real time, publishing NV21 frames. */
    private static void playOne(String path) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
        } catch (Throwable t) {
            RuntimeLog.log("live video: cannot open " + path + ": " + t);
            return;
        }
        int track = -1;
        MediaFormat format = null;
        String mime = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String m = f.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("video/")) {
                track = i;
                format = f;
                mime = m;
                break;
            }
        }
        if (track < 0 || format == null || mime == null) {
            RuntimeLog.log("live video: no video track in " + path);
            extractor.release();
            return;
        }
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        extractor.selectTrack(track);

        MediaCodec codec;
        try {
            codec = MediaCodec.createDecoderByType(mime);
        } catch (Throwable t) {
            RuntimeLog.log("live video: no decoder for " + mime);
            extractor.release();
            return;
        }
        ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
        try {
            codec.configure(format, reader.getSurface(), null, 0);
        } catch (Throwable t) {
            RuntimeLog.log("live video: codec configure failed: " + t);
            codec.release();
            extractor.release();
            reader.close();
            return;
        }
        codec.start();
        RuntimeLog.log("live video: playing " + path + " (" + width + "x" + height + ")");

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputEos = false;
        boolean outputEos = false;
        long startNs = System.nanoTime();
        try {
            while (playing.get() && Prefs.on(Prefs.LIVE_VIDEO) && !outputEos) {
                if (!inputEos) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = 0;
                        if (buf != null) size = extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10000);
                if (out >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(out, true); // render to ImageReader surface
                    if (eos) {
                        outputEos = true;
                    } else {
                        Image image = reader.acquireLatestImage();
                        if (image != null) {
                            try {
                                // Real-time pacing against presentation timestamps.
                                long targetNs = startNs + info.presentationTimeUs * 1000L;
                                long waitMs = (targetNs - System.nanoTime()) / 1000000L;
                                if (waitMs > 0 && waitMs < 5000) {
                                    try {
                                        Thread.sleep(waitMs);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                                byte[] nv21 = yuv420ToNv21(image);
                                if (nv21 != null) {
                                    int vw = image.getWidth();
                                    int vh = image.getHeight();
                                    int tw = targetW;
                                    int th = targetH;
                                    // Scale to the camera pipeline's resolution so the
                                    // hook never has to change width/height mid-stream.
                                    if (tw > 0 && th > 0 && (vw != tw || vh != th)) {
                                        byte[] scaled = scaleNv21CenterCrop(nv21, vw, vh, tw, th);
                                        if (scaled != null) {
                                            nv21 = scaled;
                                            vw = tw;
                                            vh = th;
                                        }
                                    }
                                    current.set(new Frame(nv21, vw, vh));
                                }
                            } finally {
                                image.close();
                            }
                        }
                    }
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (Throwable ignored) { }
            codec.release();
            extractor.release();
            reader.close();
        }
    }

    /**
     * Scales an NV21 frame to the target size with a center crop (fill, no
     * letterbox). Nearest-neighbor: fast enough on the decoder thread.
     * Returns null if the dimensions are unusable.
     */
    private static byte[] scaleNv21CenterCrop(byte[] src, int sw, int sh, int tw, int th) {
        if (src == null) return null;
        if ((sw & 1) != 0 || (sh & 1) != 0 || (tw & 1) != 0 || (th & 1) != 0) return null;
        if (sw <= 0 || sh <= 0 || tw <= 0 || th <= 0) return null;
        if (src.length < sw * sh * 3 / 2) return null;
        // Center-crop source rect matching the target aspect ratio.
        float targetAspect = (float) tw / th;
        float srcAspect = (float) sw / sh;
        int cw = sw, ch = sh, cx = 0, cy = 0;
        if (srcAspect > targetAspect) {
            cw = Math.round(sh * targetAspect) & ~1;
            cx = ((sw - cw) / 2) & ~1;
        } else {
            ch = Math.round(sw / targetAspect) & ~1;
            cy = ((sh - ch) / 2) & ~1;
        }
        if (cw < 2 || ch < 2) return null;
        byte[] dst = new byte[tw * th * 3 / 2];
        // Y plane.
        for (int y = 0; y < th; y++) {
            int sy = cy + (y * ch / th);
            int sRow = sy * sw + cx;
            int dRow = y * tw;
            for (int x = 0; x < tw; x++) {
                dst[dRow + x] = src[sRow + (x * cw / tw)];
            }
        }
        // Interleaved VU plane (each chroma sample covers 2x2 luma pixels).
        int srcVuBase = sw * sh;
        int dstVuBase = tw * th;
        int scw = cw / 2, sch = ch / 2;
        int scx = cx / 2, scy = cy / 2;
        int uvTw = tw / 2, uvTh = th / 2;
        int srcUvRow = sw / 2;
        for (int y = 0; y < uvTh; y++) {
            int sy = scy + (y * sch / uvTh);
            int sRow = srcVuBase + sy * srcUvRow + scx;
            int dRow = dstVuBase + y * uvTw;
            for (int x = 0; x < uvTw; x++) {
                int sOff = sRow + (x * scw / uvTw) * 2;
                int dOff = dRow + x * 2;
                dst[dOff] = src[sOff];         // V
                dst[dOff + 1] = src[sOff + 1]; // U
            }
        }
        return dst;
    }

    /** Converts an Image in YUV_420_888 to a packed NV21 byte array. */
    private static byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if ((width & 1) != 0 || (height & 1) != 0) return null;
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) return null;
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixStride = planes[1].getPixelStride();
        int vPixStride = planes[2].getPixelStride();

        byte[] nv21 = new byte[width * height * 3 / 2];
        // Y plane.
        for (int row = 0; row < height; row++) {
            int srcPos = row * yRowStride;
            for (int col = 0; col < width; col++) {
                nv21[row * width + col] = yBuf.get(srcPos + col);
            }
        }
        // Interleaved VU plane (NV21 order).
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        int dst = width * height;
        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                nv21[dst++] = vBuf.get(row * vRowStride + col * vPixStride);
                nv21[dst++] = uBuf.get(row * uRowStride + col * uPixStride);
            }
        }
        return nv21;
    }
}
