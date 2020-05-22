package io.flutter.plugins.camera;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;

import androidx.annotation.NonNull;

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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CameraPlugin implements MethodCallHandler {
    private static final int CAMERA_REQUEST_ID = 513469796;
    private static final int MAX_IMAGE_WIDTH_CAPTURE = 1024;
    private static final String TAG = "CameraPlugin";
    private final static boolean IS_NEXUS_5X = Build.MODEL.equalsIgnoreCase("Nexus 5X");
    private final String GOOGLE_DEVICE = "Google";

    private static MethodChannel channel;
    private static CameraManager cameraManager;
    private final FlutterView view;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private final File mFile;
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

    // Keep brightness current
    private int currentBrightness = 0; // normal brightness is default
    private int tempBrightness = 0;
    private int brightnessThreshold = 0;
    static final int THRESHOLD_MAX = 5;

    // Keep result for delay methods
    private Result currentResult;

    private boolean requestBrightness = false;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    enum CameraState {
        PREVIEW,
        WAITING_LOCK,
        WAITING_PRECAPTURE,
        WAITING_NON_PRECAPTURE,
        TAKEN
    }

    private CameraState cameraState = CameraState.PREVIEW;

    private CameraPlugin(Registrar registrar, FlutterView view) {
//        MovingDetectorJNI.newInstance();
        this.registrar = registrar;
        this.view = view;
        this.activity = registrar.activity();

        Log.d("IS_HUAWEI_BRAND:", Build.MANUFACTURER + "");

        orientationEventListener =
                new OrientationEventListener(registrar.activity().getApplicationContext()) {
                    @Override
                    public void onOrientationChanged(int i) {
                        if (i == ORIENTATION_UNKNOWN) {
                            i = 0; // Set to portrait when unknown orientation (table paralel case)
//                            return;
                        }
                        // Convert the raw deg angle to the nearest multiple of 90.
                        int currentOrientation = ((int) Math.round(i / 90.0) * 90) % 360;
                        if (startRotation != currentOrientation) {
                            startRotation = currentOrientation;
                            if (camera != null) {
                                camera.updateRotation();
                            }
                        }
                    }
                };

        registrar.addRequestPermissionsResultListener(new CameraRequestPermissionsListener());
        mFile = new File(activity.getExternalFilesDir(null), "pic.jpg");


        // Register light sensor
//        registerLightSensor(registrar.activity());
//        registerSensorLight();
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
//    private boolean autoFlashLight = false;

    // Implement a sensorLightListener to receive updates
//    SensorEventListener sensorLightListener = new SensorEventListener() {
//        @Override
//        public void onSensorChanged(SensorEvent event) {
//            Log.d(TAG, "Light " + event.values[0]);
//            double b = event.values[0];
//            int r = b < 20 ? -1 : (b > 200 ? 1 : 0);
//            channel.invokeMethod("camera.brightnessLevel", r);
//             Auto check flash
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
//        }
//
//        @Override
//        public void onAccuracyChanged(Sensor sensor, int i) {
//        }
//    };

//    public void registerLightSensor(Activity activity) {
//        // Obtain references to the SensorManager and the Light Sensor
//        mSensorManager = (SensorManager) activity.getSystemService(SENSOR_SERVICE);
//        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
//    }

//    private void unregisterSensorLight() {
//        if (autoFlashLight) {
//            autoFlashLight = false; // turn off auto flash light
//            mSensorManager.unregisterListener(sensorLightListener);
//        }
//    }
//
//    private void registerSensorLight() {
//        if (autoFlashLight == false) {
//            autoFlashLight = true; // turn true auto flash light
//            mSensorManager.registerListener(sensorLightListener, mLightSensor, SensorManager.SENSOR_DELAY_UI);
//        }
//    }

    @Override
    public void onMethodCall(MethodCall call, final Result result) {
        currentResult = result;
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
                    if (currentResult != null) {
                        currentResult.success(cameras);
                        currentResult = null;
                    }
                } catch (Exception e) {
                    handleException(e);
                }
                break;
            case "initialize": {
                String cameraName = call.argument("cameraName");
                String resolutionPreset = call.argument("resolutionPreset");
                boolean enableAudio = call.argument("enableAudio");
                if (camera != null) {
                    camera.close();
                }
                camera = new Camera(cameraName, resolutionPreset, enableAudio);
                orientationEventListener.enable();
                break;
            }
            case "takePicture": {
//                camera.takePicture();
                camera.captureStillImage();
                break;
            }
            case "requestFocus": {
                camera.requestFocus();
                break;
            }
            case "prepareForVideoRecording": {
                // This optimization is not required for Android.
                if (currentResult != null) {
                    currentResult.success(null);
                    currentResult = null;
                }
                break;
            }
            case "startImageStream": {
                // Register sensor light callback when on image stream to keep reverse state before
//                if (autoFlashLight) {
//                    mSensorManager.registerListener(sensorLightListener, mLightSensor, SensorManager.SENSOR_DELAY_UI);
//                }
                // Start image stream
                try {
                    camera.startPreviewWithImageStream();
                } catch (Exception e) {
                    handleException(e);
                }
                break;
            }
            case "stopImageStream": {
                // Remove sensor light callback when off image stream
//                if (autoFlashLight) {
//                    mSensorManager.unregisterListener(sensorLightListener);
//                }
                // Stop image stream
                try {
//                    // complete start preview process
//                    if (currentResult != null) {
//                        currentResult.success(true);
//                        currentResult = null;
//                    }
                    camera.startPreview();
                } catch (Exception e) {
                    handleException(e);
                }
                break;
            }
            case "dispose": {
                if (camera != null) {
                    camera.dispose();
                }
                orientationEventListener.disable();
                if (currentResult != null) {
                    currentResult.success(true);
                    currentResult = null;
                }
                break;
            }
            case "compareFrame": {
//                byte[] bytesOfImg1 = call.argument("bytesOfImg1");
//                byte[] bytesOfImg2 = call.argument("bytesOfImg2");
//                int w = call.argument("width");
//                int h = call.argument("height");
//                boolean isDiff = camera.isDiffFrame(w, h, bytesOfImg1, bytesOfImg2);
//                if (currentResult != null) {
//                    currentResult.success(isDiff);
//                    currentResult = null;
//                }
                break;
            }
            case "setFrameModeEnable": {
                isFrameMode = call.arguments != null ? call.arguments.toString().equals("YES") : false;
                if (currentResult != null) {
                    currentResult.success(true);
                    currentResult = null;
                }
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
                        if (currentResult != null) {
                            currentResult.success(true);
                            currentResult = null;
                        }
                    } else {
                        if (currentResult != null) {
                            currentResult.error("Camera is NULL", "", "");
                            currentResult = null;
                        }
                    }
                } catch (Exception e) {
                    handleException(e);
                }
                break;
            }

            case "getBrightness": {
                requestBrightness = true;
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
                if (currentResult != null) {
                    currentResult.notImplemented();
                    currentResult = null;
                }
                break;
        }
    }

    // We move catching CameraAccessException out of onMethodCall because it causes a crash
    // on plugin registration for sdks incompatible with Camera2 (< 21). We want this plugin to
    // to be able to compile with <21 sdks for apps that want the camera and support earlier version.
    @SuppressWarnings("ConstantConditions")
    private void handleException(Exception exception) {
        if (exception instanceof CameraAccessException && currentResult != null) {
            currentResult.error("CameraAccess", exception.getMessage(), null);
            currentResult = null;
        }

        throw (RuntimeException) exception;
    }

    private static class CompareSizesByHeight implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow.
            return lhs.getHeight() - rhs.getHeight();
        }
    }

    private static class CompareSizesByWidth implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow.
            return lhs.getWidth() - rhs.getWidth();
        }
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
        private CaptureRequest.Builder previewRequestBuilder;
        private Size videoSize;
        private MediaRecorder mediaRecorder;
        private boolean recordingVideo;
        private boolean enableAudio;
        private boolean enableFlash;
        private Permission permission;

        Camera(
                final String cameraName,
                final String resolutionPreset,
                final boolean enableAudio) {

            this.cameraName = cameraName;
            permission = new Permission(registrar);
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
                isFrontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT;
                computeBestCaptureSize(streamConfigurationMap);
                computeBestPreviewAndRecordingSize(streamConfigurationMap, minHeight, captureSize);
                if (cameraPermissionContinuation != null && currentResult != null) {
                    currentResult.error("cameraPermission", "Camera permission request ongoing", null);
                    currentResult = null;
                }
                cameraPermissionContinuation =
                        new Runnable() {
                            @Override
                            public void run() {
                                cameraPermissionContinuation = null;
                                if (!permission.hasCamera() && currentResult != null) {
                                    currentResult.error(
                                            " cameraPermission", "MediaRecorderCamera permission not granted", null);
                                    currentResult = null;
                                    return;
                                }
                                if (enableAudio && !permission.hasAudio() && currentResult != null) {
                                    currentResult.error(
                                            "cameraPermission", "MediaRecorderAudio permission not granted", null);
                                    currentResult = null;
                                    return;
                                }
                                open();
                            }
                        };
                if (permission.hasCamera() && (!enableAudio || permission.hasAudio())) {
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
                if (currentResult != null) {
                    currentResult.error("CameraAccess", e.getMessage(), null);
                    currentResult = null;
                }
            } catch (IllegalArgumentException e) {
                if (currentResult != null) {
                    currentResult.error("IllegalArgumentException", e.getMessage(), null);
                    currentResult = null;
                }
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


        private void computeBestPreviewAndRecordingSize(StreamConfigurationMap streamConfigurationMap, int minHeight, Size captureSize) {
//            Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);
            List<Size> sizeList = Arrays.asList(streamConfigurationMap.getOutputSizes(SurfaceTexture.class));

            Collections.sort(sizeList, new CompareSizesByHeight());
            Size bestSize = null;
            for (int i = 0; i < sizeList.size(); ++i) {
                Size size = sizeList.get(i);
                if (size.getHeight() >= minHeight) {
                    bestSize = size;
                    break;
                }
            }
            if (bestSize == null) {
                bestSize = sizeList.get(sizeList.size() - 1);
            }



            if (bestSize != null) {
                Log.d(TAG, "WxH = " + bestSize.getWidth() + " x " + bestSize.getHeight());
            } else {
                Log.d(TAG, "Loi tai Phuc!");
            }
//            // Preview size and video size should not be greater than screen resolution or 1080.
//            Point screenResolution = new Point();
//
//            final Activity activity = registrar.activity();
//            if (activity == null) {
//                throw new IllegalStateException("No activity available!");
//            }
//
//            Display display = activity.getWindowManager().getDefaultDisplay();
//            display.getRealSize(screenResolution);
//
//            final boolean swapWH = getMediaOrientation() % 180 == 90;
//            int screenWidth = swapWH ? screenResolution.y : screenResolution.x;
//            int screenHeight = swapWH ? screenResolution.x : screenResolution.y;
//
//            List<Size> goodEnough = new ArrayList<>();
//            for (Size s : sizes) {
//                if (minHeight <= s.getHeight()
//                        && s.getWidth() <= screenWidth
//                        && s.getHeight() <= screenHeight
//                        && s.getHeight() <= 1080) {
//                    goodEnough.add(s);
//                }
//            }
//
//            Collections.sort(goodEnough, new CompareSizesByArea());

//            if (goodEnough.isEmpty()) {
//                previewSize = sizes[0];
//                videoSize = sizes[0];
//            } else {
//            if (bestSize != null) {
                previewSize = bestSize;
                videoSize = bestSize;
//            } else {
//                float captureSizeRatio = (float) captureSize.getWidth() / captureSize.getHeight();
//                previewSize = goodEnough.get(0);
//                for (Size s : goodEnough) {
//                    if ((float) s.getWidth() / s.getHeight() == captureSizeRatio) {
//                        previewSize = s;
//                        break;
//                    }
//                }
//
//                Collections.reverse(goodEnough);
//                videoSize = goodEnough.get(0);
//                for (Size s : goodEnough) {
//                    if ((float) s.getWidth() / s.getHeight() == captureSizeRatio) {
//                        videoSize = s;
//                        break;
//                    }
//                }
//            }
        }

        private void computeBestCaptureSize(StreamConfigurationMap streamConfigurationMap) {
            // For still image captures, we use the largest available size.
            List<Size> sizeList = Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.JPEG));
            Collections.sort(sizeList, new CompareSizesByWidth());
            Size bestSize = null;
            for (int i = 0; i < sizeList.size(); ++i) {
                Size size = sizeList.get(i);
                if (size.getWidth() >= MAX_IMAGE_WIDTH_CAPTURE) {
                    bestSize = size;
                    break;
                }
            }
            if (bestSize == null) {
                bestSize = sizeList.get(sizeList.size() - 1);
            }
            captureSize = bestSize;
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

        private void open() {
            if (!permission.hasCamera()) {
                if (currentResult != null) {
                    currentResult.error("cameraPermission", "Camera permission not granted", null);
                    currentResult = null;
                }

            } else {
                try {
                    pictureImageReader =
                            ImageReader.newInstance(
                                    captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);
                    pictureImageReader.setOnImageAvailableListener(pictureListener, null);

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

                                    if (currentResult != null) {
                                        Map<String, Object> reply = new HashMap<>();
                                        reply.put("textureId", textureEntry.id());
                                        reply.put("previewWidth", previewSize.getWidth());
                                        reply.put("previewHeight", previewSize.getHeight());
                                        currentResult.success(reply);
                                        currentResult = null;
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
                    if (currentResult != null) {
                        currentResult.error("cameraAccess", e.getMessage(), null);
                        currentResult = null;
                    }
                }
            }
        }

        private void requestFocus() {
            if (needFlash()) {
                printLog("takePictureWithFlash");
                takePictureWithFlash();
            } else {
                printLog("lockFocus");
                lockFocus();
            }
        }

        /**
         * Lock the focus as the first step for a still image capture.
         */
        private void lockFocus() {
            try {
                // This is how to tell the camera to lock focus.
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                // Tell #mCaptureCallback to wait for the lock.
                cameraState = CameraState.WAITING_LOCK;
                cameraCaptureSession.capture(previewRequestBuilder.build(), previewCallBackBack, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
                if (currentResult != null) {
                    currentResult.error("cameraAccess", e.getMessage(), null);
                    currentResult = null;
                }
            }
        }

        private void takePictureWithFlash() {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
            try {
                cameraState = CameraState.WAITING_NON_PRECAPTURE;
                cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), previewCallBackBack,
                        null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

        }

        /**
         * Unlock the focus. This method should be called when still image capture sequence is
         * finished.
         */
        private void unlockFocus() {
            if (cameraCaptureSession == null || previewRequestBuilder == null) return;
            try {
                // Reset the auto-focus trigger
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                cameraCaptureSession.capture(previewRequestBuilder.build(), previewCallBackBack, null);
                // After this, the camera will go back to the normal state of preview.
                cameraState = CameraState.PREVIEW;
                cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), previewCallBackBack,
                        null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        /**
         * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
         */
        private CameraCaptureSession.CaptureCallback previewCallBackBack = new CameraCaptureSession.CaptureCallback() {
            private void process(CaptureResult result) {
                printLog("Camera Step:" + cameraState);
                printLog("enableFlash " + enableFlash);
                switch (cameraState) {
                    case WAITING_LOCK: {
                        Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                        if (afState == null) {
                            runPreCaptureSequence();
                        } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                            // CONTROL_AE_STATE can be null on some devices
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                cameraState = CameraState.TAKEN;
                                if (currentResult != null) {
                                    currentResult.success(true);
                                    currentResult = null;
                                }
                            } else {
                                runPreCaptureSequence();
                            }
                        }
                        break;
                    }
                    case WAITING_PRECAPTURE: {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        printLog("WAITING_PRECAPTURE " + aeState);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                            cameraState = CameraState.WAITING_NON_PRECAPTURE;
                        } else {
                            cameraState = CameraState.WAITING_NON_PRECAPTURE;
                        }
                        break;
                    }
                    case WAITING_NON_PRECAPTURE: {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                            cameraState = CameraState.TAKEN;
                            takePictureAfterPrecapture();
                        }
                        break;
                    }
                }
            }

            @Override
            public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull CaptureResult partialResult) {
//                process(partialResult);
            }

            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                           @NonNull CaptureRequest request,
                                           @NonNull TotalCaptureResult result) {

                process(result);
            }

            @Override
            public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                super.onCaptureFailed(session, request, failure);
            }
        };

        private void takePictureAfterPrecapture() {
            if (currentResult != null) {
                currentResult.success(true);
                currentResult = null;
            }
        }

        /**
         * Run the precapture sequence for capturing a still image. This method should be called when
         * we get a response in {@link #previewCallBackBack}.
         */
        private void runPreCaptureSequence() {
            try {
                // This is how to tell the camera to trigger.
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                // Tell #mCaptureCallback to wait for the precapture sequence to be set.
                cameraState = CameraState.WAITING_PRECAPTURE;
                cameraCaptureSession.capture(previewRequestBuilder.build(), previewCallBackBack,
                        null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        ImageReader.OnImageAvailableListener pictureListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                final Image image = reader.acquireNextImage();
                try {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte jpegBytes[] = new byte[buffer.remaining()];
                    buffer.get(jpegBytes);
//                    new Handler().post(new ImageSaver(jpegBytes,mFile));
                    final Bitmap imageBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
                    final String base64 = getBase64Image(imageBitmap);
                    if (currentResult != null) {
                        currentResult.success(new HashMap() {{
                            put("base64String", base64);
                            put("width", image.getWidth());
                            put("height", image.getHeight());
                        }});
                        currentResult = null;
                    }
                    unlockFocus();

                } catch (Exception e) {
                    if (currentResult != null) {
                        currentResult.error("IOError", "Convert image to base64 fail", null);
                        currentResult = null;
                    }
                } finally {
                    image.close();
                    unlockFocus();
                    if (camera != null) {
                        camera.dispose();
                    }
                }
            }
        };

        private boolean needFlash() {
            return currentBrightness == -1 || isFlashOn;
        }

        private void captureStillImage() {
            try {
                final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                captureBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
                if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    // unclear why we wouldn't want to request ZSL
                    // this is also required to enable HDR+ on Google Pixel devices when using Camera2:
                    // https://opensource.google.com/projects/pixelvisualcorecamera
                    captureBuilder.set(CaptureRequest.CONTROL_ENABLE_ZSL, true);
                }
                if (needFlash()) {
                    //Set AEmode
                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                    captureBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                }

                //setFocusMode
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);

                if (needFlash()) {
                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                    captureBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                }

                // Orientation
                int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                if (isFrontFacing) rotation = -rotation;
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));
                captureBuilder.addTarget(pictureImageReader.getSurface());
                final CameraCaptureSession.CaptureCallback captureCallback
                        = new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                        super.onCaptureFailed(session, request, failure);
                        String reason;
                        unlockFocus();
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
                        if (currentResult != null) {
                            currentResult.error("captureFailure", reason, null);
                            currentResult = null;
                        }
                        if (currentResult != null) {
                            currentResult.error("cameraAccess", failure.toString(), null);
                            currentResult = null;
                        }
                    }

                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                   @NonNull CaptureRequest request,
                                                   @NonNull TotalCaptureResult result) {
                        unlockFocus();
                    }
                };

                cameraCaptureSession.stopRepeating();
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            cameraCaptureSession.capture(
                                    captureBuilder.build(), captureCallback, null);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }
                }, 1000);

//                cameraCaptureSession.abortCaptures();
            } catch (CameraAccessException e) {
                if (currentResult != null) {
                    currentResult.error("cameraAccess", e.getMessage(), null);
                    currentResult = null;
                }
            }
        }

        private void startPreview() throws CameraAccessException {
//            closeCaptureSession();
            SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            // This is the output Surface we need to start preview.
            previewSurface = new Surface(surfaceTexture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
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
                                // complete start preview process
                                if (currentResult != null) {
                                    currentResult.success(true);
                                    currentResult = null;
                                }

                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//                                previewRequestBuilder.set(CaptureRequest.FLASH_MODE,
//                                        isFlashOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                                cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), previewCallBackBack, null);

                            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                                sendErrorEvent(e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            sendErrorEvent("Failed to configure the camera for preview.");
                        }
                    }, null);
        }

        private void turnFlashLight(boolean turnOn) {
            try {
//                cameraCaptureSession.stopRepeating();
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                previewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        turnOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), previewCallBackBack, null);

                // notify for flutter layer
                isFlashOn = turnOn;
                channel.invokeMethod("camera.torchMode", isFlashOn);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void updateRotation() {
            try {
                if (cameraCaptureSession != null && previewRequestBuilder != null) {
                    cameraCaptureSession.stopRepeating();
                    // Orientation
                    int r = activity.getWindowManager().getDefaultDisplay().getRotation();
                    if (isFrontFacing) r = -r;
                    previewRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(r));
                    cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void startPreviewWithImageStream() throws CameraAccessException {
            closeCaptureSession();
            SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            if (isFrontFacing) rotation = -rotation;
            previewRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            List<Surface> surfaces = new ArrayList<>();
            previewSurface = new Surface(surfaceTexture);
            surfaces.add(previewSurface);
            previewRequestBuilder.addTarget(previewSurface);

            surfaces.add(imageStreamReader.getSurface());
            previewRequestBuilder.addTarget(imageStreamReader.getSurface());

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
                                    previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                }
                                else {
                                    previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                }
                                previewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                        isFlashOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

                                cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null);

                                // send flash event
                                channel.invokeMethod("camera.torchMode", isFlashOn);

                                // complete init camera stream
                                if (currentResult != null) {
                                    currentResult.success(true);
                                    currentResult = null;
                                }
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
            final EventChannel imageStreamChannel = new EventChannel(registrar.messenger(), "plugins.flutter.io/camera/imageStream");
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

//        int c = 0;
        private void setImageStreamImageAvailableListener(final EventChannel.EventSink eventSink) {
            imageStreamReader.setOnImageAvailableListener(
                    new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(final ImageReader reader) {
                            Image image = reader.acquireLatestImage();
                            if (image == null) return;

                            if (requestBrightness) {
                                requestBrightness = false;
                                int brightness = parseBrightnessValue(image);
                                if (currentResult != null) {
                                    currentResult.success(brightness);
                                    currentResult = null;
                                }
                            }

//                            c++;
//                            if (c > 5) {
//                                c = 0;
//                                // Parse brightness session
//                                int brightness = parseBrightnessValue(image);
//                                if (brightness != -100) {
//                                    if (brightness == tempBrightness) {
//                                        brightnessThreshold++;
//                                    } else {
//                                        tempBrightness = brightness;
//                                        brightnessThreshold = 0;
//                                    }
//                                    if (brightnessThreshold > THRESHOLD_MAX) {
//                                        brightnessThreshold = 0;
//                                        // Notify in changed only
//                                        if (currentBrightness != tempBrightness) {
//                                            currentBrightness = tempBrightness;
//                                            channel.invokeMethod("camera.brightnessLevel", currentBrightness);
//                                            printLog("camera.brightnessLevel" + currentBrightness);
//                                        }
//                                    }
//                                }
//                            }

                            // Parse frame session
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
//                            if (isFrameMode) {
//                                handleStableStateFrameByFrame(image.getWidth(), image.getHeight(), buffers);
//                            }

                            // force close img object
                            image.close();
                        }
                    },
                    null);
        }

        /***
         * Parse brightness values from Image using openCv converter
         * @param image
         * @return 0 is normal, 1 is high and -1 is low brightness, -100 for exception
         */
        private int parseBrightnessValue(Image image) {
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
                 * BGR color -> HSV color
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
                return averageLuminance < 75 ? -1 : (averageLuminance >= 216 ? 1 : 0);

                // Save mat to image only for debugs
//            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
//            File path = new File(Environment.getExternalStorageDirectory() + "/Images/");
//            path.mkdirs();
//            File file = new File(path, "image" + timeStamp + "_orginal.png");
//            String filename = file.toString();
//            Imgcodecs.imwrite(filename, rgb);

            } catch (Exception e) {
                e.printStackTrace();
                // Skip this exception
            }
            return -100;
        }

        ByteBuffer bufferClone(ByteBuffer original) {
            ByteBuffer clone = ByteBuffer.allocate(original.capacity());
            original.rewind();//copy from the beginning
            clone.put(original);
            original.rewind();
            clone.flip();
            return clone;
        }

//        private boolean isDiffFrame(int w, int h, byte[] img1, byte[] img2) {
//
//            try {
//                Mat input1 = new Mat(h + h / 2, w, CvType.CV_8UC1);
//                Mat bgra1 = new Mat(h + h / 2, w, CvType.CV_8UC4);
//                input1.put(0, 0, img1);
//                Imgproc.cvtColor(input1, bgra1, Imgproc.COLOR_YUV420sp2BGRA);
//                if (IS_NEXUS_5X) {
//                    Core.flip(bgra1.t(), bgra1, 0);
//                } else {
//                    Core.flip(bgra1.t(), bgra1, 1);
//                }
//
//
//                Mat input2 = new Mat(h + h / 2, w, CvType.CV_8UC1);
//                Mat bgra2 = new Mat(h + h / 2, w, CvType.CV_8UC4);
//                input2.put(0, 0, img2);
//                Imgproc.cvtColor(input2, bgra2, Imgproc.COLOR_YUV420sp2BGRA);
//                if (IS_NEXUS_5X) {
//                    Core.flip(bgra2.t(), bgra2, 0);
//                } else {
//                    Core.flip(bgra2.t(), bgra2, 1);
//                }
//
//                return MovingDetectorJNI.newInstance().isDiff(bgra1, bgra2) == 1.0;
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//            return false;
//        }

//        private void fastFrame(Image image) {
//            try {
//                int w = image.getWidth();
//                int h = image.getHeight();
//
//                ByteBuffer bufferY = image.getPlanes()[0].getBuffer();
//                ByteBuffer bufferU = image.getPlanes()[1].getBuffer();
//                ByteBuffer bufferV = image.getPlanes()[2].getBuffer();
//                ByteBuffer buffer = ByteBuffer.allocateDirect(bufferY.capacity() + bufferU.capacity() + bufferV.capacity());
//                buffer.put(bufferY);
//                buffer.put(bufferU);
//                buffer.put(bufferV);
//                buffer.compact();
//
//                byte[] bytes = buffer.array();
//                Mat input = new Mat(h + h / 2, w, CvType.CV_8UC1);
//                Mat bgra = new Mat(h + h / 2, w, CvType.CV_8UC4);
//                input.put(0, 0, bytes);
//                Imgproc.cvtColor(input, bgra, Imgproc.COLOR_YUV420sp2BGRA);
//                if (IS_NEXUS_5X) {
//                    Core.flip(bgra.t(), bgra, 0);
//                } else {
//                    Core.flip(bgra.t(), bgra, 1);
//                }
//
////                Log.d("CardDetector", "frameUpdated >> bytesToMat");
////                Log.d(TAG, "WH: " + image.getWidth() + "x" + image.getHeight());
//
////                Mat mat = CardDetectorJNI.newInstance().debug(bgra.nativeObj);
////                File path = new File(Environment.getExternalStorageDirectory() + "/Images/");
////                path.mkdirs();
////                File file = new File(path, "image" + System.currentTimeMillis() + "_converted.png");
////                String filename = file.toString();
////                Imgcodecs.imwrite(filename, mat);
//
//                float stableValue = MovingDetectorJNI.newInstance().isStable(bgra);
//                channel.invokeMethod("camera.stableDetected", stableValue == 1.0);
//
////                bytes = null;
////                bgra = new Mat();
////                input = new Mat();
//
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }


//        int counter = 3;
//        /**
//         * Read camera state and detect is stable
//         *
//         * @param w       of image
//         * @param h       of image
//         * @param buffers will contents 3 elements, as Y,U,V match to position 0,1,2
//         */
//        private void handleStableStateFrameByFrame(int w, int h, ByteBuffer[] buffers) {
//            if (counter > 0) {
//                counter--;
//                return;
//            }
//            counter = 3;
//            try {
//                ByteBuffer buffer = ByteBuffer.allocate(buffers[0].capacity() + buffers[1].capacity() + buffers[2].capacity());
//                buffer.put(buffers[0]);
//                buffer.put(buffers[1]);
//                buffer.put(buffers[2]);
//                buffer.compact();
//
//                byte[] bytes = buffer.array();
//                Mat input = new Mat(h + h / 2, w, CvType.CV_8UC1);
//                Mat bgra = new Mat(h + h / 2, w, CvType.CV_8UC4);
//                input.put(0, 0, bytes);
//                Imgproc.cvtColor(input, bgra, Imgproc.COLOR_YUV420sp2BGRA);
//                if (IS_NEXUS_5X) {
//                    Core.flip(bgra.t(), bgra, 0);
//                } else {
//                    Core.flip(bgra.t(), bgra, 1);
//                }
//
////                Log.d("CardDetector", "frameUpdated >> bytesToMat");
////                Log.d(TAG, "WH: " + image.getWidth() + "x" + image.getHeight());
//
////                Mat mat = CardDetectorJNI.newInstance().debug(bgra.nativeObj);
////                File path = new File(Environment.getExternalStorageDirectory() + "/Images/");
////                path.mkdirs();
////                File file = new File(path, "image" + System.currentTimeMillis() + "_converted.png");
////                String filename = file.toString();
////                Imgcodecs.imwrite(filename, mat);
//
//                float stableValue = MovingDetectorJNI.newInstance().isStable(bgra);
//                channel.invokeMethod("camera.stableDetected", stableValue == 1.0);
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }

        private void sendErrorEvent(String errorDescription) {
            if (currentResult != null) {
                currentResult.error("Camera exception", errorDescription, null);
                currentResult = null;
            }
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

        private String getBase64Image(Bitmap bitmap) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream);
            byte[] bytes = byteArrayOutputStream.toByteArray();
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        }
    }

    static void printLog(String message) {
        Log.d("CAMERA2", message);
    }
}
