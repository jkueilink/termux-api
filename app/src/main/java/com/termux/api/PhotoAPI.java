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
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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


    static void onReceive(TermuxApiReceiver apiReceiver, final Context context, Intent intent) {
        isDone = false;
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

                }
            });
        }
        while (!isDone && (System.currentTimeMillis() - startTime < MAX_WAIT_TIME_MS) ) {
            Thread.yield();
        }        
        TermuxApiLogger.info("JK Done onReceive() isDone=" + String.valueOf(isDone) + " MAX_WAIT_TIME_MS=" + MAX_WAIT_TIME_MS  + " " + (System.currentTimeMillis() - startTime));
        try {
            Thread.sleep(500);
        } catch (Exception e){

        }
    }

    private static void takePicture(final Object stdout, final Context context, final File outputFile, String cameraId, CameraProperties cameraProps) {
        TermuxApiLogger.info("JK takePicture() ");

        try {
            final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            Looper.prepare();
            final Looper looper = Looper.myLooper();

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
            }, null);

            Looper.loop();
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

        int autoExposureMode = CameraMetadata.CONTROL_AE_MODE_OFF;
        for (int supportedMode : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)) {
            if (supportedMode == CameraMetadata.CONTROL_AE_MODE_ON) {
                TermuxApiLogger.info("JK proceedWithOpenedCamera AE_MODE_ON ");
                autoExposureMode = supportedMode;
            }
        }
        final int autoExposureModeFinal = autoExposureMode;

        int awbModes[] = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        TermuxApiLogger.info("JK proceedWithOpenedCamera AWB_MODES: length=" + String.valueOf(awbModes.length));
        if (awbModes.length == 0 || (awbModes.length==1 && awbModes[0]==CameraMetadata.CONTROL_AWB_MODE_OFF)) {
            TermuxApiLogger.info("JK proceedWithOpenedCamera awbModes NOT supported. awbModes.length=" + String.valueOf(awbModes.length) );
        } else {
            for (int supportedMode : awbModes) {
                switch(supportedMode) {
                    case CameraMetadata.CONTROL_AWB_MODE_OFF:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_OFF");
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_AUTO:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_AUTO");
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_INCANDESCENT");
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_WARM_FLUORESCENT");
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_FLUORESCENT");
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_DAYLIGHT");
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_CLOUDY_DAYLIGHT");
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_TWILIGHT:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_TWILIGHT");
                        break;
                    case CameraMetadata.CONTROL_AWB_MODE_SHADE:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AWB_MODE_SHADE");
                        break;
                    default:
                        TermuxApiLogger.error("JK proceedWithOpenedCamera Unknown CONTROL_AWB Code: " + String.valueOf(supportedMode));
                        break;
                };
            }    
        }
        final int[] awbModesFinal = awbModes;
        TermuxApiLogger.info("\n");

        int autoFocusMode = CameraMetadata.CONTROL_AF_MODE_OFF;
        int afModes[] = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        TermuxApiLogger.info("JK proceedWithOpenedCamera AF_MODES: length=" + String.valueOf(afModes.length));
        if (afModes.length == 0 || (afModes.length==1 && afModes[0]==CameraMetadata.CONTROL_AF_MODE_OFF)) {
            TermuxApiLogger.info("JK proceedWithOpenedCamera afModes NOT supported. afModes.length=" + String.valueOf(afModes.length) );
        } else {
            for (int supportedMode : afModes) {
                switch(supportedMode) {
                    case CameraMetadata.CONTROL_AF_MODE_OFF:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_OFF");
                        break;
                    case CameraMetadata.CONTROL_AF_MODE_AUTO:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_AUTO");
                        break;
                    case CameraMetadata.CONTROL_AF_MODE_MACRO:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_MACRO");
                        break;
                    case CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_COTINUOUS_PICTURE");
                        break;
                    case CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_COTINUOUS_VIDEO");
                        break;
                    case CameraMetadata.CONTROL_AF_MODE_EDOF:
                        TermuxApiLogger.info("JK proceedWithOpenedCamera CONTROL_AF_MODE_EDOF");
                        break;
                    default:
                        TermuxApiLogger.error("JK proceedWithOpenedCamera Unknown CONTROL_AF Code: " + String.valueOf(supportedMode));
                        break;
                };
                autoFocusMode = supportedMode;
                // if (supportedMode == CameraMetadata.CONTROL_AE_MODE_ON) {
                //     TermuxApiLogger.info("JK proceedWithOpenedCamera AE_MODE_ON ");
                //     autoExposureMode = supportedMode;
                // }
            }    
        }
        // Need to do this because Arrays.List().contain doesn't work as expected for int[], where it put the entire int[] as a single element
        final Integer[] autoFocusModesFinal = new Integer[afModes.length];
        int i = 0;
        for (int value : afModes) {
            autoFocusModesFinal[i++] = Integer.valueOf(value);
        }
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
                } finally {
                    TermuxApiLogger.info("JK Cleanup in imageAvailableListener. close mImageReader");
                    //closeCamera(camera, looper);
                    mImageReader.close();
                    //releaseSurfaces(outputSurfaces);
                }
            }
        }.start(), null);

        final Surface imageReaderSurface = mImageReader.getSurface();
        //imageReaderSurface = mImageReader.getSurface();
        outputSurfaces.add(imageReaderSurface);

        // create a dummy PreviewSurface
        SurfaceTexture previewTexture = new SurfaceTexture(1);
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
                    session.setRepeatingRequest(previewReq.build(), null, null);
                    //TermuxApiLogger.info("preview started " + LocalDateTime.now());
                    long timePreviewStarted = System.currentTimeMillis();
                    TermuxApiLogger.info("preview started " + System.currentTimeMillis());
                    // Don't want to kill the message loop with Thread.sleep()
                    Thread.sleep(cameraProps.autoFocusTime);
                    session.stopRepeating();
                    TermuxApiLogger.info("preview stoppend " +  + System.currentTimeMillis());
                    TermuxApiLogger.info("preview elapsed ms= " +  (System.currentTimeMillis() - timePreviewStarted));

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

}