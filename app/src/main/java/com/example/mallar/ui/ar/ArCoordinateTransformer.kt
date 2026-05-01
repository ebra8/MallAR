package com.example.mallar.ar

import com.example.mallar.data.GraphNode
import kotlin.math.*

// ── Scale ─────────────────────────────────────────────────────────────────────
/** Metres per map pixel. 1 px = 5 cm → typical mall corridor of 100 px = 5 m. */
const val AR_SCALE: Float = 0.05f

// ── Arrow placement ───────────────────────────────────────────────────────────
const val ARROW_Y_OFFSET: Float  = 0.05f   // 5 cm above the floor
const val ARROW_SPACING_M: Float = 1.2f    // one arrow every 1.2 m

// ─────────────────────────────────────────────────────────────────────────────
data class ArPosition(val x: Float, val y: Float, val z: Float)

data class ArrowPlacement(
    val position: ArPosition,
    val yRotationDeg: Float,
    val segmentIndex: Int
)

// ─────────────────────────────────────────────────────────────────────────────
class ArCoordinateTransformer(
    private val startNode: GraphNode,
    private val scale: Float = AR_SCALE,
    /**
     * World rotation offset (degrees, clockwise = positive).
     * Set from the heading-alignment UI once the user confirms direction.
     * 0° until confirmed.
     */
    var headingOffsetDeg: Float = 0f
) {

    // ── Map node → AR local position ─────────────────────────────────────────
    /**
     * Convert a map node to an AR local position.
     *
     * Map convention  : X = right, Y = down  (screen/image coordinates)
     * AR convention   : X = right, Z = away from camera (-Z = forward)
     *
     * So:   AR_X = map_ΔX * scale
     *       AR_Z = map_ΔY * scale      (note: NO negation — explained below)
     *
     * When headingOffsetDeg is applied the whole path rotates so the
     * first segment lines up with the real corridor the user confirmed.
     *
     * The rotation uses the STANDARD Y-axis rotation matrix
     * (CCW positive when viewed from above, right-hand Y-up):
     *   x' =  rawX * cos(θ) + rawZ * sin(θ)
     *   z' = -rawX * sin(θ) + rawZ * cos(θ)
     */
    fun toArLocal(node: GraphNode): ArPosition {
        val rawX = (node.x.toFloat() - startNode.x.toFloat()) * scale
        val rawZ = (node.y.toFloat() - startNode.y.toFloat()) * scale
        val rad  = Math.toRadians(headingOffsetDeg.toDouble())
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()
        return ArPosition(
            x =  rawX * cosR + rawZ * sinR,
            y = ARROW_Y_OFFSET,
            z = -rawX * sinR + rawZ * cosR
        )
    }

    // ── Node snapping ────────────────────────────────────────────────────────
    /**
     * Find the nearest graph node to the given AR camera position.
     * Returns the index into [pathNodes] and the distance in metres.
     */
    fun snapToNearestNode(
        camArX: Float,
        camArZ: Float,
        pathNodes: List<GraphNode>
    ): Pair<Int, Float> {
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        for (i in pathNodes.indices) {
            val arPos = toArLocal(pathNodes[i])
            val dx = camArX - arPos.x
            val dz = camArZ - arPos.z
            val d = sqrt(dx * dx + dz * dz)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return Pair(bestIdx, bestDist)
    }

    /**
     * Find the nearest node from the FULL graph (not just path) to snap
     * a detected location to the closest walkable node.
     */
    fun snapToNearestGraphNode(
        detectedNode: GraphNode,
        allNodes: List<GraphNode>
    ): GraphNode {
        var bestNode = detectedNode
        var bestDist = Double.MAX_VALUE
        for (node in allNodes) {
            val dx = detectedNode.x - node.x
            val dy = detectedNode.y - node.y
            val d = sqrt(dx * dx + dy * dy)
            if (d < bestDist) {
                bestDist = d
                bestNode = node
            }
        }
        return bestNode
    }

    // ── Map pixel ↔ screen pixel conversion (for StaticMapScreen) ────────────
    /**
     * Convert a graph node's map-pixel coordinates to screen coordinates
     * given the current canvas transform (scale + offset).
     */
    fun mapToScreen(
        node: GraphNode,
        canvasScale: Float,
        offsetX: Float,
        offsetY: Float
    ): Pair<Float, Float> {
        val screenX = node.x.toFloat() * canvasScale + offsetX
        val screenY = node.y.toFloat() * canvasScale + offsetY
        return Pair(screenX, screenY)
    }

    // ── Compute all arrow placements ─────────────────────────────────────────
    /**
     * Place arrows along the full path using a **direct bearing approach**.
     *
     * For each segment we:
     *  1. Compute the segment's raw map bearing (atan2 in map pixel space).
     *  2. Add headingOffsetDeg to get the real-world AR bearing.
     *  3. Place arrows at positions computed with sin/cos of that bearing.
     *  4. Set arrow rotation so the GLB tip points along that bearing.
     *
     * Positions accumulate from segment to segment so the path is continuous.
     */
    fun computeArrowPlacements(pathNodes: List<GraphNode>): List<ArrowPlacement> {
        if (pathNodes.size < 2) return emptyList()
        val result = mutableListOf<ArrowPlacement>()

        var curX = 0f   // cumulative position along path in AR space
        var curZ = 0f

        for (segIdx in 0 until pathNodes.size - 1) {
            val n1 = pathNodes[segIdx]
            val n2 = pathNodes[segIdx + 1]

            // Raw delta in map pixel space (Y is downward in map → +Z in AR by convention)
            val mapDX = (n2.x - n1.x).toFloat() * scale
            val mapDZ = (n2.y - n1.y).toFloat() * scale
            val segLen = sqrt(mapDX * mapDX + mapDZ * mapDZ)
            if (segLen < 0.001f) continue

            // ── Bearing in map space, then rotated by the confirmed heading ───
            // atan2(x, z) gives bearing measured clockwise from +Z (= "south on map")
            val rawBearingDeg = Math.toDegrees(atan2(mapDX.toDouble(), mapDZ.toDouble())).toFloat()
            val worldBearingDeg = rawBearingDeg + headingOffsetDeg
            val worldRad = Math.toRadians(worldBearingDeg.toDouble())

            // AR direction for this segment
            val dirX = sin(worldRad).toFloat()
            val dirZ = cos(worldRad).toFloat()

            // Arrow GLB rotation: bearing → model rotation accounting for model's forward axis
            val arrowRotation = (worldBearingDeg + ARROW_GLB_FORWARD_OFFSET + 360f) % 360f

            val arrowCount = (segLen / ARROW_SPACING_M).toInt().coerceAtLeast(1)
            for (k in 0 until arrowCount) {
                val t = (k + 0.5f) * ARROW_SPACING_M
                result += ArrowPlacement(
                    position     = ArPosition(curX + dirX * t, ARROW_Y_OFFSET, curZ + dirZ * t),
                    yRotationDeg = arrowRotation,
                    segmentIndex = segIdx
                )
            }

            // Advance start of next segment
            curX += dirX * segLen
            curZ += dirZ * segLen
        }
        return result
    }

    // ── Distance helper ───────────────────────────────────────────────────────
    fun distanceFromCamera(cameraTx: Float, cameraTz: Float, target: ArPosition): Float {
        val dx = cameraTx - target.x
        val dz = cameraTz - target.z
        return sqrt(dx * dx + dz * dz)
    }

    companion object {
        /**
         * nav_arrow.glb forward axis offset.
         * Standard glTF faces -Z → add 180° so tip points toward travel direction.
         * Change to 0f / 90f / -90f if your specific GLB is oriented differently.
         */
        const val ARROW_GLB_FORWARD_OFFSET = 180f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
fun quaternionAroundY(angleDeg: Float): FloatArray {
    val half = Math.toRadians(angleDeg.toDouble()) / 2.0
    return floatArrayOf(0f, sin(half).toFloat(), 0f, cos(half).toFloat())
}
