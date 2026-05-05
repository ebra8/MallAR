package com.example.mallar.ar

import android.util.Log
import com.example.mallar.data.GraphNode
import com.google.ar.core.*
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import kotlin.math.*

private const val TAG = "ArrowSceneManager"

private const val ARROW_MODEL_PATH = "models/nav_arrow.glb"
private const val PIN_MODEL_PATH   = "models/pin.glb"
private const val ARROW_SCALE      = 0.35f
private const val ARROW_FLOOR_Y    = 0.05f

// Anchor stabilisation samples
private const val ANCHOR_SAMPLE_COUNT = 20

// Pin rendering constants
private const val PIN_SCALE  = 0.6f
private const val PIN_HEIGHT = 0.5f

// ─────────────────────────────────────────────────────────────────────────────
/**
 * Navigation phases:
 *
 *  SCANNING            → Floor being detected; anchor samples being collected.
 *  AWAITING_USER       → Start pin placed; user must walk to it.
 *  MANUAL_CALIBRATION  → User is at the start pin; must point phone toward the
 *                         real-world destination and tap "Confirm Direction".
 *                         Arrows are NOT shown yet.
 *  NAVIGATING          → Manual calibration locked; arrows placed and tracking.
 */
enum class NavigationPhase {
    SCANNING,
    AWAITING_USER,
    MANUAL_CALIBRATION,
    NAVIGATING
}

// ─────────────────────────────────────────────────────────────────────────────
class ArrowSceneManager(
    private val sceneView:   ARSceneView,
    private val transformer: ArCoordinateTransformer,
    pathNodes:               List<GraphNode>
) {
    // ── State ─────────────────────────────────────────────────────────────────
    private var currentPathNodes: MutableList<GraphNode> = pathNodes.toMutableList()

    var phase: NavigationPhase = NavigationPhase.SCANNING
        private set

    // ── Anchor ────────────────────────────────────────────────────────────────
    private var rootAnchorNode: AnchorNode? = null
    private var anchorPose: Pose? = null

    private val hitSamples = mutableListOf<FloatArray>()
    var isCollectingSamples = false
        private set
    val sampleProgress: Float get() =
        (hitSamples.size.toFloat() / ANCHOR_SAMPLE_COUNT).coerceIn(0f, 1f)

    val isWorldOriginSet: Boolean get() = phase != NavigationPhase.SCANNING

    // ── Compass ───────────────────────────────────────────────────────────────
    /** Latest filtered compass heading — updated every sensor tick. */
    private var currentFilteredHeadingDeg = 0f

    /** First-segment map bearing computed at anchor-commit time. */
    private var firstSegMapBearingDeg = 0f

    // ── Debug values (exposed for the debug overlay) ──────────────────────────
    var debugRawHeadingDeg:      Float = 0f; private set
    var debugCalculatedBearing:  Float = 0f; private set
    var debugAppliedOffset:      Float = 0f; private set

    // ── Camera ────────────────────────────────────────────────────────────────
    private var camArX = 0f
    private var camArZ = 0f
    val cameraArX: Float get() = camArX
    val cameraArZ: Float get() = camArZ

    // Camera forward direction in anchor-local AR space (horizontal unit vector).
    // Extracted every frame: camera looks along -Z → forward = -getZAxis().
    private var camForwardX = 0f
    private var camForwardZ = -1f   // default: facing away from anchor

    // Latest real-time orientation guidance — read by the HUD every frame.
    var lastOrientationGuidance: OrientationGuidance? = null
        private set

    // ── Movement-based correction ─────────────────────────────────────────────
    // Previous AR position — updated only after user has moved enough.
    private var prevArX = Float.NaN
    private var prevArZ = Float.NaN
    // Whether a 180° flip is currently applied to all arrows.
    private var movementCorrectionApplied = false

    // ── Arrival ───────────────────────────────────────────────────────────────
    private var lastReportedNodeIdx = -1
    private var cameraPathDistance  = 0f

    // ── Pins ──────────────────────────────────────────────────────────────────
    private var startPinNode: ModelNode? = null
    private var endPinNode:   ModelNode? = null

    // ── Arrows ────────────────────────────────────────────────────────────────
    private data class ArrowEntry(
        val modelNode:         ModelNode,
        val placement:         ArrowPlacement,
        val distanceAlongPath: Float
    )
    private val arrows = mutableListOf<ArrowEntry>()

    // ── Internals ─────────────────────────────────────────────────────────────
    private fun nodeArPositions(): List<ArPosition> =
        currentPathNodes.map { transformer.toArLocal(it) }

    private fun nodePathDistances(): List<Float> =
        transformer.nodePathDistances(currentPathNodes)

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 1: anchor sample collection (SCANNING)
    // ─────────────────────────────────────────────────────────────────────────
    fun startSampleCollection() {
        if (phase != NavigationPhase.SCANNING || isCollectingSamples) return
        hitSamples.clear()
        isCollectingSamples = true
        Log.d(TAG, "Started anchor sample collection")
    }

    fun feedHitSample(hit: HitResult): Boolean {
        if (!isCollectingSamples || phase != NavigationPhase.SCANNING) return false
        val p = hit.hitPose
        hitSamples.add(floatArrayOf(p.tx(), p.ty(), p.tz()))
        return hitSamples.size >= ANCHOR_SAMPLE_COUNT
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 1 → 2: place anchor + start pin; wait for user to walk to it
    // ─────────────────────────────────────────────────────────────────────────
    fun commitAnchorAndAwaitUser(
        session: Session,
        frame: Frame,
        firstSegmentMapBearingDeg: Float = 0f
    ) {
        if (phase != NavigationPhase.SCANNING || hitSamples.isEmpty()) return

        val avgTx = hitSamples.map { it[0] }.average().toFloat()
        val avgTy = hitSamples.map { it[1] }.average().toFloat()
        val avgTz = hitSamples.map { it[2] }.average().toFloat()

        val avgPose = Pose(floatArrayOf(avgTx, avgTy, avgTz), floatArrayOf(0f, 0f, 0f, 1f))
        val anchor  = session.createAnchor(avgPose)
        anchorPose  = anchor.pose

        val anchorNode = AnchorNode(sceneView.engine, anchor).also {
            sceneView.addChildNode(it)
        }
        rootAnchorNode      = anchorNode
        isCollectingSamples = false
        this.firstSegMapBearingDeg = firstSegmentMapBearingDeg
        debugCalculatedBearing     = firstSegmentMapBearingDeg

        // Rough initial heading so the start pin appears near the correct position.
        val zAxis = frame.camera.pose.getZAxis()
        val cameraYawDeg = Math.toDegrees(
            atan2((-zAxis[0]).toDouble(), zAxis[2].toDouble())
        ).toFloat()
        transformer.headingOffsetDeg = cameraYawDeg - firstSegmentMapBearingDeg

        // Place start pin (visible immediately so user can walk to it)
        // and end pin as a reference marker.
        placeStartPin(anchorNode)
        placeEndPin(anchorNode)

        phase = NavigationPhase.AWAITING_USER
        Log.d(TAG, "Anchor placed. Phase → AWAITING_USER")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2 → 3: user reached start pin; enter manual calibration
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Called when the user taps "Start Navigation" after walking to the pin.
     * Transitions to MANUAL_CALIBRATION so the user can point their phone
     * toward the destination and confirm the direction.
     */
    fun requestStartNavigation(compassAccuracyLevel: Int): StartResult {
        if (phase != NavigationPhase.AWAITING_USER) {
            return StartResult.Error("Not in awaiting state")
        }

        val distFromStart = sqrt(camArX * camArX + camArZ * camArZ)
        if (distFromStart > START_PIN_CONFIRM_RADIUS_M) {
            return StartResult.TooFar(distFromStart)
        }

        phase = NavigationPhase.MANUAL_CALIBRATION
        Log.d(TAG, "Phase → MANUAL_CALIBRATION. distFromStart=$distFromStart")
        return StartResult.Ok
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 3 → 4: user taps "Confirm Direction"
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Called when the user has pointed their phone in the real-world direction
     * of the destination and taps "Confirm Direction".
     *
     * [deviceHeadingDeg] – compass azimuth at this moment (0–360°, CW from north).
     *
     * Calculates:  offset = trueBearing − deviceHeading
     * Locks this as the world-alignment reference, then spawns arrows.
     */
    fun confirmManualDirection(deviceHeadingDeg: Float): Boolean {
        if (phase != NavigationPhase.MANUAL_CALIBRATION) return false

        val trueBearing = firstSegMapBearingDeg
        debugRawHeadingDeg     = deviceHeadingDeg
        debugCalculatedBearing = trueBearing

        // Delegate offset computation + locking to the transformer
        transformer.applyManualCalibration(
            deviceHeadingDeg = deviceHeadingDeg,
            trueBearingDeg   = trueBearing
        )
        debugAppliedOffset = transformer.headingOffsetDeg

        Log.d(TAG,
            "Manual calibration confirmed. " +
                    "deviceHeading=$deviceHeadingDeg trueBearing=$trueBearing " +
                    "offset=${transformer.headingOffsetDeg}"
        )

        // Arrows can now be placed with the correct world alignment
        rootAnchorNode?.let { placeArrows(it) }
        phase = NavigationPhase.NAVIGATING
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feed a live compass reading
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Call this from the SensorEventListener on every reading.
     * During MANUAL_CALIBRATION this merely updates the cached heading
     * (used when user taps "Confirm Direction").
     * During NAVIGATING it applies gentle Kalman-filtered drift correction.
     */
    fun feedCompassReading(rawDeg: Float, accuracy: Int): Boolean {
        debugRawHeadingDeg = rawDeg
        val filtered = transformer.updateCompassHeading(rawDeg)
        currentFilteredHeadingDeg = filtered
        debugAppliedOffset = transformer.headingOffsetDeg
        // Return value kept for API compatibility; calibration no longer
        // auto-completes from compass alone.
        return false
    }

    /** Latest filtered compass heading — used by the UI "Confirm Direction" action. */
    fun currentHeadingDeg(): Float = currentFilteredHeadingDeg

    // ─────────────────────────────────────────────────────────────────────────
    // Per-frame update (NAVIGATING)
    // ─────────────────────────────────────────────────────────────────────────
    fun onFrame(
        frame: Frame,
        currentSegmentIdx: Int,
        onNodeReached: (Int) -> Unit,
        onDeviation: ((deviationDist: Float, userArX: Float, userArZ: Float) -> Unit)? = null
    ) {
        if (phase != NavigationPhase.NAVIGATING) return
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        updateCameraPosition(frame.camera)
        updatePinBillboards()

        val distFromStart = sqrt(camArX * camArX + camArZ * camArZ)
        if (distFromStart < MIN_TRAVEL_BEFORE_ARRIVAL_M) {
            updateArrowVisibility()
            return
        }

        // ── Project user onto path for accurate distance tracking ──────────
        val projection = transformer.projectOntoPath(camArX, camArZ, currentPathNodes)
        cameraPathDistance = projection.distanceAlongPath

        // ── Real-time orientation guidance (camera forward vs next waypoint) ──
        // Next waypoint = end of the segment the user is currently on.
        val nodeAr      = nodeArPositions()
        val nextIdx     = (projection.closestSegmentIndex + 1).coerceAtMost(currentPathNodes.size - 1)
        val nextNodeAR  = nodeAr.getOrNull(nextIdx)
        lastOrientationGuidance = if (nextNodeAR != null) {
            transformer.computeOrientationGuidance(
                camFwdX       = camForwardX,
                camFwdZ       = camForwardZ,
                nextWaypointAR = nextNodeAR,
                userArX       = camArX,
                userArZ       = camArZ
            )
        } else null

        // ── Movement-based direction correction ───────────────────────────────
        // Uses the user's real walking direction (not camera forward) to detect
        // if they are moving opposite to the path and corrects arrow orientation.
        // SAFE: only activates after MOVEMENT_CORRECTION_THRESHOLD_M of travel.
        if (nextNodeAR != null && !prevArX.isNaN()) {
            val mvDx = camArX - prevArX
            val mvDz = camArZ - prevArZ
            val mvLen = sqrt(mvDx * mvDx + mvDz * mvDz)

            if (mvLen >= MOVEMENT_CORRECTION_THRESHOLD_M) {
                // Normalize movement direction
                val mvX = mvDx / mvLen
                val mvZ = mvDz / mvLen

                // Normalize path direction (user→nextWaypoint)
                val pdx = nextNodeAR.x - camArX
                val pdz = nextNodeAR.z - camArZ
                val pdLen = sqrt(pdx * pdx + pdz * pdz)
                if (pdLen > 0.01f) {
                    val pathX = pdx / pdLen
                    val pathZ = pdz / pdLen

                    val dot = mvX * pathX + mvZ * pathZ

                    Log.d(TAG,
                        "MovCorrection | movDir=(${"%.3f".format(mvX)},${"%.3f".format(mvZ)}) " +
                        "pathDir=(${"%.3f".format(pathX)},${"%.3f".format(pathZ)}) " +
                        "dot=${"%.3f".format(dot)} " +
                        "flipApplied=$movementCorrectionApplied"
                    )

                    val shouldFlip = dot < 0f
                    if (shouldFlip != movementCorrectionApplied) {
                        // Apply or remove the 180° flip on every rendered arrow
                        arrows.forEach { entry ->
                            val currentRot = entry.placement.yRotationDeg
                            val newRot = if (shouldFlip) (currentRot + 180f) % 360f
                                         else (currentRot - 180f + 360f) % 360f
                            entry.modelNode.rotation = Rotation(0f, newRot, 0f)
                        }
                        movementCorrectionApplied = shouldFlip
                        Log.d(TAG, "MovCorrection: arrows flipped=$shouldFlip (dot=$dot)")
                    }
                }

                // Update previous position once threshold is crossed
                prevArX = camArX
                prevArZ = camArZ
            }
        } else if (prevArX.isNaN()) {
            // First valid position — seed the tracker
            prevArX = camArX
            prevArZ = camArZ
        }

        // ── Remove arrows the user has already passed ──────────────────────
        val toRemove = arrows.filter { entry ->
            val dx = camArX - entry.placement.position.x
            val dz = camArZ - entry.placement.position.z
            sqrt(dx * dx + dz * dz) < ARROW_DELETE_DISTANCE_M &&
                    entry.distanceAlongPath <= cameraPathDistance
        }
        toRemove.forEach { try { it.modelNode.destroy() } catch (_: Exception) {} }
        arrows.removeAll(toRemove.toSet())

        updateArrowVisibility()

        // ── Deviation check → notify caller to reroute ────────────────────
        if (projection.deviationDistance > REROUTE_THRESHOLD_M) {
            onDeviation?.invoke(projection.deviationDistance, camArX, camArZ)
        }

        // ── Arrival: only check distance to the FINAL node ────────────────
        val finalNode = nodeAr.lastOrNull() ?: return
        val dxFinal   = camArX - finalNode.x
        val dzFinal   = camArZ - finalNode.z
        val distToFinal = sqrt(dxFinal * dxFinal + dzFinal * dzFinal)

        if (distToFinal < ARRIVAL_THRESHOLD_M) {
            val finalIdx = currentPathNodes.size - 1
            if (lastReportedNodeIdx != finalIdx) {
                lastReportedNodeIdx = finalIdx
                onNodeReached(finalIdx)
                removeStartPin()
                removeEndPin()
            }
            return
        }

        // ── Intermediate node progress (HUD card updates) ─────────────────
        for (checkIdx in (lastReportedNodeIdx + 1) until currentPathNodes.size - 1) {
            val checkPos = nodeAr.getOrNull(checkIdx) ?: continue
            val dx = camArX - checkPos.x
            val dz = camArZ - checkPos.z
            if (sqrt(dx * dx + dz * dz) < ARRIVAL_THRESHOLD_M) {
                lastReportedNodeIdx = checkIdx
                onNodeReached(checkIdx)
                if (checkIdx >= 1) removeStartPin()
                break
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rerouting: replace arrows with a new path (pins stay in place)
    // ─────────────────────────────────────────────────────────────────────────
    fun rebuildPath(newPathNodes: List<GraphNode>) {
        arrows.forEach { try { it.modelNode.destroy() } catch (_: Exception) {} }
        arrows.clear()
        lastReportedNodeIdx       = -1
        cameraPathDistance        = 0f
        currentPathNodes          = newPathNodes.toMutableList()
        // Reset movement tracker so the new path starts fresh
        prevArX                   = Float.NaN
        prevArZ                   = Float.NaN
        movementCorrectionApplied = false
        rootAnchorNode?.let { placeArrows(it) }
        Log.d(TAG, "Path rebuilt: ${newPathNodes.size} nodes")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Billboard pins toward camera every frame
    // ─────────────────────────────────────────────────────────────────────────
    private fun updatePinBillboards() {
        fun billboard(pin: ModelNode?) {
            pin ?: return
            val pos  = pin.position
            val dx   = camArX - pos.x
            val dz   = camArZ - pos.z
            val yDeg = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
            pin.rotation = Rotation(0f, yDeg, 0f)
        }
        billboard(startPinNode)
        billboard(endPinNode)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────
    fun destroy() {
        try { startPinNode?.destroy() } catch (_: Exception) {}
        startPinNode = null
        try { endPinNode?.destroy() } catch (_: Exception) {}
        endPinNode = null
        arrows.forEach { try { it.modelNode.destroy() } catch (_: Exception) {} }
        arrows.clear()
        try { rootAnchorNode?.destroy() } catch (_: Exception) {}
        rootAnchorNode            = null
        anchorPose                = null
        hitSamples.clear()
        isCollectingSamples       = false
        lastReportedNodeIdx       = -1
        cameraPathDistance        = 0f
        prevArX                   = Float.NaN
        prevArZ                   = Float.NaN
        movementCorrectionApplied = false
        phase                     = NavigationPhase.SCANNING
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────
    private fun updateCameraPosition(camera: Camera) {
        val stored = anchorPose ?: return
        val local  = stored.inverse().compose(camera.pose)
        camArX = local.tx()
        camArZ = local.tz()
        // Camera looks along its local -Z axis (OpenGL convention).
        // getZAxis() returns the +Z axis in anchor-local space → negate for forward.
        val zAxis   = local.getZAxis()
        camForwardX = -zAxis[0]
        camForwardZ = -zAxis[2]
    }

    private fun placeStartPin(root: AnchorNode) {
        if (currentPathNodes.isEmpty()) return
        val startPos = transformer.toArLocal(currentPathNodes.first())
        try {
            startPinNode = ModelNode(
                modelInstance = sceneView.modelLoader.createModelInstance(PIN_MODEL_PATH),
                scaleToUnits  = PIN_SCALE
            ).apply {
                isEditable = false
                position   = Position(startPos.x, PIN_HEIGHT, startPos.z)
                modelInstance.materialInstances.forEach { mat ->
                    try { mat.setParameter("baseColorFactor", 0.1f, 0.9f, 0.3f, 1f) }
                    catch (_: Exception) {}
                }
            }
            root.addChildNode(startPinNode!!)
            Log.d(TAG, "START pin placed at (${startPos.x}, ${startPos.z})")
        } catch (e: Exception) {
            Log.e(TAG, "START pin failed: ${e.message}")
        }
    }

    private fun placeEndPin(root: AnchorNode) {
        if (currentPathNodes.size < 2) return
        val destPos = transformer.toArLocal(currentPathNodes.last())
        try {
            endPinNode = ModelNode(
                modelInstance = sceneView.modelLoader.createModelInstance(PIN_MODEL_PATH),
                scaleToUnits  = PIN_SCALE
            ).apply {
                isEditable = false
                position   = Position(destPos.x, PIN_HEIGHT, destPos.z)
                modelInstance.materialInstances.forEach { mat ->
                    try { mat.setParameter("baseColorFactor", 0.95f, 0.15f, 0.15f, 1f) }
                    catch (_: Exception) {}
                }
            }
            root.addChildNode(endPinNode!!)
            Log.d(TAG, "END pin placed at (${destPos.x}, ${destPos.z})")
        } catch (e: Exception) {
            Log.e(TAG, "END pin failed: ${e.message}")
        }
    }

    private fun removeStartPin() {
        try { startPinNode?.destroy() } catch (_: Exception) {}
        startPinNode = null
    }

    private fun removeEndPin() {
        try { endPinNode?.destroy() } catch (_: Exception) {}
        endPinNode = null
    }

    private fun placeArrows(root: AnchorNode) {
        val placements = transformer.computeArrowPlacements(currentPathNodes)
        if (placements.isEmpty()) {
            Log.w(TAG, "No placements — path too short?")
            return
        }

        val nodeDists = nodePathDistances()
        val nodeArPos = nodeArPositions()

        for (pl in placements) {
            val segStartDist = nodeDists.getOrElse(pl.segmentIndex) { 0f }
            val segStartPos  = nodeArPos.getOrNull(pl.segmentIndex)
            val arrowDist = if (segStartPos != null) {
                val dx = pl.position.x - segStartPos.x
                val dz = pl.position.z - segStartPos.z
                segStartDist + sqrt(dx * dx + dz * dz)
            } else segStartDist

            try {
                val node = ModelNode(
                    modelInstance = sceneView.modelLoader.createModelInstance(ARROW_MODEL_PATH),
                    scaleToUnits  = ARROW_SCALE
                ).apply {
                    isEditable = false
                    isVisible  = false
                    position   = Position(pl.position.x, ARROW_FLOOR_Y, pl.position.z)
                    rotation   = Rotation(0f, pl.yRotationDeg, 0f)
                    modelInstance.materialInstances.forEach { mat ->
                        try { mat.setParameter("baseColorFactor", 0f, 0.85f, 0.8f, 1f) }
                        catch (_: Exception) {}
                    }
                }
                root.addChildNode(node)
                arrows += ArrowEntry(node, pl, arrowDist)
            } catch (e: Exception) {
                Log.e(TAG, "Arrow load failed seg=${pl.segmentIndex}: ${e.message}")
            }
        }

        arrows.sortBy { it.distanceAlongPath }
        Log.d(TAG, "Placed ${arrows.size} arrows along path")
    }

    private fun updateArrowVisibility() {
        var shown = 0
        for (entry in arrows) {
            if (entry.distanceAlongPath >= cameraPathDistance) {
                entry.modelNode.isVisible = shown < MAX_VISIBLE_ARROWS
                if (entry.modelNode.isVisible) shown++
            } else {
                entry.modelNode.isVisible = false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    companion object {
        const val MIN_TRAVEL_BEFORE_ARRIVAL_M:      Float = 1.5f
        const val ARRIVAL_THRESHOLD_M:              Float = 1.5f   // was 1.0 — prevents early arrival
        const val ARROW_DELETE_DISTANCE_M:          Float = 0.7f
        const val MAX_VISIBLE_ARROWS:               Int  = 8
        const val START_PIN_CONFIRM_RADIUS_M:       Float = 2.5f
        const val REROUTE_THRESHOLD_M:              Float = 2.0f   // deviation before recalculating
        /** Minimum real movement before movement-based correction activates. */
        const val MOVEMENT_CORRECTION_THRESHOLD_M:  Float = 1.0f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
sealed class StartResult {
    object Ok : StartResult()
    data class TooFar(val distanceM: Float) : StartResult()
    data class Error(val reason: String) : StartResult()
}