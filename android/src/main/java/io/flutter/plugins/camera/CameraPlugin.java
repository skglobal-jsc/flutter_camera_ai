package io.flutter.plugins.camera;

import static android.content.Context.SENSOR_SERVICE;
import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.FlutterView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CameraPlugin implements MethodCallHandler {

    private static final int CAMERA_REQUEST_ID = 513469796;
    private static final String TAG = "CameraPlugin";
    private final static boolean IS_NEXUS_5X = Build.MODEL.equalsIgnoreCase("Nexus 5X");
    private final static boolean IS_HUAWEI_BRAND = Build.MANUFACTURER.equalsIgnoreCase("HUAWEI");
    private final String SONY_DEVICE = "Sony";
    private final String FUJITSU_DEVICE = "FUJITSU";
    private final String SAMSUNG_DEVICE = "samsung";
    private final String HUAWEI_DEVICE = "huawei";
    private final String XIAOMI_DEVICE = "Xiaomi";
    private final String GOOGLE_DEVICE = "Google";
    private final static boolean IS_7_0_AND_ABOVE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;

    private static MethodChannel channel;
    private static CameraManager cameraManager;
    private final FlutterView view;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private Camera camera;
    private Registrar registrar;
    private Activity activity;
    // The code to run after requesting camera permissions.
    private Runnable cameraPermissionContinuation;
    private final OrientationEventListener orientationEventListener;
    private int currentOrientation = ORIENTATION_UNKNOWN;
    private boolean isFrameMode = false;
    private boolean isFlashOn = false;

    // Detect flash by light sensor
    private SensorManager mSensorManager;
    private Sensor mLightSensor;

    // Keep current rotation of device
    private int startRotation = 0;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private CameraPlugin(Registrar registrar, FlutterView view) {
        MovingDetectorJNI.newInstance();
        this.registrar = registrar;
        this.view = view;
        this.activity = registrar.activity();

        Log.d("IS_HUAWEI_BRAND:", Build.MANUFACTURER + "");

        orientationEventListener =
                new OrientationEventListener(registrar.activity().getApplicationContext()) {
                    @Override
                    public void onOrientationChanged(int i) {
                        if (i == ORIENTATION_UNKNOWN) {
                            return;
                        }
                        // Convert the raw deg angle to the nearest multiple of 90.
                        int currentOrientation = ((int) Math.round(i / 90.0) * 90) % 360;
                        if (startRotation != currentOrientation) {
                            startRotation = currentOrientation;
                            if (camera != null && camera.captureRequestBuilder != null) {
                                camera.updateRotation();
                            }
                        }
                    }
                };

        registrar.addRequestPermissionsResultListener(new CameraRequestPermissionsListener());

        // Register light sensor
        registerLightSensor(registrar.activity());
        registerSensorLight();
    }

    public static void registerWith(Registrar registrar) {
        if (registrar.activity() == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // When a background flutter view tries to register the plugin, the registrar has no activity.
            // We stop the registration process as this plugin is foreground only. Also, if the sdk is
            // less than 21 (min sdk for Camera2) we don't register the plugin.
            return;
        }

        channel = new MethodChannel(registrar.messenger(), "plugins.flutter.io/camera");
        cameraManager = (CameraManager) registrar.activity().getSystemService(Context.CAMERA_SERVICE);
        channel.setMethodCallHandler(new CameraPlugin(registrar, registrar.view()));
    }

    //    final static double LIGHT_THROTTLE = 20.0;
//    final static int LIGHT_THROTTLE_TIMES = 3;
//    private int currentThrottleTimes = 0;
    private boolean autoFlashLight = false;

    // Implement a sensorLightListener to receive updates
    SensorEventListener sensorLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
//            Log.d(TAG, "Light " + event.values[0]);
//            double b = event.values[0];
//            int r = b < 20 ? -1 : (b > 200 ? 1 : 0);
//            channel.invokeMethod("camera.brightnessLevel", r);
            // Auto check flash
//            if (autoFlashLight) {
//                int sw = 0;
//                currentThrottleTimes += (event.values[0] > LIGHT_THROTTLE) ? 1 : -1;
//                if (currentThrottleTimes > LIGHT_THROTTLE_TIMES) {
//                    // turn on light
//                    sw = 1;
//                } else if (currentThrottleTimes < -1 * LIGHT_THROTTLE_TIMES) {
//                    // turn off light
//                    sw = -1;
//                }
//
//                if (sw != 0) {
//                    Log.d(TAG, "L " + sw);
//                    boolean turnOn = sw < 0;
//                    currentThrottleTimes = 0;
//                    if (camera != null && isFlashOn != turnOn) {
//                        camera.turnFlashLight(turnOn);
//                    }
//                }
//            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {
        }
    };

    public void registerLightSensor(Activity activity) {
        // Obtain references to the SensorManager and the Light Sensor
        mSensorManager = (SensorManager) activity.getSystemService(SENSOR_SERVICE);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    private void unregisterSensorLight() {
        if (autoFlashLight) {
            autoFlashLight = false; // turn off auto flash light
            mSensorManager.unregisterListener(sensorLightListener);
        }
    }

    private void registerSensorLight() {
        if (autoFlashLight == false) {
            autoFlashLight = true; // turn true auto flash light
            mSensorManager.registerListener(sensorLightListener, mLightSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    public void onMethodCall(MethodCall call, final Result result) {
        switch (call.method) {
            case "availableCameras":
                try {
                    String[] cameraNames = cameraManager.getCameraIdList();
                    List<Map<String, Object>> cameras = new ArrayList<>();
                    for (String cameraName : cameraNames) {
                        HashMap<String, Object> details = new HashMap<>();
                        CameraCharacteristics characteristics =
                                cameraManager.getCameraCharacteristics(cameraName);
//                        int[] flashModeValues = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
//                        Log.d(TAG, "flash " + Arrays.toString(flashModeValues));
                        details.put("name", cameraName);
                        @SuppressWarnings("ConstantConditions")
                        int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        details.put("sensorOrientation", sensorOrientation);

                        int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        switch (lensFacing) {
                            case CameraMetadata.LENS_FACING_FRONT:
                                details.put("lensFacing", "front");
                                break;
                            case CameraMetadata.LENS_FACING_BACK:
                                details.put("lensFacing", "back");
                                break;
                            case CameraMetadata.LENS_FACING_EXTERNAL:
                                details.put("lensFacing", "external");
                                break;
                        }
                        cameras.add(details);
                    }
                    result.success(cameras);
                } catch (Exception e) {
                    handleException(e, result);
                }
                break;
            case "initialize": {
                String cameraName = call.argument("cameraName");
                String resolutionPreset = call.argument("resolutionPreset");
                boolean enableAudio = call.argument("enableAudio");
                if (camera != null) {
                    camera.close();
                }
                camera = new Camera(cameraName, resolutionPreset, result, enableAudio);
                orientationEventListener.enable();
                break;
            }
            case "takePicture": {
                camera.takePicture((String) call.argument("path"), result);
                break;
            }
            case "prepareForVideoRecording": {
                // This optimization is not required for Android.
                result.success(null);
                break;
            }
            case "startVideoRecording": {
                final String filePath = call.argument("filePath");
                camera.startVideoRecording(filePath, result);
                break;
            }
            case "stopVideoRecording": {
                camera.stopVideoRecording(result);
                break;
            }
            case "startImageStream": {
                // Register sensor light callback when on image stream to keep reverse state before
                if (autoFlashLight) {
                    mSensorManager.registerListener(sensorLightListener, mLightSensor, SensorManager.SENSOR_DELAY_UI);
                }

                // Start image stream
                try {
                    camera.startPreviewWithImageStream();
                    result.success(null);
                } catch (Exception e) {
                    handleException(e, result);
                }
                break;
            }
            case "stopImageStream": {
                // Remove sensor light callback when off image stream
                if (autoFlashLight) {
                    mSensorManager.unregisterListener(sensorLightListener);
                }

                // Stop image stream
                try {
                    camera.startPreview();
                    result.success(null);
                } catch (Exception e) {
                    handleException(e, result);
                }
                break;
            }
            case "dispose": {
                if (camera != null) {
                    camera.dispose();
                }
                orientationEventListener.disable();
                result.success(null);
                break;
            }
            case "compareFrame": {
                byte[] bytesOfImg1 = call.argument("bytesOfImg1");
                byte[] bytesOfImg2 = call.argument("bytesOfImg2");
                int w = call.argument("width");
                int h = call.argument("height");
                boolean isDiff = camera.isDiffFrame(w, h, bytesOfImg1, bytesOfImg2);
                result.success(isDiff);
                break;
            }
            case "setFrameModeEnable": {
                isFrameMode = call.arguments != null ? call.arguments.toString().equals("YES") : false;
                result.success(true);
                break;
            }
//            case "setTorchAuto": {
//                registerSensorLight();
//                result.success(true);
//                break;
//            }
            case "setTorchEnable": {
//                unregisterSensorLight();
                boolean turnOn = (boolean) call.arguments;
                try {
                    if (camera != null) {
                        camera.turnFlashLight(turnOn);
                        result.success(true);
                    } else {
                        result.error("Camera is NULL", "", "");
                    }
                } catch (Exception e) {
                    handleException(e, result);
                }
                break;
            }

            // For debugs only
//            case "setStartRotation": {
//                int rotate = (int) call.arguments;
//                startRotation = rotate;
//                result.success(true);
//                break;
//            }
            default:
                result.notImplemented();
                break;
        }
    }

    // We move catching CameraAccessException out of onMethodCall because it causes a crash
    // on plugin registration for sdks incompatible with Camera2 (< 21). We want this plugin to
    // to be able to compile with <21 sdks for apps that want the camera and support earlier version.
    @SuppressWarnings("ConstantConditions")
    private void handleException(Exception exception, Result result) {
        if (exception instanceof CameraAccessException) {
            result.error("CameraAccess", exception.getMessage(), null);
        }

        throw (RuntimeException) exception;
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow.
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private class CameraRequestPermissionsListener
            implements PluginRegistry.RequestPermissionsResultListener {
        @Override
        public boolean onRequestPermissionsResult(int id, String[] permissions, int[] grantResults) {
            if (id == CAMERA_REQUEST_ID) {
                cameraPermissionContinuation.run();
                return true;
            }
            return false;
        }
    }

    private class Camera {
        private final FlutterView.SurfaceTextureEntry textureEntry;
        private CameraDevice cameraDevice;
        private Surface previewSurface;
        private CameraCaptureSession cameraCaptureSession;
        private EventChannel.EventSink eventSink;
        private ImageReader pictureImageReader;
        private ImageReader imageStreamReader;
        private int sensorOrientation;
        private boolean isFrontFacing;
        private String cameraName;
        private Size captureSize;
        private Size previewSize;
        private CaptureRequest.Builder captureRequestBuilder;
        private Size videoSize;
        private MediaRecorder mediaRecorder;
        private boolean recordingVideo;
        private boolean enableAudio;
        private boolean enableFlash = false;

        Camera(
                final String cameraName,
                final String resolutionPreset,
                @NonNull final Result result,
                final boolean enableAudio) {

            this.cameraName = cameraName;
            textureEntry = view.createSurfaceTexture();
            registerEventChannel();
            try {
                int minHeight;
                switch (resolutionPreset) {
                    case "high":
                        minHeight = 720;
                        break;
                    case "medium":
                        minHeight = 480;
                        break;
                    case "low":
                        minHeight = 240;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown preset: " + resolutionPreset);
                }

                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
                StreamConfigurationMap streamConfigurationMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                //noinspection ConstantConditions
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                //noinspection ConstantConditions
                isFrontFacing =
                        characteristics.get(CameraCharacteristics.LENS_FACING)
                                == CameraMetadata.LENS_FACING_FRONT;
                computeBestCaptureSize(streamConfigurationMap);
                computeBestPreviewAndRecordingSize(streamConfigurationMap, minHeight, captureSize);
                if (cameraPermissionContinuation != null) {
                    result.error("cameraPermission", "Camera permission request ongoing", null);
                }
                cameraPermissionContinuation =
                        new Runnable() {
                            @Override
                            public void run() {
                                cameraPermissionContinuation = null;
                                if (!hasCameraPermission()) {
                                    result.error(
                                            "cameraPermission", "MediaRecorderCamera permission not granted", null);
                                    return;
                                }
                                if (enableAudio && !hasAudioPermission()) {
                                    result.error(
                                            "cameraPermission", "MediaRecorderAudio permission not granted", null);
                                    return;
                                }
                                open(result);
                            }
                        };
                if (hasCameraPermission() && (!enableAudio || hasAudioPermission())) {
                    cameraPermissionContinuation.run();
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        final Activity activity = registrar.activity();
                        if (activity == null) {
                            throw new IllegalStateException("No activity available!");
                        }

                        activity.requestPermissions(
                                enableAudio
                                        ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}
                                        : new String[]{Manifest.permission.CAMERA},
                                CAMERA_REQUEST_ID);
                    }
                }
            } catch (CameraAccessException e) {
                result.error("CameraAccess", e.getMessage(), null);
            } catch (IllegalArgumentException e) {
                result.error("IllegalArgumentException", e.getMessage(), null);
            }
        }

        private void registerEventChannel() {
            new EventChannel(
                    registrar.messenger(), "flutter.io/cameraPlugin/cameraEvents" + textureEntry.id())
                    .setStreamHandler(
                            new EventChannel.StreamHandler() {
                                @Override
                                public void onListen(Object arguments, EventChannel.EventSink eventSink) {
                                    Camera.this.eventSink = eventSink;
                                }

                                @Override
                                public void onCancel(Object arguments) {
                                    Camera.this.eventSink = null;
                                }
                            });
        }

        private boolean hasCameraPermission() {
            final Activity activity = registrar.activity();
            if (activity == null) {
                throw new IllegalStateException("No activity available!");
            }

            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || activity.checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private boolean hasAudioPermission() {
            final Activity activity = registrar.activity();
            if (activity == null) {
                throw new IllegalStateException("No activity available!");
            }

            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private void computeBestPreviewAndRecordingSize(StreamConfigurationMap streamConfigurationMap, int minHeight, Size captureSize) {
            Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);

            // Preview size and video size should not be greater than screen resolution or 1080.
            Point screenResolution = new Point();

            final Activity activity = registrar.activity();
            if (activity == null) {
                throw new IllegalStateException("No activity available!");
            }

            Display display = activity.getWindowManager().getDefaultDisplay();
            display.getRealSize(screenResolution);

            final boolean swapWH = getMediaOrientation() % 180 == 90;
            int screenWidth = swapWH ? screenResolution.y : screenResolution.x;
            int screenHeight = swapWH ? screenResolution.x : screenResolution.y;

            List<Size> goodEnough = new ArrayList<>();
            for (Size s : sizes) {
                if (minHeight <= s.getHeight()
                        && s.getWidth() <= screenWidth
                        && s.getHeight() <= screenHeight
                        && s.getHeight() <= 1080) {
                    goodEnough.add(s);
                }
            }

            Collections.sort(goodEnough, new CompareSizesByArea());

            if (goodEnough.isEmpty()) {
                previewSize = sizes[0];
                videoSize = sizes[0];
            } else {
                float captureSizeRatio = (float) captureSize.getWidth() / captureSize.getHeight();

                previewSize = goodEnough.get(0);
                for (Size s : goodEnough) {
                    if ((float) s.getWidth() / s.getHeight() == captureSizeRatio) {
                        previewSize = s;
                        break;
                    }
                }

                Collections.reverse(goodEnough);
                videoSize = goodEnough.get(0);
                for (Size s : goodEnough) {
                    if ((float) s.getWidth() / s.getHeight() == captureSizeRatio) {
                        videoSize = s;
                        break;
                    }
                }
            }
        }

        private void computeBestCaptureSize(StreamConfigurationMap streamConfigurationMap) {
            // For still image captures, we use the largest available size.
            captureSize =
                    Collections.max(
                            Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)),
                            new CompareSizesByArea());
        }

        private void prepareMediaRecorder(String outputFilePath) throws IOException {
            if (mediaRecorder != null) {
                mediaRecorder.release();
            }
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoEncodingBitRate(1024 * 1000);
            mediaRecorder.setVideoFrameRate(27);
            mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            if (enableAudio) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioSamplingRate(16000);
            }
            mediaRecorder.setOutputFile(outputFilePath);
            mediaRecorder.setOrientationHint(getMediaOrientation());

            mediaRecorder.prepare();
        }

        private void open(@Nullable final Result result) {
            if (!hasCameraPermission()) {
                if (result != null)
                    result.error("cameraPermission", "Camera permission not granted", null);
            } else {
                try {
                    pictureImageReader =
                            ImageReader.newInstance(
                                    captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);

                    // Used to steam image byte data to dart side.
                    imageStreamReader =
                            ImageReader.newInstance(
                                    previewSize.getWidth(), previewSize.getHeight(), ImageFormat.JPEG, 2);

                    cameraManager.openCamera(
                            cameraName,
                            new CameraDevice.StateCallback() {
                                @Override
                                public void onOpened(@NonNull CameraDevice cameraDevice) {
                                    Camera.this.cameraDevice = cameraDevice;

                                    try {
                                        startPreview();
                                    } catch (CameraAccessException e) {
                                        if (result != null)
                                            result.error("CameraAccess", e.getMessage(), null);
                                        cameraDevice.close();
                                        Camera.this.cameraDevice = null;
                                        return;
                                    }

                                    if (result != null) {
                                        Map<String, Object> reply = new HashMap<>();
                                        reply.put("textureId", textureEntry.id());
                                        reply.put("previewWidth", previewSize.getWidth());
                                        reply.put("previewHeight", previewSize.getHeight());
                                        result.success(reply);
                                    }
                                }

                                @Override
                                public void onClosed(@NonNull CameraDevice camera) {
                                    if (eventSink != null) {
                                        Map<String, String> event = new HashMap<>();
                                        event.put("eventType", "cameraClosing");
                                        eventSink.success(event);
                                    }
                                    super.onClosed(camera);
                                }

                                @Override
                                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                                    cameraDevice.close();
                                    Camera.this.cameraDevice = null;
                                    sendErrorEvent("The camera was disconnected.");
                                }

                                @Override
                                public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
                                    cameraDevice.close();
                                    Camera.this.cameraDevice = null;
                                    String errorDescription;
                                    switch (errorCode) {
                                        case ERROR_CAMERA_IN_USE:
                                            errorDescription = "The camera device is in use already.";
                                            break;
                                        case ERROR_MAX_CAMERAS_IN_USE:
                                            errorDescription = "Max cameras in use";
                                            break;
                                        case ERROR_CAMERA_DISABLED:
                                            errorDescription =
                                                    "The camera device could not be opened due to a device policy.";
                                            break;
                                        case ERROR_CAMERA_DEVICE:
                                            errorDescription = "The camera device has encountered a fatal error";
                                            break;
                                        case ERROR_CAMERA_SERVICE:
                                            errorDescription = "The camera service has encountered a fatal error.";
                                            break;
                                        default:
                                            errorDescription = "Unknown camera error";
                                    }
                                    sendErrorEvent(errorDescription);
                                }
                            },
                            null);
                } catch (CameraAccessException e) {
                    if (result != null) result.error("cameraAccess", e.getMessage(), null);
                }
            }
        }

        private void writeToFile(ByteBuffer buffer, File file) throws IOException {
            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                while (0 < buffer.remaining()) {
                    outputStream.getChannel().write(buffer);
                }
            }
        }

        private void takePicture(String filePath, @NonNull final Result result) {
            final File file = new File(filePath);

            if (file.exists()) {
                result.error(
                        "fileExists",
                        "File at path '" + filePath + "' already exists. Cannot overwrite.",
                        null);
                return;
            }

            pictureImageReader.setOnImageAvailableListener(
                    new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(ImageReader reader) {
                            try (Image image = reader.acquireLatestImage()) {
                                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                writeToFile(buffer, file);
                                result.success(null);
                            } catch (IOException e) {
                                result.error("IOError", "Failed saving image", null);
                            }
                        }
                    },
                    null);

            try {
                final CaptureRequest.Builder captureBuilder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                captureBuilder.addTarget(pictureImageReader.getSurface());
//                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());
                int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                if (isFrontFacing) rotation = -rotation;
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

                cameraCaptureSession.capture(
                        captureBuilder.build(),
                        new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureFailed(
                                    @NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request,
                                    @NonNull CaptureFailure failure) {
                                String reason;
                                switch (failure.getReason()) {
                                    case CaptureFailure.REASON_ERROR:
                                        reason = "An error happened in the framework";
                                        break;
                                    case CaptureFailure.REASON_FLUSHED:
                                        reason = "The capture has failed due to an abortCaptures() call";
                                        break;
                                    default:
                                        reason = "Unknown reason";
                                }
                                result.error("captureFailure", reason, null);
                            }
                        },
                        null);
            } catch (CameraAccessException e) {
                result.error("cameraAccess", e.getMessage(), null);
            }
        }

        private void startVideoRecording(String filePath, @NonNull final Result result) {
            if (cameraDevice == null) {
                result.error("configureFailed", "Camera was closed during configuration.", null);
                return;
            }
            if (new File(filePath).exists()) {
                result.error(
                        "fileExists",
                        "File at path '" + filePath + "' already exists. Cannot overwrite.",
                        null);
                return;
            }
            try {
                closeCaptureSession();
                prepareMediaRecorder(filePath);

                recordingVideo = true;

                SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
                surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

                List<Surface> surfaces = new ArrayList<>();

                previewSurface = new Surface(surfaceTexture);
                surfaces.add(previewSurface);
                captureRequestBuilder.addTarget(previewSurface);

                Surface recorderSurface = mediaRecorder.getSurface();
                surfaces.add(recorderSurface);
                captureRequestBuilder.addTarget(recorderSurface);

                cameraDevice.createCaptureSession(
                        surfaces,
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                                try {
                                    if (cameraDevice == null) {
                                        result.error("configureFailed", "Camera was closed during configuration", null);
                                        return;
                                    }
                                    Camera.this.cameraCaptureSession = cameraCaptureSession;
                                    captureRequestBuilder.set(
                                            CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                    cameraCaptureSession.setRepeatingRequest(
                                            captureRequestBuilder.build(), null, null);
                                    mediaRecorder.start();
                                    result.success(null);
                                } catch (CameraAccessException
                                        | IllegalStateException
                                        | IllegalArgumentException e) {
                                    result.error("cameraException", e.getMessage(), null);
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                                result.error("configureFailed", "Failed to configure camera session", null);
                            }
                        },
                        null);
            } catch (CameraAccessException | IOException e) {
                result.error("videoRecordingFailed", e.getMessage(), null);
            }
        }

        private void stopVideoRecording(@NonNull final Result result) {
            if (!recordingVideo) {
                result.success(null);
                return;
            }

            try {
                recordingVideo = false;
                mediaRecorder.stop();
                mediaRecorder.reset();
                startPreview();
                result.success(null);
            } catch (CameraAccessException | IllegalStateException e) {
                result.error("videoRecordingFailed", e.getMessage(), null);
            }
        }

        private void startPreview() throws CameraAccessException {
            closeCaptureSession();

            SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            List<Surface> surfaces = new ArrayList<>();

            previewSurface = new Surface(surfaceTexture);
            surfaces.add(previewSurface);
            if (!android.os.Build.MANUFACTURER.equalsIgnoreCase(GOOGLE_DEVICE)) {
                if (android.os.Build.MANUFACTURER.equalsIgnoreCase(HUAWEI_DEVICE)) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                } else if (android.os.Build.MANUFACTURER.equalsIgnoreCase(SAMSUNG_DEVICE)) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                } else {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                }
            }

            captureRequestBuilder.addTarget(previewSurface);
            surfaces.add(pictureImageReader.getSurface());

            printLog("Preview: Configured camera capture session");
            cameraDevice.createCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            printLog("Preview: Configured camera capture session");
                            if (cameraDevice == null) {
                                sendErrorEvent("The camera was closed during configuration.");
                                return;
                            }
                            try {
                                cameraCaptureSession = session;
                                if (android.os.Build.MANUFACTURER.equalsIgnoreCase(GOOGLE_DEVICE)) {
                                    captureRequestBuilder.set(
                                            CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                                }
                                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                                sendErrorEvent(e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            sendErrorEvent("Failed to configure the camera for preview.");
                        }
                    },
                    null);
        }

        private void turnFlashLight(boolean turnOn) {
            try {
                cameraCaptureSession.stopRepeating();
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        turnOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);

                // notify for flutter layer
                isFlashOn = turnOn;
                channel.invokeMethod("camera.torchMode", isFlashOn);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void updateRotation() {
            try {
                cameraCaptureSession.stopRepeating();
                // Orientation
                int r = activity.getWindowManager().getDefaultDisplay().getRotation();
                if (isFrontFacing) r = -r;
                captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(r));
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void startPreviewWithImageStream() throws CameraAccessException {
            closeCaptureSession();

            SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            captureRequestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            if (isFrontFacing) rotation = -rotation;
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            List<Surface> surfaces = new ArrayList<>();

            previewSurface = new Surface(surfaceTexture);
            surfaces.add(previewSurface);
            if (!android.os.Build.MANUFACTURER.equalsIgnoreCase(GOOGLE_DEVICE)) {
                if (android.os.Build.MANUFACTURER.equalsIgnoreCase(HUAWEI_DEVICE)) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                } else if (android.os.Build.MANUFACTURER.equalsIgnoreCase(SAMSUNG_DEVICE)) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                } else {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                }
            }
            captureRequestBuilder.addTarget(previewSurface);

            surfaces.add(imageStreamReader.getSurface());
            captureRequestBuilder.addTarget(imageStreamReader.getSurface());

            printLog("ImageStream: Start create capture session");
            cameraDevice.createCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            printLog("ImageStream: Configured camera capture session");
                            if (cameraDevice == null) {
                                sendErrorEvent("The camera was closed during configuration.");
                                return;
                            }
                            try {
                                cameraCaptureSession = session;
                                if (android.os.Build.MANUFACTURER.equalsIgnoreCase(GOOGLE_DEVICE)) {
                                    captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                }
//                                 // add flash auto for test, need to more research for auto flash more. Currently, it's work only on capture request
//                                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
//                                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
//                                captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
//                                captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
//                                captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//                                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                        isFlashOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

                                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);

                                // send flash event
                                channel.invokeMethod("camera.torchMode", isFlashOn);
                            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                                sendErrorEvent(e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            sendErrorEvent("Failed to configure the camera for streaming images.");
                        }
                    },
                    null);

            registerImageStreamEventChannel();
        }

        private void registerImageStreamEventChannel() {
            final EventChannel imageStreamChannel =
                    new EventChannel(registrar.messenger(), "plugins.flutter.io/camera/imageStream");

            imageStreamChannel.setStreamHandler(
                    new EventChannel.StreamHandler() {
                        @Override
                        public void onListen(Object o, EventChannel.EventSink eventSink) {
                            setImageStreamImageAvailableListener(eventSink);
                        }

                        @Override
                        public void onCancel(Object o) {
                            imageStreamReader.setOnImageAvailableListener(null, null);
                        }
                    });
        }

        private void setImageStreamImageAvailableListener(final EventChannel.EventSink eventSink) {
            imageStreamReader.setOnImageAvailableListener(
                    new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(final ImageReader reader) {
                            Image image = reader.acquireLatestImage();
                            if (image == null) return;
                            getBrightnessValue(image);
                            List<Map<String, Object>> planes = new ArrayList<>();
                            ByteBuffer buffers[] = new ByteBuffer[image.getPlanes().length];
                            int i = 0;
                            for (Image.Plane plane : image.getPlanes()) {
                                ByteBuffer buffer = plane.getBuffer();
                                buffers[i++] = bufferClone(buffer);

                                byte[] bytes = new byte[buffer.remaining()];
                                buffer.get(bytes, 0, bytes.length);

                                Map<String, Object> planeBuffer = new HashMap<>();
                                planeBuffer.put("bytesPerRow", plane.getRowStride());
                                planeBuffer.put("bytesPerPixel", plane.getPixelStride());
                                planeBuffer.put("bytes", bytes);

                                planes.add(planeBuffer);
                            }

                            Map<String, Object> imageBuffer = new HashMap<>();
                            imageBuffer.put("width", image.getWidth());
                            imageBuffer.put("height", image.getHeight());
                            imageBuffer.put("format", image.getFormat());
                            imageBuffer.put("planes", planes);

                            eventSink.success(imageBuffer);

//                            // read stable status use native-lib of Giang san
                            if (isFrameMode) {
                                handleStableStateFrameByFrame(image.getWidth(), image.getHeight(), buffers);
                            }

                            // force close img object
                            image.close();
                        }
                    },
                    null);
        }

        private void getBrightnessValue(Image image) {
            try {
                ByteBuffer imageBuffer = image.getPlanes()[0].getBuffer();
                ByteBuffer buffer = ByteBuffer.allocate(imageBuffer.capacity());
                buffer.put(imageBuffer);
                buffer.compact();

                Mat inputMat = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8U);
                inputMat.put(0, 0, buffer.array());
                /**
                 * In the case of color images, the decoded images will have the channels stored in B G R order.
                 */
                Mat brg = Imgcodecs.imdecode(inputMat, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
                /**
                 * RGB color -> HSV color
                 */
                Mat hsv = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC3);
                List<Mat> hsv_channel = new ArrayList<>();
                Imgproc.cvtColor(brg, hsv, Imgproc.COLOR_BGR2HSV);
                /**
                 * 0: Hue channel
                 * 1: Saturation channel
                 * 2: Value/ Brightness channel
                 */
                Core.split(hsv, hsv_channel);
                /**
                 * Get total of pixel and calculate averageLuminance*/
                Scalar sumElems = Core.sumElems(hsv_channel.get(2));
                int averageLuminance = (int) (sumElems.val[0] / (hsv_channel.get(2).rows() * hsv_channel.get(2).cols()));
//                Log.d("CAM", "average: " + averageLuminance);
                int r = averageLuminance < 50 ? -1 : (averageLuminance >= 216 ? 1 : 0);
                channel.invokeMethod("camera.brightnessLevel", r);
                //            // Save mat to image
//            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
//            File path = new File(Environment.getExternalStorageDirectory() + "/Images/");
//            path.mkdirs();
//            File file = new File(path, "image" + timeStamp + "_orginal.png");
//            String filename = file.toString();
//            Imgcodecs.imwrite(filename, rgb);

            } catch (Exception e) {
                e.printStackTrace();
            }

        }

        ByteBuffer bufferClone(ByteBuffer original) {
            ByteBuffer clone = ByteBuffer.allocate(original.capacity());
            original.rewind();//copy from the beginning
            clone.put(original);
            original.rewind();
            clone.flip();
            return clone;
        }

        private boolean isDiffFrame(int w, int h, byte[] img1, byte[] img2) {

            try {
                Mat input1 = new Mat(h + h / 2, w, CvType.CV_8UC1);
                Mat bgra1 = new Mat(h + h / 2, w, CvType.CV_8UC4);
                input1.put(0, 0, img1);
                Imgproc.cvtColor(input1, bgra1, Imgproc.COLOR_YUV420sp2BGRA);
                if (IS_NEXUS_5X) {
                    Core.flip(bgra1.t(), bgra1, 0);
                } else {
                    Core.flip(bgra1.t(), bgra1, 1);
                }


                Mat input2 = new Mat(h + h / 2, w, CvType.CV_8UC1);
                Mat bgra2 = new Mat(h + h / 2, w, CvType.CV_8UC4);
                input2.put(0, 0, img2);
                Imgproc.cvtColor(input2, bgra2, Imgproc.COLOR_YUV420sp2BGRA);
                if (IS_NEXUS_5X) {
                    Core.flip(bgra2.t(), bgra2, 0);
                } else {
                    Core.flip(bgra2.t(), bgra2, 1);
                }

                return MovingDetectorJNI.newInstance().isDiff(bgra1, bgra2) == 1.0;
            } catch (Exception e) {
                e.printStackTrace();
            }
            return false;
        }

        private void fastFrame(Image image) {
            try {
                int w = image.getWidth();
                int h = image.getHeight();

                ByteBuffer bufferY = image.getPlanes()[0].getBuffer();
                ByteBuffer bufferU = image.getPlanes()[1].getBuffer();
                ByteBuffer bufferV = image.getPlanes()[2].getBuffer();
                ByteBuffer buffer = ByteBuffer.allocateDirect(bufferY.capacity() + bufferU.capacity() + bufferV.capacity());
                buffer.put(bufferY);
                buffer.put(bufferU);
                buffer.put(bufferV);
                buffer.compact();

                byte[] bytes = buffer.array();
                Mat input = new Mat(h + h / 2, w, CvType.CV_8UC1);
                Mat bgra = new Mat(h + h / 2, w, CvType.CV_8UC4);
                input.put(0, 0, bytes);
                Imgproc.cvtColor(input, bgra, Imgproc.COLOR_YUV420sp2BGRA);
                if (IS_NEXUS_5X) {
                    Core.flip(bgra.t(), bgra, 0);
                } else {
                    Core.flip(bgra.t(), bgra, 1);
                }

//                Log.d("CardDetector", "frameUpdated >> bytesToMat");
//                Log.d(TAG, "WH: " + image.getWidth() + "x" + image.getHeight());

//                Mat mat = CardDetectorJNI.newInstance().debug(bgra.nativeObj);
//                File path = new File(Environment.getExternalStorageDirectory() + "/Images/");
//                path.mkdirs();
//                File file = new File(path, "image" + System.currentTimeMillis() + "_converted.png");
//                String filename = file.toString();
//                Imgcodecs.imwrite(filename, mat);

                float stableValue = MovingDetectorJNI.newInstance().isStable(bgra);
                channel.invokeMethod("camera.stableDetected", stableValue == 1.0);

//                bytes = null;
//                bgra = new Mat();
//                input = new Mat();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        int counter = 3;

        /**
         * Read camera state and detect is stable
         *
         * @param w       of image
         * @param h       of image
         * @param buffers will contents 3 elements, as Y,U,V match to position 0,1,2
         */
        private void handleStableStateFrameByFrame(int w, int h, ByteBuffer[] buffers) {
            if (counter > 0) {
                counter--;
                return;
            }
            counter = 3;
            try {
                ByteBuffer buffer = ByteBuffer.allocate(buffers[0].capacity() + buffers[1].capacity() + buffers[2].capacity());
                buffer.put(buffers[0]);
                buffer.put(buffers[1]);
                buffer.put(buffers[2]);
                buffer.compact();

                byte[] bytes = buffer.array();
                Mat input = new Mat(h + h / 2, w, CvType.CV_8UC1);
                Mat bgra = new Mat(h + h / 2, w, CvType.CV_8UC4);
                input.put(0, 0, bytes);
                Imgproc.cvtColor(input, bgra, Imgproc.COLOR_YUV420sp2BGRA);
                if (IS_NEXUS_5X) {
                    Core.flip(bgra.t(), bgra, 0);
                } else {
                    Core.flip(bgra.t(), bgra, 1);
                }

//                Log.d("CardDetector", "frameUpdated >> bytesToMat");
//                Log.d(TAG, "WH: " + image.getWidth() + "x" + image.getHeight());

//                Mat mat = CardDetectorJNI.newInstance().debug(bgra.nativeObj);
//                File path = new File(Environment.getExternalStorageDirectory() + "/Images/");
//                path.mkdirs();
//                File file = new File(path, "image" + System.currentTimeMillis() + "_converted.png");
//                String filename = file.toString();
//                Imgcodecs.imwrite(filename, mat);

                float stableValue = MovingDetectorJNI.newInstance().isStable(bgra);
                channel.invokeMethod("camera.stableDetected", stableValue == 1.0);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void sendErrorEvent(String errorDescription) {
            if (eventSink != null) {
                Map<String, String> event = new HashMap<>();
                event.put("eventType", "error");
                event.put("errorDescription", errorDescription);
                eventSink.success(event);
            }
        }

        private void closeCaptureSession() {
            printLog("Request close capture session");
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
        }

        private void close() {
            closeCaptureSession();

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (pictureImageReader != null) {
                pictureImageReader.close();
                pictureImageReader = null;
            }
            if (imageStreamReader != null) {
                imageStreamReader.close();
                imageStreamReader = null;
            }
            if (mediaRecorder != null) {
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        }

        private void dispose() {
            close();
            textureEntry.release();
        }

        private int getMediaOrientation() {
            final int sensorOrientationOffset =
                    (currentOrientation == ORIENTATION_UNKNOWN)
                            ? 0
                            : (isFrontFacing) ? -currentOrientation : currentOrientation;
            final int finalOrientation = (sensorOrientationOffset + sensorOrientation + 360) % 360;
            // TODO: - Review it
            if (finalOrientation >= 225 && finalOrientation <= 270) {
                return 90; // Right
            }
            return finalOrientation;
        }

        /**
         * Retrieves the JPEG orientation from the specified screen rotation.
         *
         * @param rotation The screen rotation.
         * @return The JPEG orientation (one of 0, 90, 270, and 360)
         */
        private int getOrientation(int rotation) {
            // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
            // We have to take that into account and rotate JPEG properly.
            // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
            // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
//            printLog("Rotation " + rotation);
//            printLog("ORIENTATIONs " + ORIENTATIONS.get(rotation));
//            printLog("sensor Orientation " + sensorOrientation);
//            printLog("Change stat rotate " + startRotation + " -> " + ((ORIENTATIONS.get(rotation) + sensorOrientation + startRotation) % 360));
            return (ORIENTATIONS.get(rotation) + sensorOrientation + startRotation) % 360;
        }
    }

    static void printLog(String message) {
        Log.d("CAMERA2", message);
    }
}
