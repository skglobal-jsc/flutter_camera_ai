//package io.flutter.plugins.camera;
//
//import org.opencv.android.OpenCVLoader;
//import org.opencv.core.Mat;
//
///**
// * Created by tnluan on 4/12/18.
// */
//public class MovingDetectorJNI {
//    // Used to load the 'moving_detector' library on application startup.
//    static {
//        System.loadLibrary("moving_detector");
//        if (!OpenCVLoader.initDebug()) {
//            // Handle initialization error
//        }
//    }
//
//    // Singleton
//    private static MovingDetectorJNI movingDetectorJNI;
//    public static MovingDetectorJNI newInstance() {
//        if (movingDetectorJNI == null) {
//            movingDetectorJNI = new MovingDetectorJNI();
//        }
//        return movingDetectorJNI;
//    }
//    // Singleton-end
//
//    float isStable(final Mat image) {
//        return isStable(image.nativeObj);
//    }
//
//    float isDiff(final Mat image1, final Mat image2) {
//        return isDiff(image1.nativeObj, image2.nativeObj);
//    }
//
//    // JNI
//    public native float isStable(long nativeObj);
//    public native float isDiff(long nativeObj1, long nativeObj2);
//    // JNI-end
//
//}
