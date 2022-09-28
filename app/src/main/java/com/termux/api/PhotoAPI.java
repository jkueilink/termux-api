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
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import com.termux.api.util.ResultReturner;
import com.termux.api.util.TermuxApiLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class PhotoAPI {

    static void onReceive(TermuxApiReceiver apiReceiver, final Context context, Intent intent) {
        TermuxApiLogger.info("JK onReceive() ");

        final String filePath = intent.getStringExtra("file");
        final File outputFile = new File(filePath);
        final File outputDir = outputFile.getParentFile();
        final String cameraId = Objects.toString(intent.getStringExtra("camera"), "0");

        ResultReturner.returnData(apiReceiver, intent, stdout -> {
            if (!(outputDir.isDirectory() || outputDir.mkdirs())) {
                stdout.println("Not a folder (and unable to create it): " + outputDir.getAbsolutePath());
            } else {
                takePicture(stdout, context, outputFile, cameraId);
            }
        });
    }

    private static void takePicture(final PrintWriter stdout, final Context context, final File outputFile, String cameraId) {
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
                        proceedWithOpenedCamera(context, manager, camera, outputFile, looper, stdout);
                    } catch (Exception e) {
                        TermuxApiLogger.error("Exception in onOpened()", e);
                        closeCamera(camera, looper);
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    TermuxApiLogger.info("JK onDisconnected() from camera. Cleanup");
                    closeCamera(camera, looper);
                    //mImageReader.close();
                    //releaseSurfaces(outputSurfaces);
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    TermuxApiLogger.error("Failed opening camera: " + error);
                    closeCamera(camera, looper);
                }
            }, null);

            Looper.loop();
        } catch (Exception e) {
            TermuxApiLogger.error("Error getting camera", e);
        }
    }

    //static List<Surface> outputSurfaces;
    //static ImageReader mImageReader;
    //static Surface imageReaderSurface;

    // See answer on http://stackoverflow.com/questions/31925769/pictures-with-camera2-api-are-really-dark
    // See https://developer.android.com/reference/android/hardware/camera2/CameraDevice.html#createCaptureSession(java.util.List<android.view.Surface>, android.hardware.camera2.CameraCaptureSession.StateCallback, android.os.Handler)
    // for information about guaranteed support for output sizes and formats.
    static void proceedWithOpenedCamera(final Context context, final CameraManager manager, final CameraDevice camera, final File outputFile, final Looper looper, final PrintWriter stdout) throws CameraAccessException, IllegalArgumentException {
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
        Size largest = Collections.max(sizes, bySize);

        final ImageReader mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
        //ImageReader mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
        mImageReader.setOnImageAvailableListener(reader -> new Thread() {
            @Override
            public void run() {
                TermuxApiLogger.info("JK image available");
                try (final Image mImage = reader.acquireNextImage()) {
                    ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    try (FileOutputStream output = new FileOutputStream(outputFile)) {
                        output.write(bytes);
                    } catch (Exception e) {
                        stdout.println("Error writing image: " + e.getMessage());
                        TermuxApiLogger.error("Error writing image", e);
                    }
                } finally {
                    TermuxApiLogger.info("JK TODO: Cleanup in imageAvailableListener");
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

                    // TODO: Skip preview
                    //TermuxApiLogger.error("TODO: Skip preview");
                    // continous preview-capture for 1/2 second
                    session.setRepeatingRequest(previewReq.build(), null, null);
                    TermuxApiLogger.info("preview started");
                    Thread.sleep(500);
                    session.stopRepeating();
                    TermuxApiLogger.info("preview stoppend");

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
                    //TermuxApiLogger.info("JK Sleep 1000");
                    //Thread.sleep(1000);
                } catch (Exception e) {
                    // TODO: Should error handling be done in here or wait till onConfigureFailed()
                    // TODO: This seems wrong to close camera in here. Maybe just close session??
                    TermuxApiLogger.error("onConfigured() error in preview", e);
                    //closeCamera(camera, looper);
                    TermuxApiLogger.error("JK Release mImageReader, releaseSurface, closeCamera");
                    mImageReader.close();
                    releaseSurfaces(outputSurfaces);
                    closeCamera(camera, looper);
                }  finally {
                    // TermuxApiLogger.error("JK Release mImageReader, releaseSurface, closeCamera");
                    // mImageReader.close();
                    // releaseSurfaces(outputSurfaces);
                    // closeCamera(camera, looper);
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                TermuxApiLogger.error("onConfigureFailed() error in preview. cleanup");
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
                TermuxApiLogger.info("onCaptureCompleted() Cleanup");
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
        TermuxApiLogger.info("JK Done closeCamera() ");
    }

}