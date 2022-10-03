package com.termux.api;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.termux.api.util.ResultReturner;
import com.termux.api.util.TermuxApiLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PhotoAPI {

    public static class CameraProperties {
        public String quality;
        public int autoFocusTime;

        public String toString() {
            return String.format("quality=[%s] autoFocusTime=[%d]", quality, autoFocusTime);
        }
    };

    static boolean isDone = false;
    final static long MAX_WAIT_TIME_MS = 15000;
    static boolean isPreviewDone = false;
    static long timePreviewStarted;
    static Context mContext;
    static CameraCharacteristics mCharacteristics;
    static int autoExposureModeFinal;
    static Integer[] autoFocusModesFinal;
    static Surface mImageReaderSurface;
    static CameraDevice mCamera;
    static CameraCaptureSession mSession;


    static void onReceive(TermuxApiReceiver apiReceiver, final Context context, Intent intent) {
        isDone = false;
        mContext = context;
        TermuxApiLogger.info("JK onReceive() ");
        long startTime = System.currentTimeMillis();

        final String filePath = intent.getStringExtra("file");          // If filename is "-", then redirect output to stdout
        final File outputFile;
        if (filePath.endsWith("/-")) {
            TermuxApiLogger.info("JK filePath is -. Use stdout as output. filepath=" + filePath);
            outputFile = null;
        } else {
            TermuxApiLogger.info("JK filePath is: " + filePath);
            outputFile = new File(filePath);
        }
        final String cameraId = Objects.toString(intent.getStringExtra("camera"), "0");
        final String autoFocusTime = Objects.toString(intent.getStringExtra("af_time"), "500");
        final String quality = Objects.toString(intent.getStringExtra("quality"), "max");
        final CameraProperties cameraProps = new CameraProperties();
        cameraProps.quality = quality;
        cameraProps.autoFocusTime = Integer.parseInt(autoFocusTime);

        TermuxApiLogger.info("JK Camera Properties: " + cameraProps);

        startBackGroundThread();

        if (outputFile != null) {
            // Output to file
            ResultReturner.returnData(apiReceiver, intent, stdout -> {
                if (outputFile != null) {
                    final File outputDir = outputFile.getParentFile();
                    if (!(outputDir.isDirectory() || outputDir.mkdirs())) {
                        //stdout.println("Not a folder (and unable to create it): " + outputDir.getAbsolutePath());
                        TermuxApiLogger.error("Not a folder (and unable to create it): " + outputDir.getAbsolutePath(), null);
                        return;
                    }
                } 
                takePicture(stdout, context, outputFile, cameraId, cameraProps);
                mBackgroundThread.join();
            }); 
        } else {
            // Output to stdout
            ResultReturner.returnData(apiReceiver, intent, new ResultReturner.BinaryOutput() {
                @Override
                public void writeResult(OutputStream stdout) throws Exception {
                    try {
                        takePicture(stdout, context, outputFile, cameraId, cameraProps);
                    } catch (Exception e) {
                        TermuxApiLogger.error("Output binary data error: ", e);
                    }   
                    mBackgroundThread.join();
                }
            });
        }

        TermuxApiLogger.info("JK Done Wait for isDone or timeout isDone=" + String.valueOf(isDone) + " MAX_WAIT_TIME_MS=" + MAX_WAIT_TIME_MS  + " startTime=" + startTime);
        while (!isDone && (System.currentTimeMillis() - startTime < MAX_WAIT_TIME_MS) ) {
            Thread.yield();
        }        
        TermuxApiLogger.info("JK Done onReceive() isDone=" + String.valueOf(isDone) + " MAX_WAIT_TIME_MS=" + MAX_WAIT_TIME_MS  + " " + (System.currentTimeMillis() - startTime));
        stopBackGroundThread();
        try {
            Thread.sleep(500);
        } catch (Exception e){

        }
    }

    private static void takePicture(final Object stdout, final Context context, final File outputFile, String cameraId, CameraProperties cameraProps) {
        TermuxApiLogger.info("JK takePicture() ");

        try {
            final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            // Looper.prepare();
            // final Looper looper = Looper.myLooper();
            Looper looper = mBackgroundThread.getLooper();

            //noinspection MissingPermission
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice camera) {
                    TermuxApiLogger.info("onOpened() from camera");
                    try {
                        proceedWithOpenedCamera(context, manager, camera, outputFile, looper, stdout, cameraProps);
                    } catch (Exception e) {
                        TermuxApiLogger.error("JK Exception in onOpened()", e);
                        closeCamera(camera, looper);
                        isDone = true;
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    TermuxApiLogger.info("JK onDisconnected() from camera. Cleanup");
                    closeCamera(camera, looper);
                    isDone = true;
                    //mImageReader.close();
                    //releaseSurfaces(outputSurfaces);
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    TermuxApiLogger.error("JK Failed opening camera: " + error);
                    closeCamera(camera, looper);
                    isDone = true;
                }
            }, mBackgroundHandler);

            //TermuxApiLogger.info("JK Before Looper.loop()");
            // Looper.loop();
            //TermuxApiLogger.info("JK After Looper.loop()");

            // Waiting for exist
            // while (!isDone) {
            //     Thread.yield();
            // }

        } catch (Exception e) {
            TermuxApiLogger.error("JK Error getting camera", e);
            isDone = true;
        }
    }

    //static List<Surface> outputSurfaces;
    //static ImageReader mImageReader;
    //static Surface imageReaderSurface;

    // See answer on http://stackoverflow.com/questions/31925769/pictures-with-camera2-api-are-really-dark
    // See https://developer.android.com/reference/android/hardware/camera2/CameraDevice.html#createCaptureSession(java.util.List<android.view.Surface>, android.hardware.camera2.CameraCaptureSession.StateCallback, android.os.Handler)
    // for information about guaranteed support for output sizes and formats.
    static void proceedWithOpenedCamera(final Context context, final CameraManager manager, final CameraDevice camera, final File outputFile, final Looper looper, final Object stdout, CameraProperties cameraProps) throws CameraAccessException, IllegalArgumentException {
        TermuxApiLogger.info("JK proceedWithOpenedCamera() ");

        final List<Surface> outputSurfaces = new ArrayList<>();
        //outputSurfaces = new ArrayList<>();

        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(camera.getId());
        mCharacteristics = characteristics;

        // Hardware Level
        final int hardwareLevelFinal = getHWLevel(characteristics);
        TermuxApiLogger.info("\n");

        final Integer[] capsFinal = getCapabilities(characteristics);
        TermuxApiLogger.info("\n");

        // AutoExposure        
        autoExposureModeFinal = getAEMode(characteristics);
        TermuxApiLogger.info("\n");

        // AutoWhitening
        final Integer[] awbModesFinal = getAWBModes(characteristics);
        TermuxApiLogger.info("\n");

        // AutoFocus
        autoFocusModesFinal = getAFModes(characteristics);
        TermuxApiLogger.info("\n");

        // Use largest available size:
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Comparator<Size> bySize = (lhs, rhs) -> {
            // Cast to ensure multiplications won't overflow:
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        };
        List<Size> sizes = Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
        //Size largest = Collections.max(sizes, bySize);
        Size imageQualitySize = Collections.max(sizes, bySize);
        String quality = cameraProps.quality;
        switch(quality) {
            case "max":
                // Use default
                // imageQualitySize = Collections.max(sizes, bySize);
                TermuxApiLogger.info("JK Max ImageQuality Used:[" + imageQualitySize.toString() + "]");
                break;            
            case "min":
                imageQualitySize = Collections.min(sizes, bySize);
                TermuxApiLogger.info("JK Min ImageQuality Used:[" + imageQualitySize.toString() + "]");
                break;            
            default:
                boolean exactMatchFound = false;
                for (Size s : sizes) {
                    //TermuxApiLogger.info("JK Compare: " + quality + " " + s.toString());
                    if (quality.equals(s.toString())) {
                        exactMatchFound = true;
                        imageQualitySize = s;
                        TermuxApiLogger.info("JK ImageQuality Used:[" + imageQualitySize.toString() + "]");
                        break;
                    }
                }
                if (!exactMatchFound) {
                    // Use largest as default if no match found
                    // imageQualitySize = Collections.max(sizes, bySize);
                    TermuxApiLogger.info("JK ImageQuality not found. desired:[" + quality +"]. Used:[" + imageQualitySize.toString() + "]");
                    TermuxApiLogger.info("JK Supported Quality: " + sizes.toString());
                }
                break;
        }

        // MAX_IMAGES determines the maximum number of Image objects that can be acquired from the ImageReader simultaneously.
        //            Once the maximum images has obtained by the user, the user need to release the image before a new image becomes available.
        final int MAX_IMAGES = 2;
        final ImageReader mImageReader = ImageReader.newInstance(imageQualitySize.getWidth(), imageQualitySize.getHeight(), ImageFormat.JPEG, MAX_IMAGES);
        //ImageReader mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
        mImageReader.setOnImageAvailableListener(reader -> new Thread() {
            @Override
            public void run() {
                TermuxApiLogger.info("JK image available");
                try (final Image mImage = reader.acquireNextImage()) {
                    ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    if (outputFile != null) {
                        TermuxApiLogger.info("JK Send image to file");
                        try (FileOutputStream output = new FileOutputStream(outputFile)) {
                            output.write(bytes);
                        } catch (Exception e) {
                            //stdout.println("Error writing image: " + e.getMessage());
                            TermuxApiLogger.error("Error writing file image", e);
                        }
                    } else {
                        // Send image to stdout                        
                        TermuxApiLogger.info("JK Send image to stdout length=" + bytes.length);
                        try {
                            // ResultReturner.BinaryOutput in v0.50 supports binary output
                            ((OutputStream)stdout).write(bytes);
                        } catch (Exception e) {
                            TermuxApiLogger.error("Error stdout file image", e);
                        }
                        // // TODO: Send to file for debugging
                        // File outputTestFile = new File("/data/data/com.termux/files/home/testtest.jpg");
                        // try (FileOutputStream output = new FileOutputStream(outputTestFile)) {
                        //     output.write(bytes);
                        // } catch (Exception e) {
                        //     //stdout.println("Error writing image: " + e.getMessage());
                        //     TermuxApiLogger.error("Error writing test file image", e);
                        // }
                    }
                } catch (Exception e) {
                    TermuxApiLogger.error("JK imageReader error", e);
                }
                finally {
                    TermuxApiLogger.info("JK Cleanup in imageAvailableListener. close mImageReader");
                    //closeCamera(camera, looper);
                    mImageReader.close();
                    //releaseSurfaces(outputSurfaces);
                }
            }
        }.start(), null);

        final Surface imageReaderSurface = mImageReader.getSurface();
        mImageReaderSurface = imageReaderSurface;
        //imageReaderSurface = mImageReader.getSurface();
        outputSurfaces.add(imageReaderSurface);

        // create a dummy PreviewSurface
        SurfaceTexture previewTexture = new SurfaceTexture(2);
        Surface dummySurface = new Surface(previewTexture);
        outputSurfaces.add(dummySurface);

        camera.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onClosed(final CameraCaptureSession session) {
                try {
                    TermuxApiLogger.info("JK CameraCaptureSession Closed");
                    closeCamera(camera, looper);
                    releaseSurfaces(outputSurfaces);
                } catch (Exception e) {
                    TermuxApiLogger.error("JK CameraCaptureSession Closed error.", e);
                }
            }

            @Override
            public void onConfigured(final CameraCaptureSession session) {
                TermuxApiLogger.info("JK CameraCaptureSession onConfigured");
                mCamera = camera;
                mSession = session;
                try {
                    // create preview Request
                    CaptureRequest.Builder previewReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    previewReq.addTarget(dummySurface);
                    if (Arrays.asList(autoFocusModesFinal).contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                        previewReq.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        TermuxApiLogger.info("JK proceedWithOpenedCamera use AF_MODE_CONTINUOUS_PICTURE");
                    } else {
                        TermuxApiLogger.info("JK proceedWithOpenedCamera AF_MODE_CONTINUOUS_PICTURE NOT supported! " + Arrays.toString(autoFocusModesFinal));
                        //TermuxApiLogger.info("JK proceedWithOpenedCamera AF_MODE_CONTINUOUS_PICTURE NOT supported! ");
                    }
                    previewReq.set(CaptureRequest.CONTROL_AE_MODE, autoExposureModeFinal);
    
                    // continous preview-capture for 1/2 second for autofocusing
                    // TODO: Use mBackgroundHandler instead of null???
                    //       Use previewCaptureCallback???
                    session.setRepeatingRequest(previewReq.build(), previewCaptureCallback, mBackgroundHandler);
                    //TermuxApiLogger.info("preview started " + LocalDateTime.now());
                    timePreviewStarted = System.currentTimeMillis();
                    isPreviewDone = false;
                    TermuxApiLogger.info("preview started " + System.currentTimeMillis());
                    // Don't want to kill the message loop with Thread.sleep()
                    //Thread.sleep(cameraProps.autoFocusTime);
                    // isPreviewDone = false;
                    // new android.os.Handler().postDelayed(new Runnable() {
                    //     public void run() {
                    //         try {
                    //             // Code to run after delayed time
                    //             TermuxApiLogger.info("JK Handler().postDelayed() isPreviewDone=true " + System.currentTimeMillis());
                    //         }
                    //         catch (Exception e)
                    //         {
                    //             TermuxApiLogger.error("JK Handler().postDelayed() Error" + System.currentTimeMillis(), e);
                    //         }
                    //         isPreviewDone = true;
                    //     }
                    // }, cameraProps.autoFocusTime);

//TODO Test
/*
                    while (!isPreviewDone && (System.currentTimeMillis() - timePreviewStarted) < cameraProps.autoFocusTime) {
                        Thread.yield();
                    }        
                    TermuxApiLogger.info("JK isPreviewDone=" + String.valueOf(isPreviewDone) + " elapsed ms= " +  (System.currentTimeMillis() - timePreviewStarted) + " autoFocusTime=" + cameraProps.autoFocusTime);
                    TermuxApiLogger.info("JK abortCaptures " + System.currentTimeMillis());
                    session.abortCaptures();
                                
                    // while (!isPreviewDone) {
                    //     Thread.yield();
                    // }

                    // TODO: Moved to preview capture handler
                    //session.stopRepeating();
                    //TermuxApiLogger.info("preview stoppend " +  + System.currentTimeMillis());
                    //TermuxApiLogger.info("preview elapsed ms= " +  (System.currentTimeMillis() - timePreviewStarted));
    
                    //TODO: If release here, picture will not be taken
                    //TermuxApiLogger.info("JK Release previewTexture, dummySurface");
                    //previewTexture.release();
                    //dummySurface.release();
    
                    TermuxApiLogger.info("JK CaptureRequest.Builder");
                    final CaptureRequest.Builder jpegRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
    
                    // Render to our image reader:
                    TermuxApiLogger.info("JK addTarget. Render to our image reader");
                    jpegRequest.addTarget(imageReaderSurface);
    
                    // Configure auto-focus (AF) and auto-exposure (AE) modes:
                    TermuxApiLogger.info("JK AutoFocus. AutoExposure. ");
                    // TODO: Need to check if AF_MODE_CONTINUOUS_PICTURE is supported
                    //jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    if (Arrays.asList(autoFocusModesFinal).contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                        jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        TermuxApiLogger.info("JK proceedWithOpenedCamera. jpegRequest use AF_MODE_CONTINUOUS_PICTURE");
                    } else {
                        jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                        TermuxApiLogger.info("JK proceedWithOpenedCamera jpegRequest AF_MODE_CONTINUOUS_PICTURE NOT supported! Use AF_MODE_OFF");
                    }
    
                    jpegRequest.set(CaptureRequest.CONTROL_AE_MODE, autoExposureModeFinal);
                    jpegRequest.set(CaptureRequest.JPEG_ORIENTATION, correctOrientation(context, characteristics));
    
                    saveImage(camera, session, jpegRequest.build());
*/                    
    
                    // new android.os.Handler().postDelayed(new Runnable() {
                    //     public void run() {
                    //         try {
                    //             // Code to run after delayed time
                    //             session.stopRepeating();
                    //             TermuxApiLogger.info("preview stoppend " +  + System.currentTimeMillis());
                    //             TermuxApiLogger.info("preview elapsed ms= " +  (System.currentTimeMillis() - timePreviewStarted));
            
                    //             //TODO: If release here, picture will not be taken
                    //             //TermuxApiLogger.info("JK Release previewTexture, dummySurface");
                    //             //previewTexture.release();
                    //             //dummySurface.release();
            
            
                    //             TermuxApiLogger.info("JK CaptureRequest.Builder");
                    //             final CaptureRequest.Builder jpegRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            
                    //             // Render to our image reader:
                    //             TermuxApiLogger.info("JK addTarget. Render to our image reader");
                    //             jpegRequest.addTarget(imageReaderSurface);
            
            
                    //             // Configure auto-focus (AF) and auto-exposure (AE) modes:
                    //             TermuxApiLogger.info("JK AutoFocus. AutoExposure. ");
                    //             // TODO: Need to check if AF_MODE_CONTINUOUS_PICTURE is supported
                    //             //jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    //             if (Arrays.asList(autoFocusModesFinal).contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    //                 jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    //                 TermuxApiLogger.info("JK proceedWithOpenedCamera. jpegRequest use AF_MODE_CONTINUOUS_PICTURE");
                    //             } else {
                    //                 jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                    //                 TermuxApiLogger.info("JK proceedWithOpenedCamera jpegRequest AF_MODE_CONTINUOUS_PICTURE NOT supported! Use AF_MODE_OFF");
                    //             }
            
                    //             jpegRequest.set(CaptureRequest.CONTROL_AE_MODE, autoExposureModeFinal);
                    //             jpegRequest.set(CaptureRequest.JPEG_ORIENTATION, correctOrientation(context, characteristics));
            
                    //             saveImage(camera, session, jpegRequest.build());
                    //             //TermuxApiLogger.info("JK Sleep 1000");
                    //             //Thread.sleep(1000);
                    //         } catch (Exception e) {
                    //             TermuxApiLogger.error("android.os.Handler() error", e);
                    //         } finally {
                    //         }
                    //     }
                    // }, cameraProps.autoFocusTime);
                    // TermuxApiLogger.info("JK onConfigured() WaitForDone" );
                    // while (!isDone && (System.currentTimeMillis() - timePreviewStarted < MAX_WAIT_TIME_MS) ) {
                    //     Thread.yield();
                    // }        
                    // TermuxApiLogger.info("JK Done onConfigured() isDone=" + String.valueOf(isDone) + " MAX_WAIT_TIME_MS=" + MAX_WAIT_TIME_MS  + " " + (System.currentTimeMillis() - timePreviewStarted));
                    TermuxApiLogger.info("JK DONE onConfigured()");
                } catch (Exception e) {
                    // TODO: Should error handling be done in here or wait till onConfigureFailed()
                    // TODO: This seems wrong to close camera in here. Maybe just close session??
                    TermuxApiLogger.error("JK onConfigured() exception in preview", e);
                    //closeCamera(camera, looper);
                    TermuxApiLogger.error("JK Release mImageReader, releaseSurface, closeCamera");
                    closeCamera(camera, looper);
                    mImageReader.close();
                    releaseSurfaces(outputSurfaces);
                }  finally {
                    // TermuxApiLogger.error("JK Release mImageReader, releaseSurface, closeCamera");
                    // mImageReader.close();
                    // releaseSurfaces(outputSurfaces);
                    // closeCamera(camera, looper);
                }    
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                TermuxApiLogger.error("JK onConfigureFailed() error in preview. cleanup");
                session.close();
                closeCamera(camera, looper);
                mImageReader.close();
                releaseSurfaces(outputSurfaces);
            }
        }, null);

        TermuxApiLogger.info("JK DONE proceedWithOpenedCamera() ");
    }

    // Capture Image
    private static void captureImage() {
        try {
            CameraDevice camera = mCamera;
            CameraCaptureSession session = mSession;
    
            TermuxApiLogger.info("JK CaptureRequest.Builder");
            final CaptureRequest.Builder jpegRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            // Render to our image reader:
            TermuxApiLogger.info("JK addTarget. Render to our image reader");
            jpegRequest.addTarget(mImageReaderSurface);

            // Configure auto-focus (AF) and auto-exposure (AE) modes:
            TermuxApiLogger.info("JK AutoFocus. AutoExposure. ");
            // TODO: Need to check if AF_MODE_CONTINUOUS_PICTURE is supported
            //jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if (Arrays.asList(autoFocusModesFinal).contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                TermuxApiLogger.info("JK proceedWithOpenedCamera. jpegRequest use AF_MODE_CONTINUOUS_PICTURE");
            } else {
                jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                TermuxApiLogger.info("JK proceedWithOpenedCamera jpegRequest AF_MODE_CONTINUOUS_PICTURE NOT supported! Use AF_MODE_OFF");
            }

            jpegRequest.set(CaptureRequest.CONTROL_AE_MODE, autoExposureModeFinal);
            jpegRequest.set(CaptureRequest.JPEG_ORIENTATION, correctOrientation(mContext, mCharacteristics));

            saveImage(camera, session, jpegRequest.build());

            // Cleanup
            TermuxApiLogger.info("JK captureImage. stopRepeating() abortCaptures()");
            session.stopRepeating();
            session.abortCaptures();   
        } catch (Exception e) {
            TermuxApiLogger.error("JK captureImage. ",e);
        }

    }

    // Camera preview capture callback
    private static CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult result) {
             //TermuxApiLogger.info("JK previewCaptureCallback process");
             Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
             if (afState != null) {
                switch (afState) {
                    case CaptureResult.CONTROL_AF_STATE_INACTIVE:
                        TermuxApiLogger.info("JK previewCaptureCallback CONTROL_AF_STATE_INACTIVE");
                        break;
                    case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:
                        TermuxApiLogger.info("JK previewCaptureCallback CONTROL_AF_STATE_FOCUSED_LOCKED");
                        break;
                    case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
                        TermuxApiLogger.info("JK previewCaptureCallback CONTROL_AF_STATE_NOT_FOCUSED_LOCKED");
                        break;
                    case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED:
                        TermuxApiLogger.info("JK previewCaptureCallback CONTROL_AF_STATE_PASSIVE_FOCUSED");
                        captureImage();
                        break;
                    case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED:
                        TermuxApiLogger.info("JK previewCaptureCallback CONTROL_AF_STATE_PASSIVE_UNFOCUSED");
                        break;
                    case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN:
                        TermuxApiLogger.info("JK previewCaptureCallback CONTROL_AF_STATE_PASSIVE_SCAN");
                        break;
                    default:
                        TermuxApiLogger.info("JK previewCaptureCallback AF State. Unknown Code: " + String.valueOf(afState));
                        break;   
                };
            } else {
                TermuxApiLogger.info("JK previewCaptureCallback No AF State");
            }
            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            if (aeState != null) {
                switch (aeState) {
                    case CaptureResult.CONTROL_AE_STATE_CONVERGED:
                        TermuxApiLogger.info("JK previewCaptureCallback CONTROL_AE_STATE_CONVERGED");
                        break;
                    case CaptureResult.CONTROL_AE_STATE_PRECAPTURE:
                        TermuxApiLogger.info("JK previewCaptureCallback CONTROL_AE_STATE_PRECAPTURE");
                        break;
                    case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED:
                        TermuxApiLogger.info("JK previewCaptureCallback CONTROL_AE_STATE_FLASH_REQUIRED");
                        break;
                    default:
                        TermuxApiLogger.info("JK previewCaptureCallback AE State. Unknown Code: " + String.valueOf(afState));
                        break;   
                };
            } else {
                TermuxApiLogger.info("JK previewCaptureCallback No AE State");
            }
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult result) {
            TermuxApiLogger.info("JK previewCaptureCallback onCaptureProgressed process");
            process(result);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            TermuxApiLogger.info("JK previewCaptureCallback onCaptureCompleted process");
            try {
                process(result);
                //TermuxApiLogger.info("preview stoppend " +  + System.currentTimeMillis());
                //TermuxApiLogger.info("preview elapsed ms= " +  (System.currentTimeMillis() - timePreviewStarted));

// TODO: Test                
                //session.stopRepeating();

                // TODO: Got runtime error
                // Map<String, CaptureResult> results = result.getPhysicalCameraResults();
                // for (String key : results.keySet()) {
                //     TermuxApiLogger.info("PreviewCaptureResults ID: " + key + " " + String.valueOf(results.get(key)));
                // }
            } catch (Exception e) {
                TermuxApiLogger.error("JK previewCaptureCallback onCaptureCompleted session.stopRepeating", e);
            }

//TODO Test            
//            TermuxApiLogger.info("isPreviewDone=true");
//            isPreviewDone = true;
        }
    };

    private static HandlerThread mBackgroundThread;
    private static Handler mBackgroundHandler;

    private static void startBackGroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private static void stopBackGroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (Exception e) {
            TermuxApiLogger.error("JK stopBackGroundThread() ", e);
        }
    }

    static void saveImage(final CameraDevice camera, CameraCaptureSession session, CaptureRequest request) throws CameraAccessException {
        //TermuxApiLogger.info("JK saveImage() ");

        session.capture(request, new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession completedSession, CaptureRequest request, TotalCaptureResult result) {
                TermuxApiLogger.info("JK onCaptureCompleted() Cleanup");
                completedSession.close();
                //closeCamera(camera, looper);
                //mImageReader.close();
                //releaseSurfaces(outputSurfaces);
                //closeCamera(camera, null);
            }
        }, null);
        //TermuxApiLogger.info("JK Done saveImage() ");
    }

    /**
     * Determine the correct JPEG orientation, taking into account device and sensor orientations.
     * See https://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
     */
    static int correctOrientation(final Context context, final CameraCharacteristics characteristics) {
        TermuxApiLogger.info("JK correctOrientation() ");

        final Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
        final boolean isFrontFacing = lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT;
        TermuxApiLogger.info((isFrontFacing ? "Using" : "Not using") + " a front facing camera.");

        Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (sensorOrientation != null) {
            TermuxApiLogger.info(String.format("Sensor orientation: %s degrees", sensorOrientation));
        } else {
            TermuxApiLogger.info("CameraCharacteristics didn't contain SENSOR_ORIENTATION. Assuming 0 degrees.");
            sensorOrientation = 0;
        }
     
        int deviceOrientation;
        // TODO: does this return the proper value when display is off?
        //       This orientation is based upon the screen orientation of termux-app on the phone. Screen must be on to report properly.
        //       If screen off, will report the default position(camera on top), even when the phone itself is sideways(camera on left)
        //       Cant keep the screen on always on by long pressing on termux-app cli window -> more... -> Check "keep screen on"
        final int deviceRotation =
                ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        switch (deviceRotation) {
            case Surface.ROTATION_0:
                deviceOrientation = 0;
                break;
            case Surface.ROTATION_90:
                deviceOrientation = 90;
                break;
            case Surface.ROTATION_180:
                deviceOrientation = 180;
                break;
            case Surface.ROTATION_270:
                deviceOrientation = 270;
                break;
            default:
                TermuxApiLogger.info(
                        String.format("Default display has unknown rotation %d. Assuming 0 degrees.", deviceRotation));
                deviceOrientation = 0;
        }
        TermuxApiLogger.info(String.format("Device orientation: %d degrees", deviceOrientation));

        int configOrientation = context.getResources().getConfiguration().orientation;
        TermuxApiLogger.info("JK configOrientation=" + String.valueOf(configOrientation));

        boolean screenIsOn = false;
        for (Display display : ((DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE)).getDisplays()) {
            if (display.getState() != Display.STATE_OFF) {
                screenIsOn = true;
            }
        }
        TermuxApiLogger.info("JK screenIsOn=" + String.valueOf(screenIsOn));


        int jpegOrientation;
        if (isFrontFacing) {
            jpegOrientation = sensorOrientation + deviceOrientation;
        } else {
            jpegOrientation = sensorOrientation - deviceOrientation;
        }
        // Add an extra 360 because (-90 % 360) == -90 and Android won't accept a negative rotation.
        jpegOrientation = (jpegOrientation + 360) % 360;
        TermuxApiLogger.info(String.format("Returning JPEG orientation of %d degrees", jpegOrientation));
        return jpegOrientation;
    }

    static void releaseSurfaces(List<Surface> outputSurfaces) {
        for (Surface outputSurface : outputSurfaces) {
                    outputSurface.release();
        }
        TermuxApiLogger.info("surfaces released");
    }
        
    static void closeCamera(CameraDevice camera, Looper looper) {
        TermuxApiLogger.info("JK closeCamera() ");
        try {
            camera.close();
        } catch (RuntimeException e) {
            TermuxApiLogger.info("Exception closing camera: " + e.getMessage());
        }
        if (looper != null) looper.quit();
        isDone = true;
        TermuxApiLogger.info("JK Done closeCamera() ");
    }

    static int getHWLevel(CameraCharacteristics characteristics) {
        int hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        switch(hwLevel) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                TermuxApiLogger.info("INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: " + String.valueOf(hwLevel));
                break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                TermuxApiLogger.info("INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: " + String.valueOf(hwLevel));
                break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                TermuxApiLogger.info("INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: " + String.valueOf(hwLevel));
                break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                TermuxApiLogger.info("INFO_SUPPORTED_HARDWARE_LEVEL_FULL: " + String.valueOf(hwLevel));
                break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                TermuxApiLogger.info("INFO_SUPPORTED_HARDWARE_LEVEL_3: " + String.valueOf(hwLevel));
                break;
            default:
                TermuxApiLogger.info("Unknown hardware level: " + String.valueOf(hwLevel));
                break;
        }
        return hwLevel;
    }

    static int getAEMode(CameraCharacteristics characteristics) {
        int autoExposureMode = CameraMetadata.CONTROL_AE_MODE_OFF;
        for (int supportedMode : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)) {
            if (supportedMode == CameraMetadata.CONTROL_AE_MODE_ON) {
                TermuxApiLogger.info("JK proceedWithOpenedCamera AE_MODE_ON ");
                autoExposureMode = supportedMode;
            }
        }
        return autoExposureMode;
    }

    static Integer[] getAWBModes(CameraCharacteristics characteristics) {
        int awbModes[] = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        Integer autoWhiteningBalanceModes[] = new Integer[awbModes.length];
        TermuxApiLogger.info("JK proceedWithOpenedCamera AWB_MODES: length=" + String.valueOf(awbModes.length));
        if (awbModes.length == 0 || (awbModes.length==1 && awbModes[0]==CameraMetadata.CONTROL_AWB_MODE_OFF)) {
            TermuxApiLogger.info("JK proceedWithOpenedCamera awbModes NOT supported. awbModes.length=" + String.valueOf(awbModes.length) );
        } else {
            for (int i = 0; i < awbModes.length; i++ )
            {
                int supportedMode = awbModes[i];
                switch(supportedMode) {
                    case CameraMetadata.CONTROL_AWB_MODE_OFF:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_OFF: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_AUTO:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_AUTO: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_INCANDESCENT: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_WARM_FLUORESCENT: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_FLUORESCENT: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_DAYLIGHT: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_CLOUDY_DAYLIGHT: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_TWILIGHT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_TWILIGHT: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_SHADE:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_SHADE: " + String.valueOf(supportedMode));
                        break;
                    default:
                        TermuxApiLogger.error("JK proceedWithOpenedCamera Unknown CONTROL_AWB Code: " + String.valueOf(supportedMode));
                        break;
                };
                autoWhiteningBalanceModes[i] = supportedMode;
            } 
        }   
        return autoWhiteningBalanceModes;
    }

    // Need to use Integer[] so can perform ArrayList.contain(). contain() doesn't work with native int[] because entire int[] becomes a single element for to List
    static Integer[] getAFModes(CameraCharacteristics characteristics) {
        int afModes[] = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        final Integer[] autoFocusModes = new Integer[afModes.length];
        TermuxApiLogger.info("JK proceedWithOpenedCamera AF_MODES: length=" + String.valueOf(afModes.length));
        if (afModes.length == 0 || (afModes.length==1 && afModes[0]==CameraMetadata.CONTROL_AF_MODE_OFF)) {
            TermuxApiLogger.info("JK proceedWithOpenedCamera afModes NOT supported. afModes.length=" + String.valueOf(afModes.length) );
        } else {
            for (int i=0; i < afModes.length; i++ ) {
                int supportedMode = afModes[i];
                switch(supportedMode) {
                    case CameraMetadata.CONTROL_AF_MODE_OFF:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_OFF: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AF_MODE_AUTO:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_AUTO: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AF_MODE_MACRO:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_MACRO: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_COTINUOUS_PICTURE: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_COTINUOUS_VIDEO: " + String.valueOf(supportedMode));
                        break;
                    case CameraMetadata.CONTROL_AF_MODE_EDOF:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_EDOF: " + String.valueOf(supportedMode));
                        break;
                    default:
                        TermuxApiLogger.error("JK proceedWithOpenedCamera Unknown CONTROL_AF Code: " + String.valueOf(supportedMode));
                        break;
                };
                autoFocusModes[i] = supportedMode;
            }    
        }
        return autoFocusModes;
    }

    // Need to use Integer[] so can perform ArrayList.contain(). contain() doesn't work with native int[] because entire int[] becomes a single element for to List
    static Integer[] getCapabilities(CameraCharacteristics characteristics) {
        int caps[] = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        final Integer[] capabilities = new Integer[caps.length];
        TermuxApiLogger.info("Capabilities: length=" + String.valueOf(caps.length));
        if (caps.length == 0) {
            TermuxApiLogger.info("JK No capabilities. caps.length=" + String.valueOf(caps.length) );
        } else {
            for (int i=0; i < caps.length; i++) {
                int cap = caps[i];
                switch(cap) {
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE: " + String.valueOf(cap));
                        break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE: " + String.valueOf(cap));
                        break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO: " + String.valueOf(cap));
                        break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT: " + String.valueOf(cap));
                        break;
                    // case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT:
                    //     TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT");
                    //     break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA: " + String.valueOf(cap));
                        break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING: " + String.valueOf(cap));
                        break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR: " + String.valueOf(cap));
                        break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME: " + String.valueOf(cap));
                        break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING: " + String.valueOf(cap));
                        break;
                    // case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING:
                    //     TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING");
                    //     break;
                    // case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_PROCESSING:
                    //     TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_PROCESSING");
                    //     break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_RAW: " + String.valueOf(cap));
                        break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS: " + String.valueOf(cap));
                        break;
                    // case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING:
                    //     TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING");
                    //     break;
                    // case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA:
                    //     TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA");
                    //     break;
                    // case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE:
                    //     TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE");
                    //     break;
                    // case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA:
                    //     TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA");
                    //     break;
                    // case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR:
                    //     TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR");
                    //     break;
                    case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING:
                        TermuxApiLogger.info("REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING: " + String.valueOf(cap));
                        break;
                    default:
                        TermuxApiLogger.error("JK proceedWithOpenedCamera Unknown Capabilities Code: " + String.valueOf(cap));
                        break;
                };
                capabilities[i] = Integer.valueOf(cap);
            }    
        }
        return capabilities;
    }

}

