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

    // ── Auto-alignment with Compass ─────────────────────────────────────────────
    /**
     * Automatically computes headingOffsetDeg so the AR map is aligned to the 
     * real world using the device's hardware compass.
     */
    fun autoAlignWithCompass(compassAzimuth: Float, camDirX: Float, camDirZ: Float) {
        // Camera's CW angle from anchor's -Z axis
        val camArYawCw = Math.toDegrees(atan2(camDirX.toDouble(), -camDirZ.toDouble())).toFloat()
        
        // Let's define the map's north offset. 0 means Map Top (-Y) is True North.
        // If the Mall map is oriented differently, you can change this offset.
        val MAP_NORTH_OFFSET_DEG = 0f 
        
        headingOffsetDeg = compassAzimuth - camArYawCw - MAP_NORTH_OFFSET_DEG
    }

    // ── Map node → AR local position ─────────────────────────────────────────
    /**
     * Convert a map node to an AR local position.
     * Applies headingOffsetDeg to rotate the entire map relative to the anchor.
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
        val result = mutableListOf<ArrowPlacement>()
        if (pathNodes.size < 2) return result

        for (segIdx in 0 until pathNodes.size - 1) {
            val p1 = toArLocal(pathNodes[segIdx])
            val p2 = toArLocal(pathNodes[segIdx + 1])

            val dx = p2.x - p1.x
            val dz = p2.z - p1.z
            val segLen = sqrt(dx * dx + dz * dz)
            if (segLen < 0.001f) continue

            val dirX = dx / segLen
            val dirZ = dz / segLen

            // Bearing of this segment in AR world space (Filament uses Right-Hand Y-Up)
            // atan2(-x, -z) perfectly maps a (dirX, dirZ) vector to a Y-axis rotation.
            val worldBearingDeg = Math.toDegrees(atan2(-dirX.toDouble(), -dirZ.toDouble())).toFloat()

            // Arrow GLB rotation: bearing → model rotation
            val arrowRotation = worldBearingDeg

            val arrowCount = (segLen / ARROW_SPACING_M).toInt().coerceAtLeast(1)
            for (k in 0 until arrowCount) {
                val t = (k + 0.5f) * ARROW_SPACING_M
                result += ArrowPlacement(
                    position     = ArPosition(p1.x + dirX * t, ARROW_Y_OFFSET, p1.z + dirZ * t),
                    yRotationDeg = arrowRotation,
                    segmentIndex = segIdx
                )
            }
        }
        return result
    }

    companion object {
        // Constants
    }
}
