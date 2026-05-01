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
private const val ARROW_SCALE      = 0.35f
private const val ARROW_FLOOR_Y    = 0.05f

// Number of hit-test poses to average for stable anchor placement
private const val ANCHOR_SAMPLE_COUNT = 15

// Start/end pin scales
private const val PIN_SCALE = 0.5f

class ArrowSceneManager(
    private val sceneView:   ARSceneView,
    private val transformer: ArCoordinateTransformer,
    private val pathNodes:   List<GraphNode>
) {
    // ── Anchor placement ──────────────────────────────────────────────────────
    private var rootAnchorNode: AnchorNode? = null
    private var anchorPose: Pose? = null

    // Averaged anchor stabilisation
    private val hitSamples = mutableListOf<FloatArray>()  // each = [tx, ty, tz]
    var isCollectingSamples = false
        private set

    var isWorldOriginSet = false
        private set

    // ── Camera tracking ───────────────────────────────────────────────────────
    private var camArX = 0f
    private var camArZ = 0f

    var navigationStarted = false
        private set

    // ── Arrival dedup ─────────────────────────────────────────────────────────
    /** Last node index we already reported to onNodeReached; prevents re-firing
     *  on every AR frame while Compose state is being updated. */
    private var lastReportedNodeIdx = -1

    // Track total path distance covered — used to window visible arrows
    private var cameraPathDistance = 0f

    // ── Start/End pin nodes ───────────────────────────────────────────────────
    private var startPinNode: ModelNode? = null
    private var endPinNode: ModelNode? = null

    // ── Arrow nodes ───────────────────────────────────────────────────────────
    /**
     * An arrow that has been placed in the scene.
     * [distanceAlongPath] is the cumulative distance from Node[0] along the path.
     * This lets us sort and window arrows correctly as the user walks.
     */
    private data class ArrowEntry(
        val modelNode:        ModelNode,
        val placement:        ArrowPlacement,
        val distanceAlongPath: Float   // metres from path start
    )
    /** All live arrows. Sorted ascending by [ArrowEntry.distanceAlongPath]. */
    private val arrows = mutableListOf<ArrowEntry>()

    // Node AR positions — computed fresh each call so headingOffset is always current
    private fun nodeArPositions(): List<ArPosition> =
        pathNodes.map { transformer.toArLocal(it) }

    /** Cumulative arc-length from Node[0] to each node in AR space. */
    private fun nodePathDistances(): List<Float> {
        val positions = nodeArPositions()
        val dists = mutableListOf(0f)
        for (i in 1 until positions.size) {
            val prev = positions[i - 1]
            val cur  = positions[i]
            val dx = cur.x - prev.x
            val dz = cur.z - prev.z
            dists += dists.last() + sqrt(dx * dx + dz * dz)
        }
        return dists
    }

    // ── Phase 1: start collecting averaged hit-test samples ───────────────────
    /**
     * Call this when a floor plane is first detected.
     * We accumulate [ANCHOR_SAMPLE_COUNT] poses and average them for
     * a stable anchor placement rather than using a single noisy hit.
     */
    fun startSampleCollection() {
        if (isWorldOriginSet || isCollectingSamples) return
        hitSamples.clear()
        isCollectingSamples = true
        Log.d(TAG, "Started anchor sample collection")
    }

    /**
     * Feed one hit-test result per frame while [isCollectingSamples] is true.
     * Returns true when enough samples have been collected and the anchor
     * is ready to be placed (caller should then call [commitAnchor]).
     */
    fun feedHitSample(hit: HitResult): Boolean {
        if (!isCollectingSamples || isWorldOriginSet) return false
        val p = hit.hitPose
        hitSamples.add(floatArrayOf(p.tx(), p.ty(), p.tz()))
        Log.v(TAG, "Sample ${hitSamples.size}/$ANCHOR_SAMPLE_COUNT collected")
        return hitSamples.size >= ANCHOR_SAMPLE_COUNT
    }

    /**
     * Average the collected samples and create the anchor.
     * Call this once [feedHitSample] returns true.
     */
    fun commitAnchor(session: Session, @Suppress("UNUSED_PARAMETER") frame: Frame) {
        if (isWorldOriginSet || hitSamples.isEmpty()) return

        // Average the hit positions
        val avgTx = hitSamples.map { it[0] }.average().toFloat()
        val avgTy = hitSamples.map { it[1] }.average().toFloat()
        val avgTz = hitSamples.map { it[2] }.average().toFloat()

        // Build a flat (gravity-aligned) pose at the averaged position
        val avgPose = Pose(floatArrayOf(avgTx, avgTy, avgTz), floatArrayOf(0f, 0f, 0f, 1f))
        val anchor  = session.createAnchor(avgPose)
        anchorPose  = anchor.pose

        val anchorNode = AnchorNode(sceneView.engine, anchor).also {
            sceneView.addChildNode(it)
        }
        rootAnchorNode   = anchorNode
        isCollectingSamples = false
        isWorldOriginSet = true

        Log.d(TAG, "Anchor committed.")
    }

    /**
     * Automatically calculates the heading to align the first path segment
     * with the physical forward direction (-Z axis), places all arrows and pins,
     * and starts navigation tracking.
     */
    fun autoAlignAndStart(compassAzimuth: Float, camera: Camera) {
        val stored = anchorPose ?: return
        val local = stored.inverse().compose(camera.pose)
        val zAxis = local.zAxis
        // local.zAxis returns the +Z axis. The camera looks down -Z.
        // So the camera's forward vector is (-zAxis[0], -zAxis[1], -zAxis[2])
        val camDirX = -zAxis[0]
        val camDirZ = -zAxis[2]
        
        transformer.autoAlignWithCompass(compassAzimuth, camDirX, camDirZ)
        
        val root = rootAnchorNode ?: return
        arrows.forEach { try { it.modelNode.destroy() } catch (_: Exception) {} }
        arrows.clear()
        placeArrows(root)
        placeStartEndPins(root)
        navigationStarted = true
        Log.d(TAG, "Compass Auto-aligned. Azimuth=$compassAzimuth, headingOffset=${transformer.headingOffsetDeg}°. ${arrows.size} arrows placed.")
    }

    // ── Per-frame update ──────────────────────────────────────────────────────
    fun onFrame(frame: Frame, currentSegmentIdx: Int, onNodeReached: (Int) -> Unit) {
        if (!isWorldOriginSet) return
        if (!navigationStarted) return
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        updateCameraPosition(frame.camera)
        if (arrows.isEmpty() && currentSegmentIdx >= pathNodes.size - 1) return

        // Wait until the user has actually taken a step away from the start point (0,0)
        val distanceFromStart = sqrt(camArX * camArX + camArZ * camArZ)
        if (distanceFromStart < 0.5f) {
            updateArrowVisibility()
            return
        }

        // Estimate camera progress along the path using the nearest node
        val nodeDists  = nodePathDistances()
        val nodeAr     = nodeArPositions()
        var closestNodeDist = Float.MAX_VALUE
        var closestNodePathDist = 0f
        for (i in nodeAr.indices) {
            val dx = camArX - nodeAr[i].x
            val dz = camArZ - nodeAr[i].z
            val d = sqrt(dx * dx + dz * dz)
            if (d < closestNodeDist) {
                closestNodeDist    = d
                closestNodePathDist = nodeDists[i]
            }
        }
        cameraPathDistance = closestNodePathDist

        // ── Delete arrows already passed by the user ──────────────────────────
        val toRemove = arrows.filter { entry ->
            val dx = camArX - entry.placement.position.x
            val dz = camArZ - entry.placement.position.z
            sqrt(dx * dx + dz * dz) < ARROW_DELETE_DISTANCE_M &&
                    entry.distanceAlongPath <= cameraPathDistance
        }
        toRemove.forEach { entry ->
            try { entry.modelNode.destroy() } catch (_: Exception) {}
        }
        arrows.removeAll(toRemove.toSet())

        // ── Show/hide arrows based on look-ahead window ───────────────────────
        updateArrowVisibility()

        // ── Node arrival detection ────────────────────────────────────────────
        // Check against ALL upcoming nodes, not just the next one.
        // This prevents getting stuck if the user skips a waypoint.
        if (currentSegmentIdx < pathNodes.size - 1) {
            // Find the furthest node within arrival threshold
            var bestReachIdx = -1
            for (checkIdx in currentSegmentIdx + 1 until pathNodes.size) {
                val checkPos = nodeAr.getOrNull(checkIdx) ?: continue
                val dx = camArX - checkPos.x
                val dz = camArZ - checkPos.z
                val dist = sqrt(dx * dx + dz * dz)
                if (dist < ARRIVAL_THRESHOLD_M) {
                    bestReachIdx = checkIdx
                }
            }
            if (bestReachIdx > 0 && bestReachIdx != lastReportedNodeIdx) {
                lastReportedNodeIdx = bestReachIdx
                onNodeReached(bestReachIdx)

                // Remove start pin when user starts moving
                removeStartPin()

                // Check if arrived at destination
                if (bestReachIdx >= pathNodes.size - 1) {
                    removeEndPin()
                }
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────
    fun destroy() {
        try { startPinNode?.destroy() } catch (_: Exception) {}
        startPinNode = null
        try { endPinNode?.destroy() } catch (_: Exception) {}
        endPinNode = null
        arrows.forEach { try { it.modelNode.destroy() } catch (_: Exception) {} }
        arrows.clear()
        try { rootAnchorNode?.destroy() } catch (_: Exception) {}
        rootAnchorNode      = null
        anchorPose          = null
        isWorldOriginSet    = false
        hitSamples.clear()
        isCollectingSamples = false
        navigationStarted   = false
        lastReportedNodeIdx = -1
        cameraPathDistance  = 0f
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun updateCameraPosition(camera: Camera) {
        val stored = anchorPose ?: return
        val local  = stored.inverse().compose(camera.pose)
        camArX = local.tx()
        camArZ = local.tz()
    }

    /**
     * Place start and end pin indicators in the AR scene.
     * Start pin = green-tinted arrow at origin, End pin = red-tinted arrow at destination.
     */
    private fun placeStartEndPins(root: AnchorNode) {
        if (pathNodes.size < 2) return

        // ── Start pin (green) at origin (0,0) ────────────────────────────────
        try {
            startPinNode = ModelNode(
                modelInstance = sceneView.modelLoader.createModelInstance(ARROW_MODEL_PATH),
                scaleToUnits  = PIN_SCALE
            ).apply {
                isEditable = false
                position = Position(0f, ARROW_FLOOR_Y + 0.3f, 0f)  // Elevated above arrows
                // Point straight down to indicate "you are here"
                rotation = Rotation(90f, 0f, 0f)
                modelInstance.materialInstances.forEach { mat ->
                    try { mat.setParameter("baseColorFactor", 0.2f, 0.9f, 0.3f, 1f) } // Green
                    catch (_: Exception) { }
                }
            }
            root.addChildNode(startPinNode!!)
            Log.d(TAG, "Start pin placed at origin")
        } catch (e: Exception) {
            Log.e(TAG, "Start pin failed: ${e.message}")
        }

        // ── End pin (red) at destination ──────────────────────────────────────
        val destPos = transformer.toArLocal(pathNodes.last())
        try {
            endPinNode = ModelNode(
                modelInstance = sceneView.modelLoader.createModelInstance(ARROW_MODEL_PATH),
                scaleToUnits  = PIN_SCALE
            ).apply {
                isEditable = false
                position = Position(destPos.x, ARROW_FLOOR_Y + 0.3f, destPos.z)
                rotation = Rotation(90f, 0f, 0f)
                modelInstance.materialInstances.forEach { mat ->
                    try { mat.setParameter("baseColorFactor", 0.95f, 0.2f, 0.2f, 1f) } // Red
                    catch (_: Exception) { }
                }
            }
            root.addChildNode(endPinNode!!)
            Log.d(TAG, "End pin placed at destination")
        } catch (e: Exception) {
            Log.e(TAG, "End pin failed: ${e.message}")
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
        val placements = transformer.computeArrowPlacements(pathNodes)
        if (placements.isEmpty()) {
            Log.w(TAG, "No placements — path too short?")
            return
        }

        // Pre-compute arc-lengths for each segment start node so we can assign
        // a path-distance to every arrow placement.
        val nodeDists = nodePathDistances()

        for (pl in placements) {
            // distanceAlongPath = distance to the segment-start node + offset within segment
            val segStartDist = nodeDists.getOrElse(pl.segmentIndex) { 0f }
            // Estimate offset within segment from how far along the placement is
            val segStartPos  = nodeArPositions().getOrNull(pl.segmentIndex)
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
                    isVisible  = false  // hidden until updateArrowVisibility opens the window
                    position   = Position(pl.position.x, ARROW_FLOOR_Y, pl.position.z)
                    // The arrow model natively faces diagonally Left.
                    // Subtracting 135 degrees permanently corrects its internal orientation.
                    rotation   = Rotation(0f, pl.yRotationDeg - 135f, 0f)
                    modelInstance.materialInstances.forEach { mat ->
                        try { mat.setParameter("baseColorFactor", 0f, 0.85f, 0.8f, 1f) }
                        catch (_: Exception) { }
                    }
                }
                root.addChildNode(node)
                arrows += ArrowEntry(node, pl, arrowDist)
            } catch (e: Exception) {
                Log.e(TAG, "Arrow load failed seg=${pl.segmentIndex}: ${e.message}")
            }
        }

        // Ensure sorted by distance so windowing always works correctly
        arrows.sortBy { it.distanceAlongPath }
        Log.d(TAG, "Placed ${arrows.size} arrows along path")
    }

    /**
     * Show up to [MAX_VISIBLE_ARROWS] arrows starting from the user's current
     * position along the path. Arrows behind the user are already deleted;
     * arrows beyond the look-ahead window are hidden but kept for future use.
     */
    private fun updateArrowVisibility() {
        var shown = 0
        for (entry in arrows) {
            if (entry.distanceAlongPath >= cameraPathDistance) {
                entry.modelNode.isVisible = shown < MAX_VISIBLE_ARROWS
                if (entry.modelNode.isVisible) shown++
            } else {
                // Arrow is behind — should have been deleted, but keep hidden just in case
                entry.modelNode.isVisible = false
            }
        }
    }

    companion object {
        /** How close the user needs to be to a node before "arrived" fires (metres). */
        const val ARRIVAL_THRESHOLD_M: Float  = 1.2f
        /** An arrow is permanently destroyed once the user passes within this distance of it. */
        const val ARROW_DELETE_DISTANCE_M: Float = 0.8f
        /** Maximum arrows visible in the look-ahead window at any time. */
        const val MAX_VISIBLE_ARROWS: Int = 6
    }
}
