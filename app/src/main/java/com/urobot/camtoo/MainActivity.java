package com.urobot.camtoo;


import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.Size;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A basic demonstration of how to write a point-and-shoot camera app against the new
 * android.hardware.camera2 API.
 */
public class MainActivity extends GetPermission {
    /**
     * Tag to distinguish log prints.
     */
    static final String TAG = "CameraToo";

    HandlerThread mBackgroundThread;
    Handler mBackgroundHandler;
    Handler mForegroundHandler;
    SurfaceView mSurfaceView;
    ImageReader mCaptureBuffer;
    CameraManager mCameraManager;
    CameraDevice mCamera;
    CameraCaptureSession mCaptureSession;
    
    //--------------------------------------------------------------


    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values.
     *
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width   The minimum desired width
     * @param height  The minimum desired height
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    static Size chooseBigEnoughSize(Size[] choices, int width, int height) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<Size>();
        for (Size option : choices) {
            if (option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getPermission();
    }

    /**
     * Called when our {@code Activity} gains focus. <p>Starts initializing the camera.</p>
     */
    @Override
    protected void onResume() {
        super.onResume();

        // Start a background thread to manage camera requests
        mBackgroundThread = new HandlerThread("background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mForegroundHandler = new Handler(getMainLooper());

        mCameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        // Inflate the SurfaceView, set it as the main layout, and attach a listener
        //View layout = getLayoutInflater().inflate(R.layout.activity_main, null);
        mSurfaceView = (SurfaceView)findViewById(R.id.mainSurfaceView);
        mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
        //setContentView(mSurfaceView);
    }

    /**
     * Called when our {@code Activity} loses focus. <p>Tears everything back down.</p>
     */
    @Override
    protected void onPause() {
        super.onPause();

        try {
            // Ensure SurfaceHolderCallback#surfaceChanged() will run again if the user returns
            mSurfaceView.getHolder().setFixedSize(/*width*/0, /*height*/0);

            // Cancel any stale preview jobs
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
        } finally {
            if (mCamera != null) {
                mCamera.close();
                mCamera = null;
            }
        }

        // Finish processing posted messages, then join on the handling thread
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
        } catch (InterruptedException ex) {
            Log.e(TAG, "Background worker thread was interrupted while joined", ex);
        }

        // Close the ImageReader now that the background thread has stopped
        if (mCaptureBuffer != null) mCaptureBuffer.close();
    }


    /**
     * Callbacks invoked upon state changes in our {@code SurfaceView}.
     */
    final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        /** The camera device to use, or null if we haven't yet set a fixed surface size. */
        private String mCameraId;

        /** Whether we received a change callback after setting our fixed surface size. */
        private boolean mGotSecondCallback;

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            // This is called every time the surface returns to the foreground
            Log.i(TAG, "Surface created");
            mCameraId = null;
            mGotSecondCallback = false;
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i(TAG, "Surface destroyed");
            holder.removeCallback(this);
            // We don't stop receiving callbacks forever because onResume() will reattach us
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // On the first invocation, width and height were automatically set to the view's size
            if (mCameraId == null) {
                // Find the device's back-facing camera and set the destination buffer sizes
                try {
                    for (String cameraId : mCameraManager.getCameraIdList()) {
                        CameraCharacteristics cameraCharacteristics =
                                mCameraManager.getCameraCharacteristics(cameraId);
                        float minimumLens = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                        Log.d(TAG, String.format("surfaceChanged: mini f = %f", minimumLens));
                        if (cameraCharacteristics.get(cameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_BACK) {
                            Log.i(TAG, "Found a back-facing camera");
                            StreamConfigurationMap info = cameraCharacteristics
                                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                            // Bigger is better when it comes to saving our image
                            Size largestSize = Collections.max(
                                    Arrays.asList(info.getOutputSizes(ImageFormat.JPEG)),
                                    new CompareSizesByArea());

                            //largestSize = new Size(640, 480);
                            // Prepare an ImageReader in case the user wants to capture images
                            Log.i(TAG, "Capture size: " + largestSize);
                            mCaptureBuffer = ImageReader.newInstance(largestSize.getWidth(),
                                    largestSize.getHeight(), ImageFormat.JPEG, /*maxImages*/2);

                            // Danger, W.R.! Attempting to use too large a preview size could
                            // exceed the camera bus' bandwidth limitation, resulting in
                            // gorgeous previews but the storage of garbage capture data.
                            Log.i(TAG, "SurfaceView size: " +
                                    mSurfaceView.getWidth() + 'x' + mSurfaceView.getHeight());
                            Size optimalSize = chooseBigEnoughSize(
                                    info.getOutputSizes(SurfaceHolder.class), width, height);

                            // Set the SurfaceHolder to use the camera's largest supported size
                            Log.i(TAG, "Preview size: " + optimalSize);
                            SurfaceHolder surfaceHolder = mSurfaceView.getHolder();
                            surfaceHolder.setFixedSize(optimalSize.getWidth(),
                                    optimalSize.getHeight());

                            mCameraId = cameraId;
                            return;
                            // Control flow continues with this method one more time
                            // (since we just changed our own size)
                        }
                    }
                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Unable to list cameras", ex);
                }

                Log.e(TAG, "Didn't find any back-facing cameras");
                // This is the second time the method is being invoked: our size change is complete
            } else if (!mGotSecondCallback) {
                if (mCamera != null) {
                    Log.e(TAG, "Aborting camera open because it hadn't been closed");
                    return;
                }
                if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestCameraPermission();
                    return;
                }
                // Open the camera device
                try {
                    mCameraManager.openCamera(mCameraId, mCameraStateCallback, mBackgroundHandler);
                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Failed to configure output surface", ex);
                }
                mGotSecondCallback = true;

                // Control flow continues in mCameraStateCallback.onOpened()
            }
        }
    };

    /**
     * Calledbacks invoked upon state changes in our {@code CameraDevice}. <p>These are run on
     * {@code mBackgroundThread}.</p>
     */
    final CameraDevice.StateCallback mCameraStateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    Log.i(TAG, "Successfully opened camera");
                    mCamera = camera;
                    try {
                        List<Surface> outputs = Arrays.asList(
                                mSurfaceView.getHolder().getSurface(), mCaptureBuffer.getSurface());
                        camera.createCaptureSession(outputs, mCaptureSessionListener, mBackgroundHandler);
                    } catch (CameraAccessException ex)
                    {
                        Log.e(TAG, "Failed to create a capture session", ex);
                    }

                    // Control flow continues in mCaptureSessionListener.onConfigured()
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.e(TAG, "Camera was disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "State error on device '" + camera.getId() + "': code " + error);
                }
            };

    /**
     * Callbacks invoked upon state changes in our {@code CameraCaptureSession}. <p>These are run on
     * {@code mBackgroundThread}.</p>
     */
    final CameraCaptureSession.StateCallback mCaptureSessionListener =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    Log.i(TAG, "Finished configuring camera outputs");
                    mCaptureSession = session;

                    SurfaceHolder holder = mSurfaceView.getHolder();
                    if (holder != null) {
                        try {
                            // Build a request for preview footage
                            CaptureRequest.Builder rBuilder = mCamera.createCaptureRequest(mCamera.TEMPLATE_PREVIEW);
                            rBuilder.addTarget(holder.getSurface());
                            CaptureRequest previewRequest = rBuilder.build();

                            // Start displaying preview images
                            try {
                                session.setRepeatingRequest(previewRequest, /*listener*/null,/*handler*/null);
                            } catch (CameraAccessException ex) {
                                Log.e(TAG, "Failed to make repeating preview request", ex);
                            }
                        } catch (CameraAccessException ex) {
                            Log.e(TAG, "Failed to build preview request", ex);
                        }
                    } else {
                        Log.e(TAG, "Holder didn't exist when trying to formulate preview request");
                    }
                }

                @Override
                public void onClosed(CameraCaptureSession session) {
                    mCaptureSession = null;
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "Configuration error on device '" + mCamera.getId());
                }
            };
}
