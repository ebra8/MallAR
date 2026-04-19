package com.example.mallar.ar

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 *  ArCoordinateTransformer
 *  ────────────────────────────────────────────────────────────────────────────
 *  Converts 2-D mall-map pixel coordinates into ARCore world-space metres,
 *  and provides quaternion rotation helpers for arrow orientation.
 *
 *  Coordinate systems
 *  ─────────────────────
 *  Map space   : (x, y) in pixels, origin = top-left of image.
 *                +X = right,  +Y = down
 *
 *  ARCore space: right-handed, gravity-aligned.
 *                +X = right,  +Y = up,  +Z = toward user (screen outward)
 *
 *  Mapping
 *  ──────────────────
 *      arX =  (mapX - originMapX) * scale       (East  in AR = East  in map)
 *      arZ =  (mapY - originMapY) * scale       (South in AR = Down  in map)
 *
 *  Arrows lie on the floor plane  →  arY is fixed at ARROW_Y_OFFSET.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */

import com.example.mallar.data.GraphNode
import kotlin.math.*

/** Metres per map pixel.  Tune to your mall's real scale. */
const val AR_SCALE: Float = 0.25f

/** Arrows float this many metres above the detected floor. */
const val ARROW_Y_OFFSET: Float = 0.02f

/** Spacing between consecutive arrow instances (metres). */
const val ARROW_SPACING_M: Float = 0.5f

/** Distance threshold for declaring a node reached (metres). */
const val ARRIVAL_THRESHOLD_M: Float = 1.2f

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
     */
    fun toArLocal(node: GraphNode): ArPosition {
        val arX = (node.x.toFloat() - startNode.x.toFloat()) * scale
        val arZ = (node.y.toFloat() - startNode.y.toFloat()) * scale
        return ArPosition(arX, ARROW_Y_OFFSET, arZ)
    }

    // ── Y-rotation so an arrow faces from A → B ───────────────────────────────

    /**
     * Returns the Y-axis rotation angle (degrees, clockwise) so that an object
     * placed at [from] faces [to].
     *
     * In ARCore's right-hand coordinate system:
     *   0°   = −Z axis (forward / away from camera default)
     *   90°  = +X axis (right)
     *  180°  = +Z axis (back)
     *  −90°  = −X axis (left)
     */
    fun yRotationDeg(from: ArPosition, to: ArPosition): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return Math.toDegrees(atan2(dx.toDouble(), -dz.toDouble())).toFloat()
    }

    // ── Arrow interpolation ───────────────────────────────────────────────────

    /**
     * Given an ordered list of [GraphNode]s representing the full path,
     * returns a list of [ArrowPlacement]s — one every [ARROW_SPACING_M]
     * metres along each segment.
     *
     * @param pathNodes  ordered list of path nodes (startNode first)
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
            if (segLen < 0.01f) continue

            val normX = dx / segLen
            val normZ = dz / segLen
            val yRot  = yRotationDeg(p1, p2)

            val arrowCount = (segLen / ARROW_SPACING_M).toInt().coerceAtLeast(1)

            for (k in 0 until arrowCount) {
                // Centre each arrow within its spacing slot
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

    /** Horizontal distance from a camera-pose translation to an [ArPosition]. */
    fun distanceFromCamera(cameraTx: Float, cameraTz: Float, target: ArPosition): Float {
        val dx = cameraTx - target.x
        val dz = cameraTz - target.z
        return sqrt(dx * dx + dz * dz)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Quaternion helper  (no external math library required)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns a [FloatArray] of size 4 representing a quaternion [x, y, z, w]
 * for a rotation of [angleDeg] degrees around the world Y-axis.
 *
 * Use this if your scene framework expects raw quaternions instead of Euler
 * angles.  SceneView's [Rotation] accepts Euler angles directly, so this
 * is only needed if you work at a lower level.
 */
fun quaternionAroundY(angleDeg: Float): FloatArray {
    val half = Math.toRadians(angleDeg.toDouble()) / 2.0
    val sinH = sin(half).toFloat()
    val cosH = cos(half).toFloat()
    return floatArrayOf(0f, sinH, 0f, cosH) // [x, y, z, w]
}