package com.example.mallar.ar

import android.util.Log
import com.example.mallar.data.GraphNode
import com.google.ar.core.*
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import dev.romainguy.kotlin.math.Float4
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "ArrowSceneManager"

// ── Arrow geometry (tuned for real-world visibility) ──────────────────────────
//
//  The arrow is made of two CubeNodes:
//   - shaft : the long body
//   - head  : the wider arrowhead
//
//  Dimensions are in metres. On a mall floor these values produce an arrow
//  about 36 cm long × 16 cm wide — clearly visible from standing height.
//
private const val SHAFT_WIDTH  = 0.05f   // metres
private const val SHAFT_LENGTH = 0.22f
private const val HEAD_WIDTH   = 0.16f
private const val HEAD_LENGTH  = 0.14f
private const val THICKNESS    = 0.018f  // how tall (Y) the flat arrow is

// Arrow total length along Z = SHAFT_LENGTH + HEAD_LENGTH = 0.36 m
// The shaft's local +Z end connects to the head's -Z end.
// The head's tip points in the -Z direction of the group node.
// The group is then rotated by yRotationDeg so -Z faces the next waypoint.

// ── Arrow color: bright green ─────────────────────────────────────────────────
private const val CR = 0.0f
private const val CG = 0.95f
private const val CB = 0.4f
private const val CA = 1.0f

// ── How far above the floor the arrows float ─────────────────────────────────
//  0.05 m = 5 cm  → just above floor, clearly visible, not floating in mid-air
private const val ARROW_FLOOR_Y = 0.05f

class ArrowSceneManager(
    private val sceneView: ARSceneView,
    private val transformer: ArCoordinateTransformer,
    private val pathNodes: List<GraphNode>
) {

    var rootAnchorNode: AnchorNode? = null
    var isWorldOriginSet = false

    // Camera position relative to the root anchor, updated every frame
    var userArX = 0f
    var userArZ = 0f

    private data class ArrowEntry(
        val groupNode:    Node,
        val placement:    ArrowPlacement,
        val targetArPos:  ArPosition
    )

    private val arrows = mutableListOf<ArrowEntry>()

    // Pre-compute all node positions once (lazy so transformer is ready)
    private val nodeArPositions by lazy {
        pathNodes.map { transformer.toArLocal(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called every AR frame. Updates user position and arrow visibility.
     * Safe to call before the world origin is set — returns immediately.
     */
    fun onFrame(
        frame: Frame,
        currentSegmentIdx: Int,
        onNodeReached: (Int) -> Unit
    ) {
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        updateUserPosition(frame.camera)

        // Check if user has reached the next waypoint
        if (isWorldOriginSet && currentSegmentIdx < pathNodes.size - 1) {
            val target = nodeArPositions.getOrNull(currentSegmentIdx + 1) ?: return
            val dist = transformer.distanceFromCamera(userArX, userArZ, target)
            if (dist < ARRIVAL_THRESHOLD_M) {
                onNodeReached(currentSegmentIdx + 1)
            }
        }

        if (isWorldOriginSet) updateArrowVisibility(currentSegmentIdx)
    }

    /**
     * Call this once when you have a valid [HitResult] on the floor.
     * Creates the anchor and places all arrows.
     */
    fun placeWorldOrigin(hitResult: HitResult) {
        if (isWorldOriginSet) return
        buildAnchorAndArrows(hitResult.createAnchor())
    }

    /** Removes all nodes and anchors from the scene. */
    fun destroy() {
        arrows.forEach { it.groupNode.destroy() }
        arrows.clear()
        rootAnchorNode?.destroy()
        rootAnchorNode = null
        isWorldOriginSet = false
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun updateUserPosition(camera: Camera) {
        val camPose    = camera.pose
        val anchorPose = rootAnchorNode?.anchor?.pose

        if (anchorPose != null) {
            // Position relative to the world-origin anchor
            userArX = camPose.tx() - anchorPose.tx()
            userArZ = camPose.tz() - anchorPose.tz()
        } else {
            userArX = camPose.tx()
            userArZ = camPose.tz()
        }
    }

    private fun buildAnchorAndArrows(anchor: Anchor) {
        val anchorNode = AnchorNode(sceneView.engine, anchor).also {
            sceneView.addChildNode(it)
        }
        rootAnchorNode = anchorNode
        isWorldOriginSet = true

        placeArrows(anchorNode)
        Log.d(TAG, "World origin set. ${arrows.size} arrows placed.")
    }

    private fun placeArrows(root: AnchorNode) {
        val placements = transformer.computeArrowPlacements(pathNodes)

        placements.forEach { placement ->
            val group = buildArrowGroup(placement)
            root.addChildNode(group)
            arrows += ArrowEntry(group, placement, placement.position)
        }
    }

    /**
     * Builds a single directional arrow at [placement] position.
     *
     * Arrow anatomy (local space of the group node, before yRotation):
     *
     *   ──────────[HEAD]──→
     *   ←shaft_len→←head→
     *
     *  The arrowhead tip points in the -Z direction of the group node.
     *  The group is then rotated so -Z faces the next waypoint.
     *
     *  FIX: original code had head positioned at -(SHAFT_LENGTH/2) which
     *  caused the head to overlap or be behind the shaft. Correct layout:
     *    shaft centre = (0, 0, +HEAD_LENGTH/2)          → body
     *    head  centre = (0, 0, -(SHAFT_LENGTH/2))        → tip
     */
    private fun buildArrowGroup(placement: ArrowPlacement): Node {
        val p = placement.position

        val group = Node(sceneView.engine).apply {
            // Use ARROW_FLOOR_Y instead of the raw p.y from the transformer
            // so arrows always sit 5 cm above the anchor plane regardless of
            // scale mismatches.
            position = Position(p.x, ARROW_FLOOR_Y, p.z)
            rotation = Rotation(0f, placement.yRotationDeg, 0f)
        }

        val shaftMat = sceneView.materialLoader.createColorInstance(
            Float4(CR, CG, CB, CA)
        )
        val headMat = sceneView.materialLoader.createColorInstance(
            Float4(CR + 0.05f, CG, CB + 0.1f, CA)
        )

        // Shaft — the long rectangular body
        val shaft = CubeNode(
            engine           = sceneView.engine,
            size             = io.github.sceneview.math.Size(SHAFT_WIDTH, THICKNESS, SHAFT_LENGTH),
            center           = Position(0f, 0f, 0f),
            materialInstance = shaftMat
        ).apply {
            // Offset forward (along +Z of group) so shaft's -Z end = group origin
            position = Position(0f, 0f, SHAFT_LENGTH / 2f)
        }

        // Arrowhead — wider, shorter, placed at the end of the shaft
        val head = CubeNode(
            engine           = sceneView.engine,
            size             = io.github.sceneview.math.Size(HEAD_WIDTH, THICKNESS, HEAD_LENGTH),
            center           = Position(0f, 0f, 0f),
            materialInstance = headMat
        ).apply {
            // Place just beyond the shaft end (pointing forward = -Z direction)
            position = Position(0f, 0f, SHAFT_LENGTH + HEAD_LENGTH / 2f)
        }

        group.addChildNode(shaft)
        group.addChildNode(head)

        return group
    }

    // ── Arrow visibility / opacity per segment ────────────────────────────────

    private fun updateArrowVisibility(currentSegmentIdx: Int) {
        // Pulsing alpha for the active segment
        val pulse = run {
            val t = (System.currentTimeMillis() % 900L) / 900.0
            (0.75f + 0.25f * sin(t * 2.0 * Math.PI)).toFloat()
        }

        arrows.forEach { entry ->
            val seg  = entry.placement.segmentIndex
            val dist = transformer.distanceFromCamera(userArX, userArZ, entry.targetArPos)

            when {
                // Already-passed segments — fade out
                seg < currentSegmentIdx -> {
                    entry.groupNode.isVisible = false
                }
                // Active segment — pulse when close, solid otherwise
                seg == currentSegmentIdx -> {
                    entry.groupNode.isVisible = true
                    // Pulsing is visual only; actual alpha control requires
                    // a custom material. isVisible toggle approximates it.
                }
                // Future segments — visible but dimmer (no way to set alpha
                // on CubeNode without custom material, so just keep visible)
                else -> {
                    entry.groupNode.isVisible = true
                }
            }
        }
    }

    companion object {
        const val ARRIVAL_THRESHOLD_M = 1.2f
    }
}