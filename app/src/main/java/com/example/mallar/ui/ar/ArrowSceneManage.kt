package com.example.mallar.ar

import android.util.Log
import com.example.mallar.data.GraphNode
import com.google.ar.core.*
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import kotlin.math.sqrt

private const val TAG = "ArrowSceneManager"

// ── Arrow model settings ──────────────────────────────────────────────────────
// The GLB lives at  assets/models/nav_arrow.glb
// scaleToUnits = 0.35f  →  arrow is ~35 cm tall/long.
// ARROW_FLOOR_Y: how far above the anchor plane the arrow floats (metres).
private const val ARROW_MODEL_PATH = "models/nav_arrow.glb"
private const val ARROW_SCALE      = 0.35f   // metres
private const val ARROW_FLOOR_Y    = 0.05f   // 5 cm above floor

class ArrowSceneManager(
    private val sceneView:   ARSceneView,
    private val transformer: ArCoordinateTransformer,
    private val pathNodes:   List<GraphNode>
) {
    // ── Internal state ────────────────────────────────────────────────────────

    private var rootAnchorNode: AnchorNode? = null
    var isWorldOriginSet = false
        private set

    /** The world-space pose of the root anchor; null until [placeWorldOrigin] is called. */
    val rootAnchorPose: com.google.ar.core.Pose?
        get() = rootAnchorNode?.anchor?.pose

    // Camera position relative to the anchor, updated every frame
    private var camArX = 0f
    private var camArZ = 0f

    private data class ArrowEntry(
        val modelNode:   ModelNode,
        val placement:   ArrowPlacement,
        val targetArPos: ArPosition
    )

    private val arrows = mutableListOf<ArrowEntry>()

    // Pre-compute each node's AR position (relative to startNode origin)
    private val nodeArPositions: List<ArPosition> by lazy {
        pathNodes.map { transformer.toArLocal(it) }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called once the floor is detected.  Creates the world-origin anchor at
     * [hitResult] and places all arrows along the A* path.
     */
    fun placeWorldOrigin(hitResult: HitResult, @Suppress("UNUSED_PARAMETER") frame: Frame) {
        if (isWorldOriginSet) return

        val anchor     = hitResult.createAnchor()
        val anchorNode = AnchorNode(sceneView.engine, anchor).also {
            sceneView.addChildNode(it)
        }
        rootAnchorNode   = anchorNode
        isWorldOriginSet = true

        placeArrows(anchorNode)
        Log.d(TAG, "World origin set. ${arrows.size} arrows placed.")
    }

    /**
     * Called every AR frame.  Updates the user's position and checks whether
     * the next waypoint has been reached.
     *
     * @param currentSegmentIdx  index of the segment the user is currently on
     * @param onNodeReached      called with the NEW segmentIndex when a waypoint is reached
     */
    fun onFrame(frame: Frame, currentSegmentIdx: Int, onNodeReached: (Int) -> Unit) {
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        updateCameraPosition(frame.camera)

        if (!isWorldOriginSet) return

        // Check proximity to the NEXT node on the path
        if (currentSegmentIdx < pathNodes.size - 1) {
            val nextNodeArPos = nodeArPositions.getOrNull(currentSegmentIdx + 1) ?: return
            val dist = transformer.distanceFromCamera(camArX, camArZ, nextNodeArPos)
            Log.v(TAG, "dist to node[${currentSegmentIdx + 1}] = ${"%.2f".format(dist)} m")
            if (dist < ARRIVAL_THRESHOLD_M) {
                onNodeReached(currentSegmentIdx + 1)
            }
        }

        updateArrowVisibility(currentSegmentIdx)
    }

    /** Release all scene nodes and anchor. */
    fun destroy() {
        arrows.forEach { it.modelNode.destroy() }
        arrows.clear()
        rootAnchorNode?.destroy()
        rootAnchorNode   = null
        isWorldOriginSet = false
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Update the camera's position in AR space relative to the anchor. */
    private fun updateCameraPosition(camera: Camera) {
        val camPose    = camera.pose
        val anchorPose = rootAnchorNode?.anchor?.pose
        if (anchorPose != null) {
            // Position relative to the anchor origin
            camArX = camPose.tx() - anchorPose.tx()
            camArZ = camPose.tz() - anchorPose.tz()
        } else {
            camArX = camPose.tx()
            camArZ = camPose.tz()
        }
    }

    /** Create and add one ModelNode per [ArrowPlacement] computed by the transformer. */
    private fun placeArrows(root: AnchorNode) {
        val placements = transformer.computeArrowPlacements(pathNodes)
        if (placements.isEmpty()) {
            Log.w(TAG, "No arrow placements computed — path has fewer than 2 nodes?")
            return
        }

        for (placement in placements) {
            try {
                val p = placement.position
                val node = ModelNode(
                    modelInstance = sceneView.modelLoader.createModelInstance(
                        assetFileLocation = ARROW_MODEL_PATH
                    ),
                    scaleToUnits = ARROW_SCALE
                ).apply {
                    position = Position(p.x, ARROW_FLOOR_Y, p.z)
                    rotation = Rotation(0f, placement.yRotationDeg, 0f)

                    // Tint arrows bright green so they stand out
                    modelInstance.materialInstances.forEach { mat ->
                        try {
                            mat.setParameter("baseColorFactor", 0f, 0.85f, 0.3f, 1f)
                        } catch (_: Exception) {
                            // Material may not have this parameter — ignore
                        }
                    }
                }
                root.addChildNode(node)
                arrows += ArrowEntry(node, placement, p)
                Log.d(TAG, "Arrow seg=${placement.segmentIndex} pos=(${p.x}, ${p.z}) rot=${placement.yRotationDeg}°")
            } catch (e: Exception) {
                Log.e(TAG, "Arrow load failed for seg=${placement.segmentIndex}: ${e.message}")
            }
        }
    }

    /**
     * Hide arrows for already-passed segments; show arrows for current and future segments.
     * Always show at least the arrows for the current segment so the user can see where to go.
     */
    private fun updateArrowVisibility(currentSegmentIdx: Int) {
        arrows.forEach { entry ->
            entry.modelNode.isVisible = entry.placement.segmentIndex >= currentSegmentIdx
        }
    }

    companion object {
        const val ARRIVAL_THRESHOLD_M = 1.5f
    }
}
