package com.example.loomoagent;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.segway.robot.sdk.base.bind.ServiceBinder;
import com.segway.robot.sdk.locomotion.head.Head;
import com.segway.robot.sdk.locomotion.sbv.Base;
import com.segway.robot.sdk.vision.Vision;
import com.segway.robot.sdk.vision.frame.Frame;
import com.segway.robot.sdk.vision.stream.StreamInfo;
import com.segway.robot.sdk.vision.stream.StreamType;
import com.segway.robot.sdk.voice.Recognizer;
import com.segway.robot.sdk.voice.VoiceException;
import com.segway.robot.sdk.voice.recognition.RecognitionListener;
import com.segway.robot.sdk.voice.recognition.RecognitionResult;
import com.segway.robot.sdk.voice.recognition.WakeupListener;
import com.segway.robot.sdk.voice.recognition.WakeupResult;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class LoomoHttpServer {

    private static final String TAG = "LoomoHttpServer";
    public static final int PORT = 8080;
    private static final String BOUNDARY = "loomoboundary";
    private static final int SNAPSHOT_TIMEOUT_MS = 3000;
    private static final int STREAM_FRAME_DELAY_MS = 150;
    private static final int CAMERA_FRAME_STALE_MS = 4000;
    private static final int CAMERA_RECOVERY_INTERVAL_MS = 5000;
    private static final float TTS_VOLUME = 1.0f;
    private static final boolean ENABLE_ON_DEVICE_PREVIEW = false;
    private static final int DEFAULT_AUDIO_CAPTURE_MS = 2000;
    private static final int AUDIO_SAMPLE_RATE = 16000;
    private static final int AUDIO_PREROLL_MS = 250;
    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION;

    private static final String CORS = "Access-Control-Allow-Origin: *\r\n"
            + "Access-Control-Allow-Methods: GET,POST,OPTIONS\r\n"
            + "Access-Control-Allow-Headers: Content-Type\r\n";

    private final Context context;
    private final SurfaceView previewSurfaceView;
    private final SurfaceHolder previewHolder;

    private Vision vision;
    private Base base;
    private Head head;
    private TextToSpeech tts;
    private AudioManager audioManager;
    private Recognizer recognizer;

    private volatile boolean visionBound = false;
    private volatile boolean previewStarted = false;
    private volatile boolean surfaceReady = false;
    private volatile boolean baseBound = false;
    private volatile boolean headBound = false;
    private volatile boolean recognizerBound = false;
    private volatile boolean listeningActive = false;

    private volatile int colorWidth = 640;
    private volatile int colorHeight = 480;
    private volatile int receivedFrameCount = 0;
    private volatile int activeStreamClients = 0;
    private volatile boolean frameListenerActive = false;
    private volatile long lastFrameAt = 0L;
    private volatile String lastFrameStatus = "idle";
    private final Object frameLock = new Object();
    private byte[] latestFrameJpeg = null;
    private Camera legacyCamera = null;
    private SurfaceTexture legacySurfaceTexture = null;
    private volatile boolean legacyCameraStarted = false;
    private volatile String lastCameraSource = "vision";

    private volatile String lastRecognition = "";
    private volatile long lastRecognitionAt = 0L;
    private volatile String lastWakeup = "";
    private volatile long lastWakeupAt = 0L;
    private volatile String lastAudioSource = "none";
    private volatile int lastAudioPeak = 0;
    private volatile double lastAudioRms = 0.0;

    private volatile float targetLinear = 0f;
    private volatile float targetAngular = 0f;
    private volatile boolean driving = false;

    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> driveTask = null;
    private ScheduledFuture<?> cameraRecoveryTask = null;
    private boolean running = false;
    private volatile boolean cameraRecoveryRunning = false;
    private volatile long lastCameraRecoveryAt = 0L;
    private volatile int cameraRecoveryAttempts = 0;

    public LoomoHttpServer(Context context, SurfaceView previewSurfaceView) {
        this.context = context;
        this.previewSurfaceView = previewSurfaceView;
        this.previewHolder = previewSurfaceView.getHolder();
        initPreviewSurface();
        initSDKs();
    }

    private void initPreviewSurface() {
        previewHolder.setFixedSize(320, 240);
        previewHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                Log.i(TAG, "Preview surface created");
                startPreviewIfReady();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = true;
                Log.i(TAG, "Preview surface changed: " + width + "x" + height);
                startPreviewIfReady();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                previewStarted = false;
                Log.i(TAG, "Preview surface destroyed");
            }
        });
    }

    private void initSDKs() {
        vision = Vision.getInstance();
        vision.bindService(context, new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                visionBound = true;
                refreshColorStreamInfo();
                Log.i(TAG, "Vision bound");
                startPreviewIfReady();
                startFrameListenerIfReady();
            }

            @Override
            public void onUnbind(String reason) {
                visionBound = false;
                previewStarted = false;
                frameListenerActive = false;
                Log.w(TAG, "Vision unbound: " + reason);
            }
        });

        base = Base.getInstance();
        base.bindService(context, new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                baseBound = true;
                Log.i(TAG, "Base bound");
                try {
                    base.setControlMode(Base.CONTROL_MODE_RAW);
                } catch (Throwable e) {
                    Log.e(TAG, "Base init failed", e);
                }
            }

            @Override
            public void onUnbind(String reason) {
                baseBound = false;
                Log.w(TAG, "Base unbound: " + reason);
            }
        });

        head = Head.getInstance();
        head.bindService(context, new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                headBound = true;
                Log.i(TAG, "Head bound");
            }

            @Override
            public void onUnbind(String reason) {
                headBound = false;
                Log.w(TAG, "Head unbound: " + reason);
            }
        });

        recognizer = Recognizer.getInstance();
        recognizer.bindService(context, new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                recognizerBound = true;
                Log.i(TAG, "Recognizer bound");
                startAudioInput();
            }

            @Override
            public void onUnbind(String reason) {
                recognizerBound = false;
                listeningActive = false;
                Log.w(TAG, "Recognizer unbound: " + reason);
            }
        });

        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.GERMAN);
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                            .build());
                    ensureTtsVolume();
                    Log.i(TAG, "TTS ready");
                }
            }
        });

        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    private void ensureTtsVolume() {
        if (audioManager == null) return;
        try {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (maxVolume > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);
            }
        } catch (Throwable e) {
            Log.w(TAG, "ensureTtsVolume failed: " + e.getMessage());
        }
    }

    private void speakText(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        ensureTtsVolume();
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, TTS_VOLUME);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "loomo-agent-tts");
    }

    private void refreshColorStreamInfo() {
        if (!visionBound) return;
        try {
            StreamInfo[] infos = vision.getActivatedStreamInfo();
            if (infos == null) {
                Log.w(TAG, "Vision returned no activated stream info");
                return;
            }
            for (StreamInfo info : infos) {
                Log.i(TAG, "Stream info type=" + info.getStreamType()
                        + " size=" + info.getWidth() + "x" + info.getHeight()
                        + " fps=" + info.getFps()
                        + " pixel=" + info.getPixelFormat());
                if (info.getStreamType() == StreamType.COLOR) {
                    colorWidth = info.getWidth();
                    colorHeight = info.getHeight();
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "refreshColorStreamInfo failed: " + e.getMessage());
        }
    }

    private synchronized void startPreviewIfReady() {
        if (!ENABLE_ON_DEVICE_PREVIEW) return;
        if (!visionBound || !surfaceReady || previewStarted) return;
        if (previewHolder.getSurface() == null || !previewHolder.getSurface().isValid()) return;
        try {
            vision.startPreview(StreamType.COLOR, previewHolder.getSurface());
            previewStarted = true;
            Log.i(TAG, "Vision preview started");
        } catch (Throwable e) {
            Log.e(TAG, "startPreview failed", e);
        }
    }

    private synchronized void startFrameListenerIfReady() {
        if (!visionBound || frameListenerActive) return;
        try {
            vision.startListenFrame(StreamType.COLOR, new Vision.FrameListener() {
                @Override
                public void onNewFrame(int streamType, Frame frame) {
                    if (frame == null || frame.getByteBuffer() == null) {
                        lastFrameStatus = "null frame";
                        return;
                    }
                    if (streamType != StreamType.COLOR) {
                        return;
                    }
                    try {
                        byte[] jpeg = jpegFromColorFrame(frame);
                        if (jpeg == null || jpeg.length == 0) {
                            lastFrameStatus = "empty frame";
                            return;
                        }
                        synchronized (frameLock) {
                            latestFrameJpeg = jpeg;
                            lastFrameAt = System.currentTimeMillis();
                            receivedFrameCount++;
                            lastFrameStatus = "ok";
                            lastCameraSource = "vision";
                            frameLock.notifyAll();
                        }
                    } catch (Throwable e) {
                        lastFrameStatus = "frame error: " + e.getMessage();
                        Log.w(TAG, "Color frame handling failed: " + e.getMessage());
                    }
                }
            });
            frameListenerActive = true;
            lastFrameStatus = "listening";
            Log.i(TAG, "Vision frame listener started");
        } catch (Throwable e) {
            lastFrameStatus = "listen start failed";
            Log.e(TAG, "startListenFrame failed", e);
        }
    }

    private synchronized void stopFrameListener() {
        if (!frameListenerActive) return;
        try {
            vision.stopListenFrame(StreamType.COLOR);
        } catch (Throwable e) {
            Log.w(TAG, "stopListenFrame failed: " + e.getMessage());
        }
        frameListenerActive = false;
    }

    private synchronized void startLegacyCameraIfNeeded() {
        if (legacyCameraStarted) {
            return;
        }
        try {
            int cameraId = 0;
            int numberOfCameras = Camera.getNumberOfCameras();
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            for (int i = 0; i < numberOfCameras; i++) {
                Camera.getCameraInfo(i, cameraInfo);
                if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    cameraId = i;
                    break;
                }
            }

            legacyCamera = Camera.open(cameraId);
            Camera.Parameters params = legacyCamera.getParameters();
            Camera.Size chosen = null;
            for (Camera.Size size : params.getSupportedPreviewSizes()) {
                if (size.width == 640 && size.height == 480) {
                    chosen = size;
                    break;
                }
            }
            if (chosen == null && !params.getSupportedPreviewSizes().isEmpty()) {
                chosen = params.getSupportedPreviewSizes().get(0);
            }
            if (chosen != null) {
                params.setPreviewSize(chosen.width, chosen.height);
                colorWidth = chosen.width;
                colorHeight = chosen.height;
            }
            params.setPreviewFormat(ImageFormat.NV21);
            legacyCamera.setParameters(params);

            int bitsPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.NV21);
            int bufferSize = Math.max(1, colorWidth * colorHeight * bitsPerPixel / 8);
            legacySurfaceTexture = new SurfaceTexture(10);
            legacyCamera.setPreviewTexture(legacySurfaceTexture);
            legacyCamera.addCallbackBuffer(new byte[bufferSize]);
            legacyCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    try {
                        if (data == null || data.length == 0) {
                            lastFrameStatus = "camera1 empty frame";
                            return;
                        }
                        YuvImage image = new YuvImage(data, ImageFormat.NV21, colorWidth, colorHeight, null);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        image.compressToJpeg(new Rect(0, 0, colorWidth, colorHeight), 70, out);
                        synchronized (frameLock) {
                            latestFrameJpeg = out.toByteArray();
                            lastFrameAt = System.currentTimeMillis();
                            receivedFrameCount++;
                            lastFrameStatus = "ok";
                            lastCameraSource = "camera1";
                            frameLock.notifyAll();
                        }
                    } catch (Throwable e) {
                        lastFrameStatus = "camera1 frame error: " + e.getMessage();
                        Log.w(TAG, "Camera1 frame handling failed: " + e.getMessage());
                    } finally {
                        try {
                            camera.addCallbackBuffer(data);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
            legacyCamera.startPreview();
            legacyCameraStarted = true;
            lastCameraSource = "camera1";
            lastFrameStatus = "camera1 listening";
            Log.i(TAG, "Camera1 fallback started");
        } catch (Throwable e) {
            lastFrameStatus = "camera1 start failed: " + e.getMessage();
            Log.e(TAG, "Camera1 fallback start failed", e);
            stopLegacyCamera();
        }
    }

    private synchronized void stopLegacyCamera() {
        if (legacyCamera != null) {
            try {
                legacyCamera.setPreviewCallbackWithBuffer(null);
            } catch (Throwable ignored) {
            }
            try {
                legacyCamera.stopPreview();
            } catch (Throwable ignored) {
            }
            try {
                legacyCamera.release();
            } catch (Throwable ignored) {
            }
        }
        legacyCamera = null;
        if (legacySurfaceTexture != null) {
            try {
                legacySurfaceTexture.release();
            } catch (Throwable ignored) {
            }
        }
        legacySurfaceTexture = null;
        legacyCameraStarted = false;
    }

    private long getFrameAgeMs() {
        if (lastFrameAt <= 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, System.currentTimeMillis() - lastFrameAt);
    }

    private void clearCachedFrame(String reason) {
        synchronized (frameLock) {
            latestFrameJpeg = null;
            lastFrameAt = 0L;
            lastFrameStatus = reason;
        }
    }

    private void attemptCameraRecovery(String reason) {
        if (cameraRecoveryRunning) {
            return;
        }
        cameraRecoveryRunning = true;
        try {
            cameraRecoveryAttempts++;
            lastCameraRecoveryAt = System.currentTimeMillis();
            Log.w(TAG, "Attempting camera recovery #" + cameraRecoveryAttempts + " reason=" + reason);

            clearCachedFrame("recovering " + cameraRecoveryAttempts);
            stopLegacyCamera();
            stopFrameListener();
            stopPreview();
            refreshColorStreamInfo();
            startPreviewIfReady();
            startFrameListenerIfReady();

            if (!legacyCameraStarted && cameraRecoveryAttempts >= 2) {
                startLegacyCameraIfNeeded();
            }

            if (lastFrameAt <= 0L && cameraRecoveryAttempts >= 3) {
                lastFrameStatus = "host restart required";
            }
        } finally {
            cameraRecoveryRunning = false;
        }
    }

    private void scheduleCameraRecoveryWatchdog() {
        if (cameraRecoveryTask != null) {
            cameraRecoveryTask.cancel(true);
        }
        cameraRecoveryTask = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (!running || !visionBound) {
                    return;
                }

                long now = System.currentTimeMillis();
                long frameAge = getFrameAgeMs();

                if ((legacyCameraStarted || lastFrameAt > 0L) && frameAge < CAMERA_FRAME_STALE_MS) {
                    cameraRecoveryAttempts = 0;
                    return;
                }

                if (cameraRecoveryRunning || (now - lastCameraRecoveryAt) < CAMERA_RECOVERY_INTERVAL_MS) {
                    return;
                }

                attemptCameraRecovery(lastFrameAt > 0L ? "stale frame" : "startup/no frame");
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    private synchronized void stopPreview() {
        if (!previewStarted) return;
        try {
            vision.stopPreview(StreamType.COLOR);
        } catch (Throwable e) {
            Log.w(TAG, "stopPreview failed: " + e.getMessage());
        }
        previewStarted = false;
    }

    private byte[] jpegFromColorFrame(Frame frame) throws IOException {
        int width = colorWidth > 0 ? colorWidth : 640;
        int height = colorHeight > 0 ? colorHeight : 480;
        ByteBuffer source = frame.getByteBuffer();
        if (source == null) {
            throw new IOException("Frame buffer missing");
        }
        ByteBuffer buffer = source.duplicate();
        buffer.clear();
        int expectedBytes = width * height * 4;
        if (buffer.remaining() < expectedBytes) {
            throw new IOException("Frame buffer too small: " + buffer.remaining() + " < " + expectedBytes);
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        byte[] jpeg = jpegFromBitmap(bitmap, 75);
        bitmap.recycle();
        return jpeg;
    }

    private byte[] jpegFromBitmap(Bitmap bitmap, int quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    private synchronized byte[] captureFrameBlocking(int timeoutMs) {
        if (!visionBound) return null;
        refreshColorStreamInfo();
        startPreviewIfReady();
        startFrameListenerIfReady();

        synchronized (frameLock) {
            if (latestFrameJpeg != null && (System.currentTimeMillis() - lastFrameAt) < 2000) {
                return latestFrameJpeg.clone();
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (latestFrameJpeg == null && System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    frameLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (latestFrameJpeg != null) {
                return latestFrameJpeg.clone();
            }
        }
        startLegacyCameraIfNeeded();
        synchronized (frameLock) {
            if (latestFrameJpeg != null && (System.currentTimeMillis() - lastFrameAt) < 2000) {
                return latestFrameJpeg.clone();
            }
            try {
                frameLock.wait(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (latestFrameJpeg != null) {
                return latestFrameJpeg.clone();
            }
        }
        Log.w(TAG, "Timed out waiting for persistent camera frame; status=" + lastFrameStatus);
        return null;
    }

    private byte[] captureAudioWav(int durationMs) throws IOException {
        int captureMs = Math.max(250, Math.min(durationMs, 10000));
        AudioCaptureResult capture = captureAudioFromSource(AUDIO_SOURCE, captureMs);
        lastAudioSource = capture.sourceName;
        lastAudioPeak = capture.peak;
        lastAudioRms = capture.rms;
        Log.i(TAG, "Audio capture: source=" + capture.sourceName
                + " peak=" + capture.peak + " rms=" + capture.rms);
        analyzeAndBoostPcm(capture.pcm);
        return pcmToWav(capture.pcm, AUDIO_SAMPLE_RATE, 1, 16);
    }

    private AudioCaptureResult captureAudioFromSource(int source, int captureMs) throws IOException {
        int minBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int expectedBytes = AUDIO_SAMPLE_RATE * 2 * captureMs / 1000;
        int prerollBytes = AUDIO_SAMPLE_RATE * 2 * AUDIO_PREROLL_MS / 1000;
        int bufferSize = Math.max(minBufferSize, expectedBytes + prerollBytes);
        AudioRecord audioRecord = new AudioRecord(
                source,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            throw new IOException("AudioRecord init failed");
        }

        byte[] pcm = new byte[expectedBytes];
        byte[] discard = new byte[Math.max(minBufferSize, prerollBytes)];
        int offset = 0;
        try {
            audioRecord.startRecording();
            int discarded = 0;
            while (discarded < prerollBytes) {
                int read = audioRecord.read(discard, 0, Math.min(discard.length, prerollBytes - discarded));
                if (read <= 0) {
                    throw new IOException("AudioRecord preroll failed: " + read);
                }
                discarded += read;
            }
            while (offset < pcm.length) {
                int read = audioRecord.read(pcm, offset, pcm.length - offset);
                if (read <= 0) {
                    throw new IOException("AudioRecord read failed: " + read);
                }
                offset += read;
            }
        } finally {
            try {
                audioRecord.stop();
            } catch (Throwable ignored) {
            }
            audioRecord.release();
        }

        AudioMetrics metrics = measurePcm(pcm);
        return new AudioCaptureResult(pcm, metrics.peak, metrics.rms, audioSourceName(source));
    }

    private static final class AudioCaptureResult {
        final byte[] pcm;
        final int peak;
        final double rms;
        final String sourceName;

        AudioCaptureResult(byte[] pcm, int peak, double rms, String sourceName) {
            this.pcm = pcm;
            this.peak = peak;
            this.rms = rms;
            this.sourceName = sourceName;
        }
    }

    private static final class AudioMetrics {
        final int peak;
        final double rms;

        AudioMetrics(int peak, double rms) {
            this.peak = peak;
            this.rms = rms;
        }
    }

    private AudioMetrics measurePcm(byte[] pcm) {
        long sumSquares = 0L;
        int peak = 0;
        int samples = pcm.length / 2;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int abs = Math.abs((int) sample);
            if (abs > peak) {
                peak = abs;
            }
            sumSquares += (long) sample * sample;
        }

        double rms = samples > 0 ? Math.sqrt(sumSquares / (double) samples) : 0.0;
        return new AudioMetrics(peak, rms);
    }

    private void analyzeAndBoostPcm(byte[] pcm) {
        AudioMetrics metrics = measurePcm(pcm);
        int peak = metrics.peak;
        double rms = metrics.rms;

        if (peak < 8) {
            lastAudioPeak = peak;
            lastAudioRms = rms;
            Log.w(TAG, "Captured near-silence audio: source=" + lastAudioSource + " peak=" + peak + " rms=" + rms);
            return;
        }

        double targetPeak = peak < 128 ? 30000.0 : (peak < 512 ? 26000.0 : 22000.0);
        double gain = Math.min(1024.0, targetPeak / peak);
        if (gain <= 1.05) {
            lastAudioPeak = peak;
            lastAudioRms = rms;
            Log.i(TAG, "Captured audio: source=" + lastAudioSource + " peak=" + peak + " rms=" + rms);
            return;
        }

        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int boosted = (int) Math.round(sample * gain);
            if (boosted > Short.MAX_VALUE) boosted = Short.MAX_VALUE;
            if (boosted < Short.MIN_VALUE) boosted = Short.MIN_VALUE;
            pcm[i] = (byte) (boosted & 0xFF);
            pcm[i + 1] = (byte) ((boosted >> 8) & 0xFF);
        }
        AudioMetrics boostedMetrics = measurePcm(pcm);
        lastAudioPeak = boostedMetrics.peak;
        lastAudioRms = boostedMetrics.rms;
        Log.i(TAG, "Boosted audio: source=" + lastAudioSource
                + " rawPeak=" + peak
                + " rawRms=" + rms
                + " gain=" + gain
                + " boostedPeak=" + boostedMetrics.peak
                + " boostedRms=" + boostedMetrics.rms);
    }

    private String audioSourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.DEFAULT:
                return "DEFAULT";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION:
                return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.CAMCORDER:
                return "CAMCORDER";
            case MediaRecorder.AudioSource.MIC:
                return "MIC";
            default:
                return "SRC_" + source;
        }
    }

    private byte[] pcmToWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        out.write("RIFF".getBytes());
        out.write(intToLeBytes(36 + pcm.length));
        out.write("WAVE".getBytes());
        out.write("fmt ".getBytes());
        out.write(intToLeBytes(16));
        out.write(shortToLeBytes((short) 1));
        out.write(shortToLeBytes((short) channels));
        out.write(intToLeBytes(sampleRate));
        out.write(intToLeBytes(byteRate));
        out.write(shortToLeBytes((short) blockAlign));
        out.write(shortToLeBytes((short) bitsPerSample));
        out.write("data".getBytes());
        out.write(intToLeBytes(pcm.length));
        out.write(pcm);
        return out.toByteArray();
    }

    private byte[] intToLeBytes(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private byte[] shortToLeBytes(short value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array();
    }

    private synchronized void startAudioInput() {
        if (!recognizerBound || listeningActive) return;
        try {
            recognizer.startWakeupAndRecognition(new LoomoWakeupListener(), new LoomoRecognitionListener());
            listeningActive = true;
            Log.i(TAG, "Audio input started");
        } catch (VoiceException e) {
            Log.e(TAG, "startAudioInput failed", e);
        }
    }

    private synchronized void stopAudioInput() {
        if (!recognizerBound || !listeningActive) return;
        try {
            recognizer.stopRecognition();
        } catch (VoiceException e) {
            Log.e(TAG, "stopAudioInput failed", e);
        }
        listeningActive = false;
        Log.i(TAG, "Audio input stopped");
    }

    public void start() {
        running = true;

        driveTask = scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (!baseBound) return;
                try {
                    base.setControlMode(Base.CONTROL_MODE_RAW);
                    base.setLinearVelocity(driving ? targetLinear : 0f);
                    base.setAngularVelocity(driving ? targetAngular : 0f);
                } catch (Throwable e) {
                    Log.e(TAG, "Heartbeat error: " + e.getMessage());
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    Log.i(TAG, "HTTP server on port " + PORT);
                    while (running) {
                        Socket client = serverSocket.accept();
                        executor.execute(new ClientHandler(client));
                    }
                } catch (Exception e) {
                    if (running) Log.e(TAG, "Server error", e);
                }
            }
        });

        scheduleCameraRecoveryWatchdog();
    }

    public void stop() {
        running = false;
        driving = false;
        stopAudioInput();
        stopPreview();
        stopLegacyCamera();
        try {
            stopFrameListener();
        } catch (Throwable ignored) {
        }
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        executor.shutdownNow();
        if (driveTask != null) {
            driveTask.cancel(true);
        }
        if (cameraRecoveryTask != null) {
            cameraRecoveryTask.cancel(true);
        }
        scheduler.shutdownNow();
        try {
            if (recognizer != null) recognizer.unbindService();
        } catch (Throwable ignored) {
        }
        try {
            if (vision != null) vision.unbindService();
        } catch (Throwable ignored) {
        }
        try {
            if (base != null) base.unbindService();
        } catch (Throwable ignored) {
        }
        try {
            if (head != null) head.unbindService();
        } catch (Throwable ignored) {
        }
        if (tts != null) {
            tts.shutdown();
        }
    }

    private JSONObject buildStatusJson() {
        JSONObject status = new JSONObject();
        try {
            status.put("status", "ok");
            status.put("port", PORT);
            status.put("vision", visionBound);
            status.put("preview", previewStarted);
            status.put("base", baseBound);
            status.put("head", headBound);
            status.put("recognizer", recognizerBound);
            status.put("listening", listeningActive);
            status.put("streamClients", activeStreamClients);
            status.put("frameCallbacks", receivedFrameCount);
            status.put("frameListener", frameListenerActive);
            status.put("legacyCamera", legacyCameraStarted);
            status.put("cameraSource", lastCameraSource);
            status.put("lastFrameAt", lastFrameAt);
            status.put("lastFrameStatus", lastFrameStatus);
            status.put("cameraRecoveryAttempts", cameraRecoveryAttempts);
            status.put("colorWidth", colorWidth);
            status.put("colorHeight", colorHeight);
            status.put("lastRecognition", lastRecognition);
            status.put("lastRecognitionAt", lastRecognitionAt);
            status.put("lastWakeup", lastWakeup);
            status.put("lastWakeupAt", lastWakeupAt);
            status.put("lastAudioSource", lastAudioSource);
            status.put("lastAudioPeak", lastAudioPeak);
            status.put("lastAudioRms", lastAudioRms);
        } catch (Exception ignored) {
        }
        return status;
    }

    public JSONObject getStatusSnapshot() {
        return buildStatusJson();
    }

    private JSONObject buildAudioJson() {
        JSONObject audio = new JSONObject();
        try {
            audio.put("recognizer", recognizerBound);
            audio.put("listening", listeningActive);
            audio.put("lastRecognition", lastRecognition);
            audio.put("lastRecognitionAt", lastRecognitionAt);
            audio.put("lastWakeup", lastWakeup);
            audio.put("lastWakeupAt", lastWakeupAt);
            audio.put("lastAudioSource", lastAudioSource);
            audio.put("lastAudioPeak", lastAudioPeak);
            audio.put("lastAudioRms", lastAudioRms);
        } catch (Exception ignored) {
        }
        return audio;
    }

    private void handleCommand(String json) {
        Log.i(TAG, "CMD: " + json + " [base=" + baseBound + " head=" + headBound + "]");
        try {
            JSONObject object = new JSONObject(json);
            String cmd = object.getString("cmd");

            if ("move".equals(cmd)) {
                if (!baseBound) {
                    Log.e(TAG, "Base not bound");
                    return;
                }
                targetLinear = (float) object.optDouble("linear", 0);
                targetAngular = (float) object.optDouble("angular", 0);
                driving = (targetLinear != 0f || targetAngular != 0f);
                Log.i(TAG, "Drive: l=" + targetLinear + " a=" + targetAngular);
            } else if ("head".equals(cmd)) {
                if (!headBound) {
                    Log.e(TAG, "Head not bound");
                    return;
                }
                head.setMode(Head.MODE_SMOOTH_TACKING);
                head.setWorldYaw((float) object.optDouble("yaw", 0));
                head.setWorldPitch((float) object.optDouble("pitch", 0));
            } else if ("speak".equals(cmd)) {
                speakText(object.optString("text", ""));
            } else if ("listen".equals(cmd) || "audio".equals(cmd)) {
                String action = object.optString("action", "start");
                if ("stop".equals(action)) {
                    stopAudioInput();
                } else {
                    startAudioInput();
                }
            } else if ("vision".equals(cmd)) {
                String action = object.optString("action", "start");
                if ("stop".equals(action)) {
                    stopPreview();
                } else {
                    startPreviewIfReady();
                }
            } else if ("stop".equals(cmd)) {
                driving = false;
                targetLinear = 0f;
                targetAngular = 0f;
                if (baseBound) {
                    base.setLinearVelocity(0);
                    base.setAngularVelocity(0);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Command error", e);
        }
    }

    private class LoomoWakeupListener implements WakeupListener {
        @Override
        public void onStandby() {
            Log.i(TAG, "Recognizer standby");
        }

        @Override
        public void onWakeupResult(WakeupResult wakeupResult) {
            lastWakeup = wakeupResult == null ? "" : wakeupResult.toString();
            lastWakeupAt = System.currentTimeMillis();
            Log.i(TAG, "Wakeup: " + lastWakeup);
        }

        @Override
        public void onWakeupError(String error) {
            Log.w(TAG, "Wakeup error: " + error);
        }
    }

    private class LoomoRecognitionListener implements RecognitionListener {
        @Override
        public void onRecognitionStart() {
            Log.i(TAG, "Recognition started");
        }

        @Override
        public boolean onRecognitionResult(RecognitionResult recognitionResult) {
            lastRecognition = recognitionResult == null ? "" : recognitionResult.getRecognitionResult();
            lastRecognitionAt = System.currentTimeMillis();
            Log.i(TAG, "Recognition result: " + lastRecognition);
            return false;
        }

        @Override
        public boolean onRecognitionError(String error) {
            Log.w(TAG, "Recognition error: " + error);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String handleWifiConnect(String jsonBody) {
        try {
            JSONObject req = new JSONObject(jsonBody);
            String ssid = req.getString("ssid");
            String identity = req.getString("identity");
            String password = req.getString("password");
            String eapMethod = req.optString("eap", "PEAP");
            String phase2 = req.optString("phase2", "MSCHAPV2");

            // Save credentials for auto-connect on next startup
            context.getSharedPreferences("wifi_enterprise", Context.MODE_PRIVATE).edit()
                    .putString("ssid", ssid)
                    .putString("identity", identity)
                    .putString("password", password)
                    .putString("eap", eapMethod)
                    .putString("phase2", phase2)
                    .apply();

            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return "{\"ok\":false,\"error\":\"WifiManager not available\"}";
            }

            if (!wifiManager.isWifiEnabled()) {
                wifiManager.setWifiEnabled(true);
                Thread.sleep(2000);
            }

            // Remove existing config for this SSID if present
            for (WifiConfiguration existing : wifiManager.getConfiguredNetworks()) {
                if (existing.SSID != null && existing.SSID.equals("\"" + ssid + "\"")) {
                    wifiManager.removeNetwork(existing.networkId);
                }
            }

            WifiConfiguration config = new WifiConfiguration();
            config.SSID = "\"" + ssid + "\"";
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);

            WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
            enterpriseConfig.setIdentity(identity);
            enterpriseConfig.setPassword(password);

            if ("PEAP".equalsIgnoreCase(eapMethod)) {
                enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.PEAP);
            } else if ("TTLS".equalsIgnoreCase(eapMethod)) {
                enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
            } else if ("TLS".equalsIgnoreCase(eapMethod)) {
                enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
            } else if ("PWD".equalsIgnoreCase(eapMethod)) {
                enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.PWD);
            }

            if ("MSCHAPV2".equalsIgnoreCase(phase2)) {
                enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2);
            } else if ("GTC".equalsIgnoreCase(phase2)) {
                enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);
            } else if ("PAP".equalsIgnoreCase(phase2)) {
                enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.PAP);
            }

            config.enterpriseConfig = enterpriseConfig;

            int netId = wifiManager.addNetwork(config);
            if (netId == -1) {
                return "{\"ok\":false,\"error\":\"addNetwork failed - check permissions\"}";
            }

            wifiManager.disconnect();
            boolean enabled = wifiManager.enableNetwork(netId, true);
            boolean reconnected = wifiManager.reconnect();

            JSONObject result = new JSONObject();
            result.put("ok", enabled && reconnected);
            result.put("networkId", netId);
            result.put("ssid", ssid);
            result.put("eap", eapMethod);
            result.put("phase2", phase2);
            result.put("enabled", enabled);
            result.put("reconnected", reconnected);
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "WiFi connect failed", e);
            return "{\"ok\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                OutputStream out = socket.getOutputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isEmpty()) {
                    socket.close();
                    return;
                }

                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts.length > 1 ? parts[1] : "/";

                int contentLength = 0;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.split(":")[1].trim());
                    }
                }

                if ("OPTIONS".equals(method)) {
                    out.write(("HTTP/1.1 204 No Content\r\n" + CORS + "\r\n").getBytes());
                    out.flush();
                    socket.close();
                    return;
                }

                if ("GET".equals(method) && path.startsWith("/snapshot")) {
                    byte[] jpeg = captureFrameBlocking(SNAPSHOT_TIMEOUT_MS);
                    if (jpeg != null) {
                        out.write(("HTTP/1.1 200 OK\r\n" + CORS
                                + "Content-Type: image/jpeg\r\n"
                                + "Content-Length: " + jpeg.length + "\r\n\r\n").getBytes());
                        out.write(jpeg);
                    } else {
                        out.write(("HTTP/1.1 503 Service Unavailable\r\n" + CORS
                                + "Content-Type: text/plain\r\n\r\nNo frame - vision="
                                + visionBound + " preview=" + previewStarted
                                + " surface=" + surfaceReady
                                + " callbacks=" + receivedFrameCount
                                + " listener=" + frameListenerActive
                                + " frameStatus=" + lastFrameStatus
                                + " size=" + colorWidth + "x" + colorHeight).getBytes());
                    }
                    out.flush();
                    socket.close();
                    return;
                }

                if ("GET".equals(method) && path.startsWith("/stream")) {
                    out.write(("HTTP/1.1 200 OK\r\n" + CORS
                            + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                            + "Cache-Control: no-cache\r\n\r\n").getBytes());
                    out.flush();

                    activeStreamClients++;
                    int failures = 0;
                    try {
                        while (running && !socket.isClosed()) {
                            byte[] jpeg = captureFrameBlocking(SNAPSHOT_TIMEOUT_MS);
                            if (jpeg == null) {
                                failures++;
                                if (failures >= 3) {
                                    Log.w(TAG, "Stopping MJPEG stream after repeated frame timeouts");
                                    break;
                                }
                                continue;
                            }
                            failures = 0;
                            out.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: "
                                    + jpeg.length + "\r\n\r\n").getBytes());
                            out.write(jpeg);
                            out.write("\r\n".getBytes());
                            out.flush();
                            Thread.sleep(STREAM_FRAME_DELAY_MS);
                        }
                    } finally {
                        activeStreamClients--;
                        socket.close();
                    }
                    return;
                }

                if ("GET".equals(method) && path.startsWith("/audio.wav")) {
                    byte[] wav = captureAudioWav(DEFAULT_AUDIO_CAPTURE_MS);
                    out.write(("HTTP/1.1 200 OK\r\n" + CORS
                            + "Content-Type: audio/wav\r\n"
                            + "Content-Length: " + wav.length + "\r\n\r\n").getBytes());
                    out.write(wav);
                    out.flush();
                    socket.close();
                    return;
                }

                if ("GET".equals(method) && path.startsWith("/audio")) {
                    byte[] body = buildAudioJson().toString().getBytes();
                    out.write(("HTTP/1.1 200 OK\r\n" + CORS
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + body.length + "\r\n\r\n").getBytes());
                    out.write(body);
                    out.flush();
                    socket.close();
                    return;
                }

                if ("POST".equals(method) && path.startsWith("/wifi-connect")) {
                    char[] wbody = new char[Math.max(0, contentLength)];
                    int wread = reader.read(wbody, 0, contentLength);
                    String wifiResult = handleWifiConnect(new String(wbody, 0, Math.max(0, wread)));
                    byte[] wifiResp = wifiResult.getBytes();
                    out.write(("HTTP/1.1 200 OK\r\n" + CORS
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + wifiResp.length + "\r\n\r\n").getBytes());
                    out.write(wifiResp);
                    out.flush();
                    socket.close();
                    return;
                }

                if ("POST".equals(method) && path.startsWith("/cmd")) {
                    char[] body = new char[Math.max(0, contentLength)];
                    int read = reader.read(body, 0, contentLength);
                    handleCommand(new String(body, 0, Math.max(0, read)));
                    out.write(("HTTP/1.1 200 OK\r\n" + CORS
                            + "Content-Type: application/json\r\n\r\n{\"ok\":true}").getBytes());
                    out.flush();
                    socket.close();
                    return;
                }

                byte[] body = buildStatusJson().toString().getBytes();
                out.write(("HTTP/1.1 200 OK\r\n" + CORS
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + body.length + "\r\n\r\n").getBytes());
                out.write(body);
                out.flush();
                socket.close();
            } catch (Exception e) {
                Log.w(TAG, "Client error: " + e.getMessage());
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
