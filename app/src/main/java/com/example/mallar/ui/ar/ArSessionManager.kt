package com.example.mallar.ar

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 *  ArSessionManager  –  ARCore session configuration & lifecycle helper
 *  ────────────────────────────────────────────────────────────────────────────
 *  This companion object provides:
 *   • Session config (plane finding, light estimation, update mode)
 *   • ARCore availability checks
 *   • Tracking-state enum extension for readable logs / UI
 *
 *  SceneView's ARSceneView creates and owns the ARCore session internally.
 *  This file contains the configuration lambda you pass to
 *  ARSceneView.configureSession { session, config -> … }
 *  and utility extensions used across the AR navigation flow.
 * ═══════════════════════════════════════════════════════════════════════════════
 */

import android.content.Context
import android.util.Log
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk.Availability
import com.google.ar.core.exceptions.*

private const val TAG = "ArSessionManager"

// ─────────────────────────────────────────────────────────────────────────────
//  ARCore availability check
// ─────────────────────────────────────────────────────────────────────────────

/** Result of an ARCore availability check. */
sealed class ArAvailability {
    object Supported            : ArAvailability()
    object NotSupported         : ArAvailability()
    data class NeedsUpdate(val isUserActionRequired: Boolean) : ArAvailability()
    data class CheckFailed(val cause: Exception)              : ArAvailability()
}

/**
 * Checks whether ARCore is available and up-to-date on this device.
 *
 * Call from a ViewModel or Activity BEFORE launching [ArNavigationScreen].
 * SceneView will also request installation automatically, but checking
 * early lets you show a friendlier pre-check UI.
 */
fun checkArCoreAvailability(context: Context): ArAvailability {
    return try {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        Log.d(TAG, "ARCore availability: $availability")
        when (availability) {
            Availability.SUPPORTED_INSTALLED         -> ArAvailability.Supported
            Availability.SUPPORTED_APK_TOO_OLD,
            Availability.SUPPORTED_NOT_INSTALLED     -> ArAvailability.NeedsUpdate(isUserActionRequired = true)
            Availability.UNKNOWN_CHECKING            -> ArAvailability.NeedsUpdate(isUserActionRequired = false)
            Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
            Availability.UNKNOWN_ERROR,
            Availability.UNKNOWN_TIMED_OUT           -> ArAvailability.NotSupported
            else                                     -> ArAvailability.NotSupported
        }
    } catch (e: Exception) {
        Log.e(TAG, "ARCore availability check failed", e)
        ArAvailability.CheckFailed(e)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Session configuration
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Applies the recommended indoor-navigation ARCore session configuration.
 *
 * Pass this lambda to [ARSceneView.configureSession]:
 *
 * ```kotlin
 * ARSceneView(context).apply {
 *     configureSession(::configureArSessionForNavigation)
 * }
 * ```
 */
fun configureArSessionForNavigation(session: Session, config: Config) {
    // Detect only horizontal upward-facing planes (the floor).
    // HORIZONTAL includes both up and down; HORIZONTAL_UPWARD_FACING is
    // safer for indoor floor detection.
    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL

    // Environmental HDR provides real-time light estimation so 3-D arrows
    // match the ambient lighting of the mall, making them look grounded.
    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

    // LATEST_CAMERA_IMAGE processes the most recent frame rather than queuing,
    // which minimises latency on fast movement.
    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

    // Depth API (optional): if the device supports it, enable depth for
    // better plane detection in featureless indoor corridors.
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
        config.depthMode = Config.DepthMode.AUTOMATIC
        Log.d(TAG, "Depth API enabled")
    } else {
        config.depthMode = Config.DepthMode.DISABLED
    }

    // Focus mode: FIXED gives the best performance for a moving user; AUTO
    // is better if you also use the camera for logo scanning.
    config.focusMode = Config.FocusMode.AUTO

    Log.d(TAG, "ARCore session configured for indoor navigation")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tracking state extensions
// ─────────────────────────────────────────────────────────────────────────────

/** Human-readable label for use in logs and debug overlays. */
val TrackingState.label: String get() = when (this) {
    TrackingState.TRACKING          -> "TRACKING"
    TrackingState.PAUSED            -> "PAUSED"
    TrackingState.STOPPED           -> "STOPPED"
}

/** True if the AR session has a valid tracking state and arrows should update. */
val TrackingState.isActive: Boolean get() = this == TrackingState.TRACKING

// ─────────────────────────────────────────────────────────────────────────────
//  Frame helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Extracts the camera's world-space translation from the latest AR [Frame].
 * Returns null if the camera is not currently tracking.
 */
fun Frame.cameraWorldTranslation(): FloatArray? {
    if (camera.trackingState != TrackingState.TRACKING) return null
    return camera.pose.translation
}

/**
 * Performs a hit-test at the given screen-space pixel and returns the first
 * horizontal-plane hit result, or null if none.
 */
fun Frame.hitTestFloor(screenX: Float, screenY: Float): HitResult? =
    hitTest(screenX, screenY).firstOrNull { hit ->
        val t = hit.trackable
        t is Plane &&
                t.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                t.isPoseInPolygon(hit.hitPose)
    }