package com.google.ar.core.examples.java.persistentcloudanchor;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;

import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.CloudAnchorMode;
import com.google.ar.core.Earth;
import com.google.ar.core.Frame;
import com.google.ar.core.GeospatialPose;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Session.FeatureMapQuality;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.LocationPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer;
import com.google.ar.core.examples.java.common.rendering.PlaneRenderer;
import com.google.ar.core.examples.java.common.rendering.PointCloudRenderer;
import com.google.ar.core.examples.java.persistentcloudanchor.PrivacyNoticeDialogFragment.HostResolveListener;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.common.base.Preconditions;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

enum State {
    UNINITIALIZED,
    EARTH_STATE_ERROR,
    PRETRACKING,
    LOCALIZING,
    LOCALIZED
}

/**
 * Main Activity for the Persistent Cloud Anchor Sample.
 *
 * <p>This is a simple example that shows how to host and resolve anchors using ARCore Cloud Anchors
 * API calls. This app only has at most one anchor at a time, to focus more on the cloud aspect of
 * anchors.
 */
public class CloudAnchorActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final String TAG = CloudAnchorActivity.class.getSimpleName();
    private static final String QUALITY_INSUFFICIENT_STRING = "INSUFFICIENT";
    private static final String ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES";
    protected static final String PREFERENCE_FILE_KEY = "CLOUD_ANCHOR_PREFERENCES";
    protected static final String HOSTED_ANCHOR_IDS = "anchor_ids";
    protected static final double MIN_DISTANCE = 0.2f;
    protected static final double MAX_DISTANCE = 10.0f;

    private static final float LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS = 10;
    private static final float LOCALIZING_HEADING_ACCURACY_THRESHOLD_DEGREES = 50;

    private static final double LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS = 10;
    private static final double LOCALIZED_HEADING_ACCURACY_HYSTERESIS_DEGREES = 10;


    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private GLSurfaceView surfaceView;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final ObjectRenderer anchorObject = new ObjectRenderer();
    private final ObjectRenderer featureMapQualityBarObject = new ObjectRenderer();
    private final PlaneRenderer planeRenderer = new PlaneRenderer();
    private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    private boolean installRequested;

    // Temporary matrices allocated here to reduce number of allocations for each frame.
    private final float[] anchorMatrix = new float[16];
    private final float[] viewMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];
    private final float[] anchorTranslation = new float[4];

    // Locks needed for synchronization
    private final Object singleTapLock = new Object();
    private final Object anchorLock = new Object();

    // Tap handling and UI.
    private GestureDetector gestureDetector;
    private DisplayRotationHelper displayRotationHelper;
    private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
    private TextView debugText;
    private TextView userMessageText;
    private SharedPreferences sharedPreferences;

    // Feature Map Quality Indicator UI
    private FeatureMapQualityUi featureMapQualityUi;
    private static final float QUALITY_THRESHOLD = 0.6f;
    private Pose anchorPose;
    private boolean hostedAnchor;
    private long lastEstimateTimestampMillis;

    @GuardedBy("singleTapLock")
    private MotionEvent queuedSingleTap;

    private Session session;
    private State state = State.UNINITIALIZED;
    private boolean qualityObtained = false;

    @GuardedBy("anchorLock")
    private Anchor anchor;

    private CloudAnchorManager cloudAnchorManager;

    private static int getNumStoredAnchors(@NonNull SharedPreferences anchorPreferences) {
        String hostedAnchorIds = anchorPreferences.getString(CloudAnchorActivity.HOSTED_ANCHOR_IDS, "");
        return hostedAnchorIds.split(";", -1).length - 1;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.cloud_anchor);
        surfaceView = findViewById(R.id.surfaceview);
        displayRotationHelper = new DisplayRotationHelper(this);
        setUpTapListener();
        // Set up renderer.
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);
        installRequested = false;

        // Initialize UI components.
        debugText = findViewById(R.id.debug_message);
        userMessageText = findViewById(R.id.user_message);

        showPrivacyDialog();
    }

    private void setUpTapListener() {
        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        synchronized (singleTapLock) {
                            queuedSingleTap = e;
                        }
                        return true;
                    }

                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }
                });
        surfaceView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private void showPrivacyDialog() {
        sharedPreferences = getSharedPreferences(PREFERENCE_FILE_KEY, Context.MODE_PRIVATE);

        if (!sharedPreferences.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
            showNoticeDialog(this::onPrivacyAcceptedForHost);
        } else {
            onPrivacyAcceptedForHost();
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }

        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sharedPreferences.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
            createSession();
        }
        surfaceView.onResume();
        displayRotationHelper.onResume();
    }

    private void createSession() {
        if (session == null) {
            Exception exception = null;
            int messageId = -1;
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }
                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (CameraPermissionHelper.noCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }

                if (LocationPermissionHelper.noFineLocationPermission(this)) {
                    LocationPermissionHelper.requestFineLocationPermission(this);
                    return;
                }

                session = new Session(this);
                cloudAnchorManager = new CloudAnchorManager(session);
            } catch (UnavailableArcoreNotInstalledException e) {
                messageId = R.string.arcore_unavailable;
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                messageId = R.string.arcore_too_old;
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                messageId = R.string.arcore_sdk_too_old;
                exception = e;
            } catch (Exception e) {
                messageId = R.string.arcore_exception;
                exception = e;
            }

            if (exception != null) {
                userMessageText.setText(messageId);
                debugText.setText(messageId);
                Log.e(TAG, "Exception creating session", exception);
                return;
            }

            // Create default config and check if supported.
            Config config = new Config(session);
            config.setCloudAnchorMode(CloudAnchorMode.ENABLED);
            config.setGeospatialMode(Config.GeospatialMode.ENABLED);
            session.configure(config);

            state = State.PRETRACKING;
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            userMessageText.setText(R.string.camera_unavailable);
            debugText.setText(R.string.camera_unavailable);
            session = null;
            cloudAnchorManager = null;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);

        if (CameraPermissionHelper.noCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }

        if (LocationPermissionHelper.hasFineLocationPermissionsResponseInResult(permissions)
                && LocationPermissionHelper.noFineLocationPermission(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(
                            this,
                            "Precise location permission is needed to run this application",
                            Toast.LENGTH_LONG)
                    .show();
            if (!LocationPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                LocationPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
    }

    /**
     * Handles the most recent user tap.
     *
     * <p>We only ever handle one tap at a time, since this app only allows for a single anchor.
     *
     * @param frame               the current AR frame
     * @param cameraTrackingState the current camera tracking state
     */
    private void handleTap(Frame frame, TrackingState cameraTrackingState) {
        // Handle taps. Handling only one tap per frame, as taps are usually low frequency
        // compared to frame rate.
        synchronized (singleTapLock) {
            synchronized (anchorLock) {
                // Only handle a tap if the anchor is currently null, the queued tap is non-null and the
                // camera is currently tracking.
                if (anchor == null && queuedSingleTap != null
                        && cameraTrackingState == TrackingState.TRACKING) {

                    for (HitResult hit : frame.hitTest(queuedSingleTap)) {
                        if (shouldCreateAnchorWithHit(hit)) {
                            debugText.setText(
                                    getString(R.string.debug_hosting_save, QUALITY_INSUFFICIENT_STRING));
                            // Create an anchor using a hit test with plane
                            Anchor newAnchor = hit.createAnchor();
                            Plane plane = (Plane) hit.getTrackable();
                            if (plane.getType() == Plane.Type.VERTICAL) {
                                featureMapQualityUi =
                                        FeatureMapQualityUi.createVerticalFeatureMapQualityUi(
                                                featureMapQualityBarObject);
                            } else {
                                featureMapQualityUi =
                                        FeatureMapQualityUi.createHorizontalFeatureMapQualityUi(
                                                featureMapQualityBarObject);
                            }
                            setNewAnchor(newAnchor);
                            break; // Only handle the first valid hit.
                        }
                    }
                }
            }
            queuedSingleTap = null;
        }
    }

    /**
     * Returns {@code true} if and only if the hit can be used to create an Anchor reliably.
     */
    private static boolean shouldCreateAnchorWithHit(@NonNull HitResult hit) {
        Trackable trackable = hit.getTrackable();
        if (trackable instanceof Plane) {
            // Check if the hit was within the plane's polygon.
            return ((Plane) trackable).isPoseInPolygon(hit.getHitPose());
        }
        return false;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(this);
            planeRenderer.createOnGlThread(this, "models/trigrid.png");
            pointCloudRenderer.createOnGlThread(this);

            anchorObject.createOnGlThread(this, "models/anchor.obj", "models/anchor.png");
            anchorObject.setMaterialProperties(0.0f, 0.75f, 0.1f, 0.5f);

            featureMapQualityBarObject.createOnGlThread(
                    this, "models/map_quality_bar.obj", "models/map_quality_bar.png");
            featureMapQualityBarObject.setMaterialProperties(0.0f, 2.0f, 0.02f, 0.5f);

        } catch (IOException ex) {
            Log.e(TAG, "Failed to read an asset file", ex);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            Frame frame = session.update();
            Camera camera = frame.getCamera();
            TrackingState cameraTrackingState = camera.getTrackingState();

            // Notify the cloudAnchorManager of all the updates.
            cloudAnchorManager.onUpdate();

            // Handle user input.
            handleTap(frame, cameraTrackingState);

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame);

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

            // If not tracking, don't draw 3d objects.
            if (cameraTrackingState == TrackingState.PAUSED) {
                return;
            }

            // Get camera and projection matrices.
            camera.getViewMatrix(viewMatrix, 0);
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

            // Visualize tracked points.
            // Use try-with-resources to automatically release the point cloud.
            try (PointCloud pointCloud = frame.acquirePointCloud()) {
                pointCloudRenderer.update(pointCloud);
                pointCloudRenderer.draw(viewMatrix, projectionMatrix);
            }

            float[] colorCorrectionRgba = new float[4];
            float scaleFactor = 1.0f;
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);
            boolean shouldDrawFeatureMapQualityUi = false;
            synchronized (anchorLock) {
                if (anchor == null) {
                    // Visualize planes.
                    planeRenderer.drawPlanes(
                            session.getAllTrackables(Plane.class),
                            camera.getDisplayOrientedPose(),
                            projectionMatrix);
                }
                // Update the pose of the anchor (to be) hosted if it can be drawn and render the anchor.
                if (anchor != null && anchor.getTrackingState() == TrackingState.TRACKING) {
                    anchorPose = anchor.getPose();
                    anchorPose.toMatrix(anchorMatrix, 0);
                    anchorPose.getTranslation(anchorTranslation, 0);
                    anchorTranslation[3] = 1.0f;
                    drawAnchor(anchorMatrix, scaleFactor, colorCorrectionRgba);

                    if (!hostedAnchor && featureMapQualityUi != null) {
                        shouldDrawFeatureMapQualityUi = true;
                    }
                }
            }

            Earth earth = session.getEarth();
            if (earth != null) {
                updateGeospatialState(earth);
            }

            // Render the Feature Map Quality Indicator UI.
            // Adaptive UI is drawn here, using the values from the mapping quality API.
            if (shouldDrawFeatureMapQualityUi) {
                updateFeatureMapQualityUi(camera, colorCorrectionRgba, earth);
            }

        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    private void updatePretrackingState(@NonNull Earth earth) {
        if (earth.getTrackingState() == TrackingState.TRACKING) {
            state = State.LOCALIZING;
            return;
        }

        if (earth.getEarthState() != Earth.EarthState.ENABLED) {
            state = State.EARTH_STATE_ERROR;
            return;
        }

        runOnUiThread(() -> userMessageText.setText(R.string.geospatial_pose_not_tracking));
    }

    private void updateLocalizingState(@NonNull Earth earth) {
        GeospatialPose geospatialPose = earth.getCameraGeospatialPose();

        if (geospatialPose.getHorizontalAccuracy() <= LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS
                && geospatialPose.getHeadingAccuracy() <= LOCALIZING_HEADING_ACCURACY_THRESHOLD_DEGREES) {
            state = State.LOCALIZED;

            runOnUiThread(() -> userMessageText.setText(R.string.hosting_place_anchor));
            return;
        }

        updateGeospatialPoseText(geospatialPose);
    }

    private void updateGeospatialState(Earth earth) {
        if (state == State.PRETRACKING) {
            updatePretrackingState(earth);
        } else if (state == State.LOCALIZING) {
            updateLocalizingState(earth);
        } else if (state == State.LOCALIZED) {
            updateLocalizedState(earth);
        }
    }

    private void updateGeospatialPoseText(@NonNull GeospatialPose geospatialPose) {
        String poseText = getResources().getString(
                R.string.geospatial_pose,
                geospatialPose.getLatitude(),
                geospatialPose.getLongitude(),
                geospatialPose.getHorizontalAccuracy(),
                geospatialPose.getAltitude(),
                geospatialPose.getVerticalAccuracy(),
                geospatialPose.getHeading(),
                geospatialPose.getHeadingAccuracy());

        runOnUiThread(() -> debugText.setText(poseText));
    }

    private void updateLocalizedState(@NonNull Earth earth) {
        GeospatialPose geospatialPose = earth.getCameraGeospatialPose();
        // Check if either accuracy has degraded to the point we should enter back into the LOCALIZING
        // state.
        if (geospatialPose.getHorizontalAccuracy()
                > LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS
                + LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS
                || geospatialPose.getHeadingAccuracy()
                > LOCALIZING_HEADING_ACCURACY_THRESHOLD_DEGREES
                + LOCALIZED_HEADING_ACCURACY_HYSTERESIS_DEGREES) {
            // Accuracies have degenerated, return to the localizing state.
            state = State.LOCALIZING;

            runOnUiThread(() -> runOnUiThread(() -> userMessageText.setText(R.string.geospatial_pose_not_tracking)));
        }
    }

    private void updateFeatureMapQualityUi(@NonNull Camera camera, float[] colorCorrectionRgba, Earth earth) {
        Pose featureMapQualityUiPose = anchorPose.compose(featureMapQualityUi.getUiTransform());
        float[] cameraUiFrame =
                featureMapQualityUiPose.inverse().compose(camera.getPose()).getTranslation();
        double distance = Math.hypot(/*dx=*/ cameraUiFrame[0], /*dz=*/ cameraUiFrame[2]);

        if (!qualityObtained)
            runOnUiThread(
                    () -> {
                        if (distance < MIN_DISTANCE) {
                            userMessageText.setText(R.string.too_close);
                        } else if (distance > MAX_DISTANCE) {
                            userMessageText.setText(R.string.too_far);
                        } else {
                            userMessageText.setText(R.string.hosting_save);
                        }
                    });

        long now = SystemClock.uptimeMillis();
        if (now - lastEstimateTimestampMillis > 500
                && FeatureMapQualityUi.isAnchorInView(anchorTranslation, viewMatrix, projectionMatrix)) {
            lastEstimateTimestampMillis = now;
            FeatureMapQuality currentQuality =
                    session.estimateFeatureMapQualityForHosting(camera.getPose());
            featureMapQualityUi.updateQualityForViewpoint(cameraUiFrame, currentQuality);
            float averageQuality = featureMapQualityUi.computeOverallQuality();
            Log.i(TAG, "History of average mapping quality calls: " + averageQuality);

            qualityObtained = averageQuality >= QUALITY_THRESHOLD;

            if (qualityObtained && state == State.LOCALIZED) {

                // Host the anchor automatically if the FeatureMapQuality threshold is reached.
                Log.i(TAG, "FeatureMapQuality has reached SUFFICIENT-GOOD, triggering hostCloudAnchor()");
                synchronized (anchorLock) {
                    hostedAnchor = true;
                    cloudAnchorManager.hostCloudAnchor(anchor, new HostListener(earth.getCameraGeospatialPose()));
                }
                runOnUiThread(
                        () -> {
                            userMessageText.setText(R.string.hosting_processing);
                            debugText.setText(R.string.debug_hosting_processing);
                        });
            }
        }

        // Render the mapping quality UI.
        featureMapQualityUi.drawUi(anchorPose, viewMatrix, projectionMatrix, colorCorrectionRgba);
    }

    private void drawAnchor(float[] anchorMatrix, float scaleFactor, float[] colorCorrectionRgba) {
        anchorObject.updateModelMatrix(anchorMatrix, scaleFactor);
        anchorObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba);
    }

    /**
     * Sets the new value of the current anchor. Detaches the old anchor, if it was non-null.
     */
    private void setNewAnchor(Anchor newAnchor) {
        synchronized (anchorLock) {
            if (anchor != null) {
                anchor.detach();
            }
            anchor = newAnchor;
        }
    }

    /**
     * Callback function invoked when the privacy notice is accepted.
     */
    private void onPrivacyAcceptedForHost() {
        if (!sharedPreferences.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true).commit()) {
            throw new AssertionError("Could not save the user preference to SharedPreferences!");
        }
        createSession();
    }

    /* Listens for a hosted anchor. */
    private final class HostListener implements CloudAnchorManager.CloudAnchorListener {
        private String cloudAnchorId;
        private final GeospatialPose geospatialPose;

        public HostListener(GeospatialPose geospatialPose) {
            this.geospatialPose = geospatialPose;
        }

        @Override
        public void onComplete(Anchor anchor) {
            runOnUiThread(
                    () -> {
                        CloudAnchorState state = anchor.getCloudAnchorState();
                        if (state.isError()) {
                            Log.e(TAG, "Error hosting a cloud anchor, state " + state);
                            userMessageText.setText(getString(R.string.hosting_error, state));
                            return;
                        }
                        Preconditions.checkState(
                                cloudAnchorId == null, "The cloud anchor ID cannot have been set before.");
                        cloudAnchorId = anchor.getCloudAnchorId();
                        setNewAnchor(anchor);
                        Log.i(TAG, "Anchor " + cloudAnchorId + " created.");
                        userMessageText.setText(getString(R.string.hosting_success));
                        debugText.setText(getString(R.string.debug_hosting_success, cloudAnchorId));
                        saveAnchorWithNickname();
                    });
        }

        /**
         * Callback function invoked when the user presses the OK button in the Save Anchor Dialog.
         */
        private void onAnchorNameEntered(String anchorNickname) {
            userMessageText.setVisibility(View.GONE);

            Map<String, Object> point = new HashMap<>();

            point.put("anchorId", cloudAnchorId);
            point.put("altitude", geospatialPose.getAltitude());
            point.put("latitude", geospatialPose.getLatitude());
            point.put("longitude", geospatialPose.getLongitude());
            point.put("heading", geospatialPose.getHeading());
            point.put("name", anchorNickname);

            db.collection("maps/fisat/poi").add(point)
                    .addOnSuccessListener((d) -> debugText.setText(getString(R.string.debug_hosting_success, anchorNickname)))
                    .addOnFailureListener((e) -> debugText.setText(e.getLocalizedMessage()));
        }

        private void saveAnchorWithNickname() {
            if (cloudAnchorId == null) {
                return;
            }
            HostDialogFragment hostDialogFragment = new HostDialogFragment();
            // Supply num input as an argument.
            Bundle args = new Bundle();
            args.putString(
                    "nickname", getString(R.string.nickname_default, getNumStoredAnchors(sharedPreferences)));
            hostDialogFragment.setOkListener(this::onAnchorNameEntered);
            hostDialogFragment.setArguments(args);
            hostDialogFragment.show(getSupportFragmentManager(), "HostDialog");
        }
    }

    public void showNoticeDialog(HostResolveListener listener) {
        DialogFragment dialog = PrivacyNoticeDialogFragment.createDialog(listener);
        dialog.show(getSupportFragmentManager(), PrivacyNoticeDialogFragment.class.getName());
    }
}
