package com.example.mallar.ar

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 *  ArCoordinateTransformer
 *  ────────────────────────────────────────────────────────────────────────────
 *  Converts 2-D mall-map pixel coordinates into ARCore world-space metres,
 *  and provides Y-rotation helpers for arrow orientation.
 *
 *  Coordinate systems
 *  ─────────────────────
 *  Map space   : (x, y) in pixels, origin = top-left of image.
 *                +X = right,  +Y = DOWN (screen space)
 *
 *  ARCore space: right-handed, gravity-aligned.
 *                +X = right,  +Y = up,  +Z = toward user (away from screen)
 *
 *  Mapping (the key insight!)
 *  ──────────────────────────
 *      arX =  (mapX - originMapX) * scale        East  in map  →  +X in AR
 *      arZ =  (mapY - originMapY) * scale        Down  in map  →  +Z in AR
 *                                                (positive Z = toward user / south)
 *
 *  Arrow Y-rotation
 *  ────────────────
 *  We use atan2(dx, dz) so that:
 *      0°   = +Z direction (south on map / toward user in AR)
 *     90°   = +X direction (east  on map / right in AR)
 *    180°   = -Z direction (north on map / away from user in AR)
 *    -90°   = -X direction (west  on map / left  in AR)
 *
 *  SceneView's Rotation(x, y, z) takes Euler angles in degrees, Y-up.
 * ═══════════════════════════════════════════════════════════════════════════════
 */

import com.example.mallar.data.GraphNode
import kotlin.math.*

/** Metres per map pixel.  Tune to your mall's real scale.
 *  0.05f means 1 pixel = 5 cm, so a 100-pixel corridor = 5 m. */
const val AR_SCALE: Float = 0.05f

/** Arrows float this many metres above the detected floor. */
const val ARROW_Y_OFFSET: Float = 0.05f

/** Spacing between consecutive arrow instances (metres). */
const val ARROW_SPACING_M: Float = 0.6f

/** Distance threshold for declaring a node reached (metres). */
const val ARRIVAL_THRESHOLD_M: Float = 1.5f

// ─────────────────────────────────────────────────────────────────────────────
//  Data classes
// ─────────────────────────────────────────────────────────────────────────────

/** A position in AR world space (metres), relative to the root anchor. */
data class ArPosition(val x: Float, val y: Float, val z: Float)

/** A single interpolated point along the path where an arrow is placed. */
data class ArrowPlacement(
    val position: ArPosition,
    /** Rotation around the Y axis (degrees) so the arrow faces direction of travel. */
    val yRotationDeg: Float,
    /** Index of the path segment this arrow belongs to (0 = first edge). */
    val segmentIndex: Int
)

// ─────────────────────────────────────────────────────────────────────────────
//  Transformer
// ─────────────────────────────────────────────────────────────────────────────

class ArCoordinateTransformer(
    private val startNode: GraphNode,
    private val scale: Float = AR_SCALE
) {

    // ── Map node → AR local position ─────────────────────────────────────────

    /**
     * Converts a [GraphNode]'s pixel position into an [ArPosition] relative
     * to the world origin anchor (which sits at [startNode]).
     *
     * Map  +X (right)  →  AR +X (right)
     * Map  +Y (down)   →  AR +Z (toward user / south)
     */
    fun toArLocal(node: GraphNode): ArPosition {
        val arX = (node.x.toFloat() - startNode.x.toFloat()) * scale
        val arZ = (node.y.toFloat() - startNode.y.toFloat()) * scale
        return ArPosition(arX, ARROW_Y_OFFSET, arZ)
    }

    // ── Y-rotation so an arrow faces from A → B ───────────────────────────────

    /**
     * Returns the Y-axis rotation angle (degrees) so that an object placed at
     * [from] will visually point toward [to].
     *
     * In ARCore / SceneView (Y-up right-hand):
     *   atan2(dx, dz) gives angle from +Z axis rotating toward +X axis.
     *   0°   → facing +Z (south / down on map)
     *   90°  → facing +X (east  / right on map)
     *  -90°  → facing -X (west  / left on map)
     *  180°  → facing -Z (north / up on map)
     *
     * The GLB model's "forward" direction at rest determines whether an
     * additional offset is needed.  Adjust ARROW_GLB_FORWARD_OFFSET below
     * if arrows point the wrong way.
     */
    fun yRotationDeg(from: ArPosition, to: ArPosition): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val angle = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
        return (angle + ARROW_GLB_FORWARD_OFFSET + 360f) % 360f
    }

    // ── Arrow interpolation ───────────────────────────────────────────────────

    /**
     * Given an ordered list of [GraphNode]s representing the full A* path,
     * returns a list of [ArrowPlacement]s — one every [ARROW_SPACING_M]
     * metres along each segment.
     */
    fun computeArrowPlacements(pathNodes: List<GraphNode>): List<ArrowPlacement> {
        if (pathNodes.size < 2) return emptyList()

        val result = mutableListOf<ArrowPlacement>()

        for (segIdx in 0 until pathNodes.size - 1) {
            val n1 = pathNodes[segIdx]
            val n2 = pathNodes[segIdx + 1]

            val p1 = toArLocal(n1)
            val p2 = toArLocal(n2)

            val dx     = p2.x - p1.x
            val dz     = p2.z - p1.z
            val segLen = sqrt(dx * dx + dz * dz)
            if (segLen < 0.001f) continue          // skip degenerate segments

            val normX = dx / segLen
            val normZ = dz / segLen
            val yRot  = yRotationDeg(p1, p2)

            // Place at least 1 arrow per segment; space them ARROW_SPACING_M apart
            val arrowCount = (segLen / ARROW_SPACING_M).toInt().coerceAtLeast(1)

            for (k in 0 until arrowCount) {
                val t = (k + 0.5f) * ARROW_SPACING_M
                result += ArrowPlacement(
                    position     = ArPosition(p1.x + normX * t, ARROW_Y_OFFSET, p1.z + normZ * t),
                    yRotationDeg = yRot,
                    segmentIndex = segIdx
                )
            }
        }

        return result
    }

    // ── Distance helpers ──────────────────────────────────────────────────────

    /** Horizontal distance (metres) between two [ArPosition]s (ignores Y). */
    fun horizontalDistance(a: ArPosition, b: ArPosition): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    /**
     * Horizontal distance from the camera's AR position (translated relative
     * to the anchor) to a target [ArPosition].
     */
    fun distanceFromCamera(cameraTx: Float, cameraTz: Float, target: ArPosition): Float {
        val dx = cameraTx - target.x
        val dz = cameraTz - target.z
        return sqrt(dx * dx + dz * dz)
    }

    companion object {
        /**
         * Additional rotation to apply because the GLB model's "forward" axis
         * at rest may not be +Z.  Common values:
         *   0f   — model's tip already points  +Z (toward user / south on map)
         *   90f  — model's tip points  +X at rest
         *   180f — model's tip points  -Z at rest  (most common for glTF arrows)
         *  -90f  — model's tip points  -X at rest
         */
        const val ARROW_GLB_FORWARD_OFFSET = 0f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Quaternion helper  (no external math library required)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns a [FloatArray] of size 4 representing a quaternion [x, y, z, w]
 * for a rotation of [angleDeg] degrees around the world Y-axis.
 */
fun quaternionAroundY(angleDeg: Float): FloatArray {
    val half = Math.toRadians(angleDeg.toDouble()) / 2.0
    val sinH = sin(half).toFloat()
    val cosH = cos(half).toFloat()
    return floatArrayOf(0f, sinH, 0f, cosH) // [x, y, z, w]
}