package com.example.mallar.ar

import com.example.mallar.data.GraphNode
import kotlin.math.*

// ── Scale ─────────────────────────────────────────────────────────────────────
const val AR_SCALE: Float = 0.05f

// ── Arrow placement ───────────────────────────────────────────────────────────
const val ARROW_Y_OFFSET: Float  = 0.05f
const val ARROW_SPACING_M: Float = 1.0f

// ─────────────────────────────────────────────────────────────────────────────
data class ArPosition(val x: Float, val y: Float, val z: Float)

data class PathProjectionResult(
    val distanceAlongPath: Float,   // meters from path start to projected point
    val closestSegmentIndex: Int,
    val deviationDistance: Float    // perpendicular distance off path (meters)
)

// ── Real-time orientation guidance ──────────────────────────────────────────────
/**
 * TurnHint is derived from the angle between the camera’s current forward
 * vector and the direction to the next waypoint.
 * It is INDEPENDENT of compass heading or forced rotation.
 */
enum class TurnHint { STRAIGHT, LEFT, RIGHT }

/**
 * Computed every frame in AR world (anchor-local) space.
 *
 * [angleDeg]        – angle between camera forward and path direction (0–180°)
 * [turnHint]        – STRAIGHT / LEFT / RIGHT tells the HUD which way to guide
 * [pathYRotDeg]     – the world-Y rotation that makes an arrow point along the path;
 *                     use this to orient any floating guidance arrow in AR
 */
data class OrientationGuidance(
    val angleDeg:    Float,
    val turnHint:    TurnHint,
    val pathYRotDeg: Float
)

data class ArrowPlacement(
    val position: ArPosition,
    val yRotationDeg: Float,
    val segmentIndex: Int
)

data class StartPin(
    val position: ArPosition
)

// ─────────────────────────────────────────────────────────────────────────────
/**
 * Low-pass filter for angles (handles wraparound at ±180°).
 * Alpha near 1.0 = heavy smoothing (slow to update).
 * Alpha near 0.0 = no smoothing (follows input immediately).
 */
class AngleLowPassFilter(private val alpha: Float = 0.94f) {
    private var filtered: Double? = null

    fun filter(newDeg: Float): Float {
        val curr = filtered
        if (curr == null) {
            filtered = newDeg.toDouble()
            return newDeg
        }
        var delta = newDeg - curr
        while (delta > 180)  delta -= 360
        while (delta < -180) delta += 360
        filtered = curr + (1.0 - alpha) * delta
        return filtered!!.toFloat()
    }

    fun reset() { filtered = null }
}

// ─────────────────────────────────────────────────────────────────────────────
/**
 * Kalman-style scalar filter for heading drift correction.
 * Provides smoother convergence than a fixed-alpha low-pass filter.
 */
class HeadingKalmanFilter(
    private val processNoise: Float = 0.01f,   // Q: how much the true heading can drift per step
    private val measurementNoise: Float = 5f   // R: how noisy the compass reading is (degrees²)
) {
    private var estimate: Float? = null
    private var errorCovariance: Float = 1f

    fun filter(measured: Float): Float {
        val est = estimate
        if (est == null) {
            estimate = measured
            return measured
        }

        // Predict step: error grows by process noise
        val predictedError = errorCovariance + processNoise

        // Update step: Kalman gain
        val kalmanGain = predictedError / (predictedError + measurementNoise)

        // Shortest-path delta for angle wrap
        var delta = measured - est
        while (delta > 180f)  delta -= 360f
        while (delta < -180f) delta += 360f

        val newEstimate = est + kalmanGain * delta
        errorCovariance = (1f - kalmanGain) * predictedError

        estimate = (newEstimate + 360f) % 360f
        return estimate!!
    }

    fun reset() {
        estimate = null
        errorCovariance = 1f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
class ArCoordinateTransformer(
    private val startNode: GraphNode,
    private val scale: Float = AR_SCALE,
    var headingOffsetDeg: Float = 0f
) {
    /**
     * Low-pass filter for live compass readings used for drift correction.
     * Only active after manual calibration; does NOT override the locked offset.
     */
    private val compassFilter = AngleLowPassFilter(alpha = 0.94f)

    /**
     * Kalman filter for smooth drift correction during navigation.
     * Applied at a low rate so it never fights the manual calibration.
     */
    private val kalmanFilter = HeadingKalmanFilter(processNoise = 0.005f, measurementNoise = 8f)

    /**
     * True once the user has completed manual direction calibration.
     * When locked, automatic drift correction is applied only very gently.
     */
    var isManuallyCalibrated: Boolean = false
        private set

    /**
     * The heading captured at manual calibration time (device forward direction).
     * Stored for debug display purposes.
     */
    var manualCapturedDeviceHeadingDeg: Float = 0f
        private set

    /**
     * The true GPS bearing to the next waypoint, stored at calibration time.
     * Stored for debug display purposes.
     */
    var manualTrueBearingDeg: Float = 0f
        private set

    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Perform manual calibration.
     *
     * Called when the user taps "Confirm Direction" while pointing their phone
     * toward the real-world destination direction.
     *
     * [deviceHeadingDeg]  – current compass azimuth (0–360°, clockwise from north).
     * [trueBearingDeg]    – GPS-derived bearing from start node to next waypoint.
     *
     * Computes: offset = trueBearing − deviceHeading
     * and locks it as the world-alignment reference.
     */
    fun applyManualCalibration(deviceHeadingDeg: Float, trueBearingDeg: Float) {
        manualCapturedDeviceHeadingDeg = deviceHeadingDeg
        manualTrueBearingDeg           = trueBearingDeg
        headingOffsetDeg               = shortestAngleDelta(deviceHeadingDeg, trueBearingDeg)
        isManuallyCalibrated           = true

        // Seed the Kalman filter with the locked offset so future drift corrections
        // start from the correct baseline rather than fighting the manual value.
        kalmanFilter.reset()
        compassFilter.reset()
    }

    /**
     * Feed a raw compass reading during navigation.
     *
     * Returns the Kalman-filtered heading (for debug display).
     * When [isManuallyCalibrated] is true the heading offset is only adjusted
     * at a very slow rate (DRIFT_ALPHA) so manual alignment is preserved.
     */
    fun updateCompassHeading(rawDeg: Float): Float {
        val lpFiltered = compassFilter.filter(rawDeg)
        val kFiltered  = kalmanFilter.filter(lpFiltered)

        if (isManuallyCalibrated) {
            // Gentle continuous drift correction — blend 0.5% per reading.
            // At ~50 Hz this gives a ~40-second convergence time constant.
            val desiredOffset = shortestAngleDelta(kFiltered, manualTrueBearingDeg)
            val delta = shortestAngleDelta(headingOffsetDeg, desiredOffset)
            headingOffsetDeg += delta * DRIFT_ALPHA
        }
        return kFiltered
    }

    // ─────────────────────────────────────────────────────────────────────────

    fun toArLocal(node: GraphNode): ArPosition {
        val rawX =  (node.x.toFloat() - startNode.x.toFloat()) * scale
        val rawZ = -(node.y.toFloat() - startNode.y.toFloat()) * scale

        val rad  = Math.toRadians(headingOffsetDeg.toDouble())
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()

        return ArPosition(
            x =  rawX * cosR - rawZ * sinR,
            y = ARROW_Y_OFFSET,
            z =  rawX * sinR + rawZ * cosR
        )
    }

    fun computeArrowPlacements(pathNodes: List<GraphNode>): List<ArrowPlacement> {
        if (pathNodes.size < 2) return emptyList()
        val result = mutableListOf<ArrowPlacement>()

        for (segIdx in 0 until pathNodes.size - 1) {
            val p1 = toArLocal(pathNodes[segIdx])
            val p2 = toArLocal(pathNodes[segIdx + 1])

            val segDX = p2.x - p1.x
            val segDZ = p2.z - p1.z
            val segLen = sqrt(segDX * segDX + segDZ * segDZ)
            if (segLen < 0.001f) continue

            val dirX = segDX / segLen
            val dirZ = segDZ / segLen

            val bearingDeg = Math.toDegrees(
                atan2(dirX.toDouble(), -dirZ.toDouble())
            ).toFloat()

            val arrowRotation = (bearingDeg + ARROW_GLB_FORWARD_OFFSET + 360f) % 360f

            val arrowCount = (segLen / ARROW_SPACING_M).toInt().coerceAtLeast(1)

            for (k in 0 until arrowCount) {
                if (segIdx == 0 && k == 0) continue   // skip first to avoid start-pin overlap
                val t = (k + 0.5f) * ARROW_SPACING_M
                result += ArrowPlacement(
                    position = ArPosition(
                        x = p1.x + dirX * t,
                        y = ARROW_Y_OFFSET,
                        z = p1.z + dirZ * t
                    ),
                    yRotationDeg = arrowRotation,
                    segmentIndex = segIdx
                )
            }
        }
        return result
    }

    fun nodePathDistances(pathNodes: List<GraphNode>): List<Float> {
        val dists = mutableListOf(0f)
        for (i in 1 until pathNodes.size) {
            val p1 = toArLocal(pathNodes[i - 1])
            val p2 = toArLocal(pathNodes[i])
            val dx = p2.x - p1.x
            val dz = p2.z - p1.z
            dists += dists.last() + sqrt(dx * dx + dz * dz)
        }
        return dists
    }

    /**
     * Project (userX, userZ) onto the path and return:
     *  - distanceAlongPath : metres from path start to the projected point
     *  - closestSegmentIndex : which segment the projection falls on
     *  - deviationDistance : perpendicular metres off the path
     *
     * Uses segment parameterisation (not nearest node) so arrows update
     * smoothly and deviation is measured correctly.
     */
    fun projectOntoPath(userX: Float, userZ: Float, pathNodes: List<GraphNode>): PathProjectionResult {
        if (pathNodes.size < 2) return PathProjectionResult(0f, 0, Float.MAX_VALUE)

        val arPositions = pathNodes.map { toArLocal(it) }
        var bestDeviation = Float.MAX_VALUE
        var bestPathDist  = 0f
        var bestSegIdx    = 0
        var accDist       = 0f

        for (i in 0 until arPositions.size - 1) {
            val ax = arPositions[i].x;     val az = arPositions[i].z
            val bx = arPositions[i + 1].x; val bz = arPositions[i + 1].z
            val abx = bx - ax;             val abz = bz - az
            val abLen = sqrt(abx * abx + abz * abz)
            if (abLen < 0.001f) { accDist += abLen; continue }

            val apx = userX - ax; val apz = userZ - az
            val t   = ((apx * abx + apz * abz) / (abLen * abLen)).coerceIn(0f, 1f)

            val closestX = ax + t * abx
            val closestZ = az + t * abz
            val devX     = userX - closestX
            val devZ     = userZ - closestZ
            val dev      = sqrt(devX * devX + devZ * devZ)

            if (dev < bestDeviation) {
                bestDeviation = dev
                bestPathDist  = accDist + t * abLen
                bestSegIdx    = i
            }
            accDist += abLen
        }
        return PathProjectionResult(bestPathDist, bestSegIdx, bestDeviation)
    }

    /**
     * Compute real-time orientation guidance every AR frame.
     *
     * [camFwdX / camFwdZ]  – horizontal camera forward in anchor-local AR space.
     *                         Derived from anchor-local camera pose: forward = -Z axis.
     * [nextWaypointAR]     – AR position of the next waypoint on the path.
     * [userArX / userArZ]  – user's current AR position.
     *
     * Algorithm:
     *   1. Build path direction vector  D = normalize(nextWaypoint - userPos)
     *   2. Normalize camera forward    C = normalize(camFwd)
     *   3. dot   = C · D           → cos of angle between them
     *   4. angle = acos(dot)       → 0° = aligned, 180° = facing backwards
     *   5. crossY = C.z * D.x − C.x * D.z   (Y component of C × D)
     *        crossY > 0 → path is to the RIGHT of camera → turn RIGHT
     *        crossY < 0 → path is to the LEFT  of camera → turn LEFT
     *
     * IMPORTANT: compass and headingOffsetDeg are NOT touched.
     * AR arrows on the floor always face the path direction (pathYRotDeg).
     * This function only tells the HUD what to display.
     */
    fun computeOrientationGuidance(
        camFwdX: Float, camFwdZ: Float,
        nextWaypointAR: ArPosition,
        userArX: Float, userArZ: Float
    ): OrientationGuidance {
        // ── Path direction vector ─────────────────────────────────────────────
        val pdx = nextWaypointAR.x - userArX
        val pdz = nextWaypointAR.z - userArZ
        val pLen = sqrt(pdx * pdx + pdz * pdz)
        if (pLen < 0.01f) return OrientationGuidance(0f, TurnHint.STRAIGHT, 0f)
        val pathX = pdx / pLen
        val pathZ = pdz / pLen

        // ── Camera forward (normalized) ───────────────────────────────────────
        val cLen = sqrt(camFwdX * camFwdX + camFwdZ * camFwdZ)
        if (cLen < 0.01f) return OrientationGuidance(0f, TurnHint.STRAIGHT, 0f)
        val cfX = camFwdX / cLen
        val cfZ = camFwdZ / cLen

        // ── Angle between camera forward and path direction ───────────────────
        val dot      = (cfX * pathX + cfZ * pathZ).coerceIn(-1f, 1f)
        val angleDeg = Math.toDegrees(acos(dot.toDouble())).toFloat()

        // ── Cross product Y: determines left vs right ─────────────────────────
        // (C × D).y = C.z * D.x − C.x * D.z
        // positive → path is to the RIGHT of camera forward
        val crossY = cfZ * pathX - cfX * pathZ

        val hint = when {
            angleDeg < ORIENTATION_STRAIGHT_DEG -> TurnHint.STRAIGHT
            crossY > 0f                         -> TurnHint.RIGHT
            else                                -> TurnHint.LEFT
        }

        // ── Path-aligned Y rotation for a floating guidance arrow in AR ───────
        // This makes the arrow point along the path (same formula as floor arrows).
        val bearingRad = atan2(pathX.toDouble(), (-pathZ).toDouble())
        val pathYRot   = ((Math.toDegrees(bearingRad).toFloat()) + ARROW_GLB_FORWARD_OFFSET + 360f) % 360f

        return OrientationGuidance(angleDeg, hint, pathYRot)
    }

    fun distanceFromCamera(cameraTx: Float, cameraTz: Float, target: ArPosition): Float {
        val dx = cameraTx - target.x
        val dz = cameraTz - target.z
        return sqrt(dx * dx + dz * dz)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Signed shortest-path delta: how much to rotate [from] to reach [to].
     * Result is in (−180, +180].
     */
    private fun shortestAngleDelta(from: Float, to: Float): Float {
        var d = to - from
        while (d > 180f)  d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    companion object {
        const val ARROW_GLB_FORWARD_OFFSET = 180f

        /**
         * Fraction of drift error corrected per compass reading during navigation.
         * ~0.005 at 50 Hz ≈ 40-second full-correction time constant.
         * Kept very small so manual calibration is effectively locked.
         */
        const val DRIFT_ALPHA = 0.005f

        /**
         * Within this angle (camera forward vs path direction) the user is
         * considered to be facing the correct direction → TurnHint.STRAIGHT.
         * Below 20° feels natural for walking; increase to 30° for looser guidance.
         */
        const val ORIENTATION_STRAIGHT_DEG = 20f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
fun quaternionAroundY(angleDeg: Float): FloatArray {
    val half = Math.toRadians(angleDeg.toDouble()) / 2.0
    return floatArrayOf(0f, sin(half).toFloat(), 0f, cos(half).toFloat())
}

// ─────────────────────────────────────────────────────────────────────────────
/**
 * Compute the GPS bearing (degrees, clockwise from true north) from
 * [startNode] to [endNode] using the Haversine formula.
 *
 * Node coordinates are assumed to be in pixel/map space, NOT lat/lon.
 * For a flat mall map the bearing is the simple 2D angle:
 *   bearing = atan2(Δx, −Δy) converted to 0–360°.
 *
 * If your GraphNode stores real lat/lon, replace this with a proper
 * Haversine bearing calculation.
 */
fun computeMapBearing(startNode: GraphNode, endNode: GraphNode): Float {
    val dx = (endNode.x - startNode.x).toFloat()
    val dy = (endNode.y - startNode.y).toFloat()
    val bearingRad = atan2(dx.toDouble(), (-dy).toDouble())
    return ((Math.toDegrees(bearingRad).toFloat()) + 360f) % 360f
}