package com.example.mallar.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mallar.data.AStarDirection
import com.example.mallar.data.AStarPath
import com.example.mallar.data.GraphNode
import com.example.mallar.data.MallGraphRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val DISPLAY_SCALE = 0.05f
private const val M_PER_MIN = 80f

data class StepItem(
    val label: String,
    val isWaypoint: Boolean,
    val nodeIndex: Int?          // non-null for waypoints
)

data class ArNavigationUiState(
    val pathNodes: List<GraphNode> = emptyList(),
    val aStarPath: AStarPath? = null,
    val destinationNode: GraphNode? = null,
    val distanceM: Int = 0,
    val walkMinutes: Int = 0,
    val statusText: String = "📷 Point at the floor and move slowly…",
    val segmentIndex: Int = 0,
    val showRouteSheet: Boolean = false,
    val showStoreCard: Boolean = true,
    val showAlignmentUi: Boolean = false,
    val alignmentAngle: Float = 0f,
    val reachedNotification: String? = null,
    val routeSteps: List<StepItem> = emptyList(),
    val currentInstructionLabel: String = ""
)

class ArNavigationViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ArNavigationUiState())
    val uiState: StateFlow<ArNavigationUiState> = _uiState.asStateFlow()

    init {
        initializePathData()
    }

    private fun initializePathData() {
        val path = NavigationState.aStarPath
        val graph = MallGraphRepository.loadedGraph

        if (path == null || graph == null) {
            updateStatusText("❌ No navigation path loaded")
            return
        }

        val nodes = path.nodeIds.mapNotNull { id -> graph.nodes.firstOrNull { it.id == id } }
        val destNode = nodes.lastOrNull()

        val distM = ((path.totalDistancePx) * DISPLAY_SCALE).roundToInt().coerceAtLeast(1)
        val walkMin = (distM / M_PER_MIN).coerceAtLeast(1f).roundToInt()

        val steps = buildStepItems(nodes, path)
        val instructionLabel = buildInstructionLabel(nodes, path, 0)

        _uiState.update { currentState ->
            currentState.copy(
                pathNodes = nodes,
                aStarPath = path,
                destinationNode = destNode,
                distanceM = distM,
                walkMinutes = walkMin,
                routeSteps = steps,
                currentInstructionLabel = instructionLabel
            )
        }
    }

    fun updateStatusText(newText: String) {
        _uiState.update { it.copy(statusText = newText) }
    }

    fun requestHeadingAlignment() {
        _uiState.update { it.copy(showAlignmentUi = true) }
    }

    fun updateAlignmentAngle(angle: Float) {
        _uiState.update { it.copy(alignmentAngle = angle) }
    }

    fun confirmAlignment() {
        _uiState.update { it.copy(showAlignmentUi = false) }
    }

    fun toggleRouteSheet() {
        _uiState.update { it.copy(showRouteSheet = !it.showRouteSheet) }
    }

    fun closeStoreCard() {
        _uiState.update { it.copy(showStoreCard = false) }
    }

    fun onNodeReached(reachedIdx: Int) {
        val nodes = _uiState.value.pathNodes
        val nodeName = nodes.getOrNull(reachedIdx)?.shopName ?: "Node $reachedIdx"
        
        val isArrived = reachedIdx >= nodes.size - 1
        val notificationText = if (isArrived) {
            updateStatusText("🏁 You have arrived!")
            "🏁 Arrived at $nodeName!"
        } else {
            "✅ Reached: $nodeName"
        }

        _uiState.update { currentState ->
            currentState.copy(
                segmentIndex = reachedIdx,
                reachedNotification = notificationText
            )
        }

        // Auto-dismiss the notification after 2.5 seconds
        viewModelScope.launch {
            delay(2500)
            if (_uiState.value.reachedNotification == notificationText) {
                _uiState.update { it.copy(reachedNotification = null) }
            }
        }
        
        updateInstructionLabel(reachedIdx)
    }

    private fun updateInstructionLabel(segmentIndex: Int) {
        val nodes = _uiState.value.pathNodes
        val path = _uiState.value.aStarPath
        _uiState.update { it.copy(currentInstructionLabel = buildInstructionLabel(nodes, path, segmentIndex)) }
    }

    private fun buildInstructionLabel(pathNodes: List<GraphNode>, aStarPath: AStarPath?, segmentIndex: Int): String {
        if (pathNodes.size < 2) return "▲  Head to Next Stop"
        val nextNode = pathNodes.getOrNull(segmentIndex + 1)
        val step = aStarPath?.steps?.getOrNull(segmentIndex)
        return when (step?.direction) {
            AStarDirection.LEFT    -> "↰  Turn Left"
            AStarDirection.RIGHT   -> "↱  Turn Right"
            AStarDirection.STRAIGHT -> "↑  Go Straight"
            AStarDirection.ARRIVED -> "🏁  You Arrived!"
            else -> "▲  Head to ${nextNode?.shopName ?: "Next Stop"}"
        }
    }

    private fun buildStepItems(
        pathNodes: List<GraphNode>,
        aStarPath: AStarPath?
    ): List<StepItem> {
        val items = mutableListOf<StepItem>()
        if (pathNodes.isEmpty()) return items

        val instructions = (aStarPath?.steps ?: emptyList())
            .filter { it.direction != AStarDirection.ARRIVED }

        // Always show start node
        items += StepItem(
            label      = pathNodes[0].shopName ?: "Start",
            isWaypoint = true,
            nodeIndex  = 0
        )

        // Walk through every instruction in A* order
        instructions.forEachIndexed { instrIdx, instr ->
            val nextInstrNodeIdx = instructions.getOrNull(instrIdx + 1)?.nodeIndex
                ?: pathNodes.size - 1

            // Named stores that sit between this instruction's nodeIndex and the
            // start of the NEXT instruction (exclusive)
            for (ni in instr.nodeIndex + 1 until nextInstrNodeIdx) {
                val n = pathNodes.getOrNull(ni) ?: continue
                if (n.shopName != null) {
                    items += StepItem(n.shopName, isWaypoint = true, nodeIndex = ni)
                }
            }

            // Direction step for this instruction
            val distM = (instr.distancePx * DISPLAY_SCALE).roundToInt().coerceAtLeast(1)
            val dirText = when (instr.direction) {
                AStarDirection.LEFT     -> "↰  Turn Left"
                AStarDirection.RIGHT    -> "↱  Turn Right"
                AStarDirection.STRAIGHT -> "↑  Go Straight  (~${distM}m)"
                else                    -> null
            }
            if (dirText != null) {
                items += StepItem(dirText, isWaypoint = false, nodeIndex = null)
            }
        }

        // Named stores after the last instruction (before destination)
        val lastInstrNodeIdx = instructions.lastOrNull()?.nodeIndex ?: 0
        for (ni in lastInstrNodeIdx + 1 until pathNodes.size - 1) {
            val n = pathNodes.getOrNull(ni) ?: continue
            if (n.shopName != null) {
                items += StepItem(n.shopName, isWaypoint = true, nodeIndex = ni)
            }
        }

        // Always show destination
        if (pathNodes.size >= 2) {
            items += StepItem(
                label      = pathNodes.last().shopName ?: "Destination",
                isWaypoint = true,
                nodeIndex  = pathNodes.size - 1
            )
        }

        return items
    }
}
