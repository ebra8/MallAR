package com.example.mallar.navigation

import android.util.Log
import com.example.mallar.data.GraphNode
import com.example.mallar.data.MallGraph
import com.example.mallar.data.MallGraphRepository
import com.example.mallar.overlay.OverlayProjectionEngine
import com.example.mallar.overlay.ProjectedPoint
import com.example.mallar.overlay.TurnInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "NavSessionManager"

enum class NavMode { MAP, CAMERA }

data class NavSessionState(
    val pathNodes: List<GraphNode>    = emptyList(),
    val segmentIdx: Int               = 0,
    val destinationName: String       = "",
    val userMapX: Float               = 0f,
    val userMapY: Float               = 0f,
    val headingDeg: Float             = 0f,
    val remainingDistanceM: Int       = 0,
    val walkMinutes: Int              = 0,
    val mode: NavMode                 = NavMode.MAP,
    val projectedPoints: List<ProjectedPoint> = emptyList(),
    val turnInfo: TurnInfo?           = null,
    val isRerouting: Boolean          = false,
    val isArrived: Boolean            = false,
    val isOnPath: Boolean             = true,
    val waypointMessage: String?      = null,
    val totalSteps: Long              = 0L,
    val driftLevel: DriftMonitor.DriftLevel = DriftMonitor.DriftLevel.OK,
    val relocReason: String?          = null
)

class NavigationSessionManager(
    private val mallGraph: MallGraph,
    private val pxPerMetre: Float = 4.48f  // Calibrated: 208 px = 46.5 m (Bershka→OXXO)
) {

    private val _sessionState = MutableStateFlow(NavSessionState())
    val sessionState: StateFlow<NavSessionState> = _sessionState.asStateFlow()

    private var positionTracker: IndoorPositionTracker? = null
    private val projectionEngine = OverlayProjectionEngine()
    private val pathSnapper      = PathSnapper()
    private val driftMonitor     = DriftMonitor()
    private val smoother         = PositionSmoother()

    var onRerouteNeeded: (() -> Unit)? = null
    var onArrived: (() -> Unit)? = null
    var onRelocalizationNeeded: ((reason: String) -> Unit)? = null

    fun initialize(pathNodes: List<GraphNode>, destinationName: String) {
        if (pathNodes.size < 2) {
            Log.w(TAG, "Path too short — cannot initialise")
            return
        }

        val startNode = pathNodes.first()
        positionTracker = IndoorPositionTracker(mallGraph, startNode).also { tracker ->
            tracker.onPositionUpdated = { posX, posY -> onPositionUpdated(posX, posY) }
        }

        driftMonitor.onRelocalizationNeeded = { reason ->
            _sessionState.update { it.copy(relocReason = reason) }
            onRelocalizationNeeded?.invoke(reason)
        }

        val distM = (totalPathDistancePx(pathNodes) / pxPerMetre).roundToInt().coerceAtLeast(1)
        val mins  = (distM / 80f).coerceAtLeast(1f).roundToInt()

        _sessionState.update {
            it.copy(
                pathNodes          = pathNodes,
                destinationName    = destinationName,
                userMapX           = startNode.x.toFloat(),
                userMapY           = startNode.y.toFloat(),
                remainingDistanceM = distM,
                walkMinutes        = mins
            )
        }

        Log.d(TAG, "Initialised: ${pathNodes.size} nodes, dest=$destinationName")
        recomputeAndEmit()
    }

    fun destroy() {
        positionTracker = null
        onRerouteNeeded = null
        onArrived       = null
        onRelocalizationNeeded = null
        pathSnapper.reset()
        smoother.reset()
        driftMonitor.onRelocalizationNeeded = null
        Log.d(TAG, "Destroyed")
    }

    fun onHeadingUpdated(azimuthDeg: Float) {
        positionTracker?.currentHeadingDeg = azimuthDeg
        _sessionState.update { it.copy(headingDeg = azimuthDeg) }
        recomputeProjectionOnly()
    }

    fun onStep(totalSteps: Long) {
        // StepTracker already debounces at 400 ms (hardware) or STEP_DEBOUNCE_MS
        // (software fallback). A second gate here only silently drops valid steps.
        val stridePx = StepTracker.STRIDE_LENGTH_M * pxPerMetre
        positionTracker?.onStep(stridePx.toDouble())
        _sessionState.update { it.copy(totalSteps = totalSteps) }
        // Force map dot + AR projection to update immediately on every step
        recomputeForCurrentMode()
    }

    fun onLogoDetected(node: GraphNode) {
        val snapped = positionTracker?.relocalize(node) ?: false
        if (snapped) {
            Log.d(TAG, "Relocalized to '${node.shopName}'")
            pathSnapper.reset()
            smoother.reset()
            driftMonitor.onRelocalized()
            _sessionState.update { it.copy(relocReason = null, driftLevel = DriftMonitor.DriftLevel.OK) }
            recomputeAndEmit()
        }
    }

    fun switchMode(newMode: NavMode) {
        if (_sessionState.value.mode == newMode) return
        _sessionState.update { it.copy(mode = newMode) }
        Log.d(TAG, "Mode → $newMode")
        if (newMode == NavMode.CAMERA) recomputeAndEmit()
    }

    fun updatePath(newPath: List<GraphNode>) {
        if (newPath.size < 2) return
        val tracker  = positionTracker
        val nearNode = if (tracker != null) {
            MallGraphRepository.findNearestNode(mallGraph, tracker.posX, tracker.posY)
        } else null
        if (nearNode != null) tracker?.relocalize(nearNode)
        _sessionState.update {
            it.copy(pathNodes = newPath, segmentIdx = 0, isRerouting = false)
        }
        Log.d(TAG, "Path updated: ${newPath.size} nodes")
        recomputeAndEmit()
    }

    fun setRerouting(active: Boolean) {
        _sessionState.update { it.copy(isRerouting = active) }
    }

    fun setScreenSize(w: Float, h: Float, fovDeg: Float = 68f) {
        projectionEngine.screenW = w
        projectionEngine.screenH = h
        projectionEngine.fovDeg  = fovDeg
        recomputeProjectionOnly()
    }

    fun setWaypointMessage(msg: String?) {
        _sessionState.update { it.copy(waypointMessage = msg) }
    }

    private fun onPositionUpdated(rawPosX: Double, rawPosY: Double) {
        val state = _sessionState.value
        val path  = state.pathNodes

        val snapResult = pathSnapper.snap(rawPosX, rawPosY, path, state.segmentIdx)
        val snappedX   = snapResult.snappedX
        val snappedY   = snapResult.snappedY
        val newSeg     = snapResult.bestSegmentIdx.coerceAtLeast(state.segmentIdx)

        driftMonitor.onStep(
            posX        = snappedX,
            posY        = snappedY,
            isOnPath    = snapResult.isOnPath,
            deviationPx = snapResult.deviationPx,
            headingDeg  = state.headingDeg
        )
        val drift = driftMonitor.driftState

        val (smoothX, smoothY) = smoother.smoothPosition(snappedX, snappedY)
        // Use live tracker heading so each step uses the same heading as dead reckoning,
        // not a possibly stale snapshot from the last StateFlow update.
        val headingForSmooth =
            positionTracker?.currentHeadingDeg ?: state.headingDeg
        val smoothHeading = smoother.smoothHeading(headingForSmooth)

        val finalNode = path.lastOrNull()
        if (finalNode != null) {
            val dx = finalNode.x - smoothX; val dy = finalNode.y - smoothY
            if (sqrt(dx * dx + dy * dy) < ARRIVAL_THRESHOLD_PX) {
                if (!state.isArrived) {
                    _sessionState.update { it.copy(isArrived = true) }
                    onArrived?.invoke()
                }
                return
            }
        }

        val isOnPath = snapResult.isOnPath
        if (!isOnPath && snapResult.deviationPx > REROUTE_THRESHOLD_PX && !state.isRerouting) {
            _sessionState.update { it.copy(isRerouting = true, isOnPath = false) }
            onRerouteNeeded?.invoke()
        }

        val remainPx = computeRemainingDistancePx(path, newSeg, smoothX, smoothY)
        val remainM  = (remainPx / pxPerMetre).roundToInt().coerceAtLeast(0)
        val mins     = if (remainM > 0) (remainM / 80f).coerceAtLeast(1f).roundToInt() else 0

        _sessionState.update {
            it.copy(
                userMapX           = smoothX.toFloat(),
                userMapY           = smoothY.toFloat(),
                headingDeg         = smoothHeading,
                segmentIdx         = newSeg,
                remainingDistanceM = remainM,
                walkMinutes        = mins,
                isOnPath           = isOnPath,
                driftLevel         = drift.level,
                relocReason        = if (drift.relocNeeded) drift.relocReason else it.relocReason
            )
        }

        recomputeAndEmit()
    }

    /** Recompute overlay projection (camera mode) and turn hints for both modes. */
    private fun recomputeForCurrentMode() {
        val state = _sessionState.value
        val path = state.pathNodes
        if (path.size < 2) return

        val seg = state.segmentIdx
        val nextNode = path.getOrNull(seg + 1)
        val turnInfo = if (nextNode != null) {
            projectionEngine.computeTurnInfo(
                nextNodeMapX = nextNode.x.toFloat(),
                nextNodeMapY = nextNode.y.toFloat(),
                userMapX     = state.userMapX,
                userMapY     = state.userMapY,
                headingDeg   = state.headingDeg
            )
        } else null

        if (state.mode == NavMode.CAMERA) {
            val projected = projectionEngine.project(
                pathNodes         = path,
                userMapX          = state.userMapX,
                userMapY          = state.userMapY,
                headingDeg        = state.headingDeg,
                lookaheadStartIdx = seg
            )
            _sessionState.update {
                it.copy(projectedPoints = projected, turnInfo = turnInfo)
            }
        } else {
            _sessionState.update { it.copy(turnInfo = turnInfo) }
        }
    }

    // Keep old name as alias so all existing callers still compile
    private fun recomputeAndEmit() = recomputeForCurrentMode()

    private fun recomputeProjectionOnly() {
        val state = _sessionState.value
        if (state.mode != NavMode.CAMERA) return
        recomputeForCurrentMode()
    }

    private fun computeRemainingDistancePx(
        path: List<GraphNode>, fromIdx: Int, userX: Double, userY: Double
    ): Float {
        if (path.size < 2 || fromIdx >= path.size - 1) return 0f
        var d = 0.0
        for (i in fromIdx until path.size - 1) {
            val a = path[i]; val b = path[i + 1]
            d += sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
        }
        return d.toFloat()
    }

    private fun totalPathDistancePx(path: List<GraphNode>): Float {
        var d = 0f
        for (i in 0 until path.size - 1) {
            val a = path[i]; val b = path[i + 1]
            val dx = (a.x - b.x).toFloat(); val dy = (a.y - b.y).toFloat()
            d += sqrt(dx * dx + dy * dy)
        }
        return d
    }

    companion object {
        // Thresholds in map pixels, calibrated to pxPerMetre = 4.48
        // ARRIVAL  ~2 m  → 2 × 4.48 = ~9 px
        // REROUTE  ~4 m  → 4 × 4.48 = ~18 px
        private const val ARRIVAL_THRESHOLD_PX = 9.0
        private const val REROUTE_THRESHOLD_PX = 18.0

        // SINGLETON RESET FIX: var instead of lazy — fully replaced each trip
        @Volatile
        private var _instance: NavigationSessionManager? = null

        val instance: NavigationSessionManager
            get() = _instance
                ?: throw IllegalStateException(
                    "NavigationSessionManager not initialised — call reset() first"
                )

        fun reset() {
            _instance?.destroy()
            _instance = NavigationSessionManager(
                mallGraph  = MallGraphRepository.loadedGraph
                    ?: throw IllegalStateException("Graph not loaded"),
                pxPerMetre = 4.48f  // Calibrated: 208 px = 46.5 m (Bershka→OXXO)
            )
        }
    }
}