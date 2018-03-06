package com.softwarejoint.media.camera;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import com.softwarejoint.media.R;
import com.softwarejoint.media.enums.FlashMode;
import com.softwarejoint.media.utils.CameraHelper;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

import static android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;
import static android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT;
import static android.hardware.Camera.Parameters.FLASH_MODE_OFF;
import static android.hardware.Camera.Parameters.FLASH_MODE_TORCH;
import static com.softwarejoint.media.utils.Constants.PREFERRED_PREVIEW_HEIGHT;
import static com.softwarejoint.media.utils.Constants.PREFERRED_PREVIEW_WIDTH;

/**
 * Thread for asynchronous operation of camera preview
 */
@SuppressWarnings("WeakerAccess")
public final class CameraThread extends Thread {

    private static final String TAG = "CameraThread";

    private final Object mReadyFence = new Object();
    private final WeakReference<CameraGLView> mWeakParent;
    private CameraHandler mHandler;
    private CameraZoom cameraZoom;

    public volatile boolean mIsRunning = false;
    private volatile @FlashMode
    int mFlashMode = FlashMode.UNAVAILABLE;
    private Camera mCamera;

    public CameraThread(final CameraGLView parent) {
        super("Camera thread");
        mWeakParent = new WeakReference<>(parent);
    }

    public CameraHandler getHandler() {
        synchronized (mReadyFence) {
            try {
                mReadyFence.wait();
            } catch (final InterruptedException ignore) {
            }
        }
        return mHandler;
    }

    /**
     * message loop
     * prepare Looper and create Handler for this thread
     */
    @Override
    public void run() {
        Log.d(TAG, "Camera thread start");

        Looper.prepare();
        synchronized (mReadyFence) {
            mHandler = new CameraHandler(this);
            cameraZoom = new CameraZoom(mWeakParent.get());
            cameraZoom.setCameraHandler(mHandler);
            mIsRunning = true;
            mReadyFence.notify();
        }
        Looper.loop();

        Log.d(TAG, "Camera thread finish");

        synchronized (mReadyFence) {
            mHandler = null;
            mIsRunning = false;
        }
    }

    public void updateFlashStatus() {
        final CameraGLView parent = mWeakParent.get();
        if (parent == null || parent.flashImageView == null || mCamera == null) {
            return;
        }

        Camera.Parameters parameters = mCamera.getParameters();

        List<String> flashModes = parameters.getSupportedFlashModes();

        if (flashModes == null || flashModes.isEmpty() || !flashModes.contains(FLASH_MODE_TORCH)) {
            mFlashMode = FlashMode.UNAVAILABLE;
        } else if (mFlashMode == FlashMode.UNAVAILABLE) {
            mFlashMode = FlashMode.OFF;
        }

        updateFlashImage(parent);
    }

    public void toggleCamera() {
        final CameraGLView parent = mWeakParent.get();
        if (parent == null || parent.cameraSwitcher == null) {
            return;
        }

        switch (parent.cameraId) {
            case CAMERA_FACING_BACK:
                parent.cameraId = CAMERA_FACING_FRONT;
                break;
            case CAMERA_FACING_FRONT:
                parent.cameraId = CAMERA_FACING_BACK;
                break;
            default:
                break;
        }

        if (updateCameraIcon()) {
            parent.post(parent::restartPreview);
        }
    }

    public void toggleFlash() {
        final CameraGLView parent = mWeakParent.get();
        if (parent == null || parent.flashImageView == null || mCamera == null) {
            return;
        }

        Camera.Parameters parameters = mCamera.getParameters();

        List<String> flashModes = parameters.getSupportedFlashModes();
        if (flashModes == null || flashModes.isEmpty()) {
            return;
        }

        if (mFlashMode == FlashMode.OFF && flashModes.contains(FLASH_MODE_TORCH)) {
            mFlashMode = FlashMode.TORCH;
        } else if (mFlashMode == FlashMode.TORCH) {
            mFlashMode = FlashMode.OFF;
        }

        updateFlashImage(parent);
    }

    public void onRecordingStart() {
        torchOn();
    }

    public void onRecordingStop() {
        torchOff();
    }

    public void torchOn() {
        if (mFlashMode != FlashMode.TORCH || mCamera == null) {
            return;
        }

        Camera.Parameters params = mCamera.getParameters();
        params.setFlashMode(FLASH_MODE_TORCH);
        mCamera.setParameters(params);
    }

    public void torchOff() {
        if (mFlashMode != FlashMode.TORCH || mCamera == null) {
            return;
        }

        Camera.Parameters params = mCamera.getParameters();
        params.setFlashMode(FLASH_MODE_OFF);
        mCamera.setParameters(params);
    }

    public boolean updateCameraIcon() {
        final CameraGLView parent = mWeakParent.get();
        if (parent == null || parent.cameraSwitcher == null) {
            return false;
        }

        parent.post(() -> {
            if (parent.cameraSwitcher == null) {
                return;
            }

            switch (parent.cameraId) {
                case CAMERA_FACING_BACK:
                    parent.cameraSwitcher.setImageResource(R.drawable.camera_front_white);
                    break;
                case CAMERA_FACING_FRONT:
                    parent.cameraSwitcher.setImageResource(R.drawable.camera_rear_white);
                    break;
                default:
                    break;
            }

            parent.cameraSwitcher.setVisibility(View.VISIBLE);
            Log.d(TAG, "mCameraSwitcher: visible: " + " front: " + (parent.cameraId == CAMERA_FACING_FRONT));
        });

        return true;
    }

    public void updateFlashImage(final CameraGLView parent) {
        parent.post(() -> {
            if (parent.flashImageView == null) {
                return;
            }

            switch (mFlashMode) {
                case FlashMode.ON:
                case FlashMode.AUTO:
                case FlashMode.TORCH:
                    parent.flashImageView.setImageResource(R.drawable.flash_on_white);
                    parent.flashImageView.setVisibility(View.VISIBLE);
                    break;
                case FlashMode.OFF:
                    parent.flashImageView.setImageResource(R.drawable.flash_off_white);
                    parent.flashImageView.setVisibility(View.VISIBLE);
                    break;
                case FlashMode.UNAVAILABLE:
                    parent.flashImageView.setVisibility(View.INVISIBLE);
                    break;
            }
        });
    }

    /**
     * start camera preview
     */
    public void startPreview(final int reqWidth, final int reqHeight) {
        Log.d(TAG, "startPreview:");

        final CameraGLView parent = mWeakParent.get();

        if ((parent == null) || (mCamera != null)) {
            return;
        }

        // This is a sample project so just use 0 as camera ID.
        // it is better to selecting camera is available
        try {
            mCamera = Camera.open(parent.cameraId);
            final Camera.Parameters params = mCamera.getParameters();

            final List<String> focusModes = params.getSupportedFocusModes();

            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            // let's try fastest frame rate. You will get near 60fps, but your device become hot.
            final List<int[]> supportedFpsRange = params.getSupportedPreviewFpsRange();

            if (supportedFpsRange != null) {
                //TODO: get 30fps rate
                final int n = supportedFpsRange.size();

                for (int i = 0; i < n; i++) {
                    int range[] = supportedFpsRange.get(i);
                    Log.d(TAG, String.format("supportedFpsRange(%d)=(%d,%d)", i, range[0], range[1]));
                }

                final int[] max_fps = supportedFpsRange.get(supportedFpsRange.size() - 1);
                Log.d(TAG, String.format("fps: %d-%d", max_fps[0], max_fps[1]));
                params.setPreviewFpsRange(max_fps[0], max_fps[1]);
            }

            params.setRecordingHint(true);
            final int width = Math.min(reqWidth, PREFERRED_PREVIEW_WIDTH);
            final int height = Math.min(reqHeight, PREFERRED_PREVIEW_HEIGHT);

            // request preview size
            // this is a sample project and just use fixed value
            // if you want to use other size, you also need to change the recording size.
            Log.d(TAG, "requested: width: " + reqWidth + " height: " + reqHeight
                    + " selected: width: " + width + " height: " + height);

            final Camera.Size closestSize = CameraHelper.getOptimalSize(
                    params.getSupportedPreviewSizes(), width, height);

            params.setPreviewSize(closestSize.width, closestSize.height);

            Log.d(TAG, String.format("closestSize(%d, %d)", closestSize.width, closestSize.height));

            final Camera.Size pictureSize = CameraHelper.getOptimalSize(
                    params.getSupportedPictureSizes(), width, height);

            params.setPictureSize(pictureSize.width, pictureSize.height);

            Log.d(TAG, String.format("pictureSize(%d, %d)", pictureSize.width, pictureSize.height));

            final int degrees = CameraHelper.getDisplayOrientation(parent.getContext(), parent.cameraId);
            mCamera.setDisplayOrientation(degrees);

            // apply rotation setting
            parent.mRotation = degrees;

            mCamera.setParameters(params);

            final Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
            Log.d(TAG, String.format("previewSize(%d, %d)", previewSize.width, previewSize.height));

            // adjust view size with keeping the aspect ration of camera preview.
            // here is not a UI thread and we should request parent view to execute.
            parent.post(() -> parent.setCameraPreviewSize(previewSize.width, previewSize.height));

            final SurfaceTexture st = parent.getSurfaceTexture();
            //noinspection ConstantConditions
            st.setDefaultBufferSize(previewSize.width, previewSize.height);
            mCamera.setPreviewTexture(st);
        } catch (final IOException | RuntimeException e) {
            Log.e(TAG, "startPreview:", e);
            cameraZoom.setCamera(null);

            if (mCamera != null) {
                mCamera.release();
                mCamera = null;
            }
        }

        if (mCamera != null) {
            // start camera preview display
            mCamera.startPreview();
            cameraZoom.setCamera(mCamera);
        }
    }

    /**
     * stop camera preview
     */
    public void stopPreview() {
        Log.d(TAG, "stopPreview:");

        cameraZoom.setCamera(null);

        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }

        final CameraGLView parent = mWeakParent.get();
        if (parent == null) return;
        parent.mCameraHandler = null;
    }
}