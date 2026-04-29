package com.example.mallar.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlin.math.atan2
import kotlin.math.sqrt

// ── Graph data structures ────────────────────────────────────────────────────

data class GraphNode(
    @SerializedName("id")       val id: Int,
    @SerializedName("x")        val x: Double,
    @SerializedName("y")        val y: Double,
    @SerializedName("shopId")   val shopId: Int?,
    @SerializedName("shopName") val shopName: String?,
    @SerializedName("logo")     val logo: String?
)

data class GraphEdge(
    @SerializedName("from") val from: Int,
    @SerializedName("to")   val to: Int
)

data class MallGraph(
    @SerializedName("nodes") val nodes: List<GraphNode>,
    @SerializedName("edges") val edges: List<GraphEdge>
)

// ── A* path result ───────────────────────────────────────────────────────────

data class AStarPath(
    val nodeIds: List<Int>,          // ordered node IDs on the path
    val totalDistancePx: Double,     // total path length in map pixels
    val steps: List<NavInstruction>  // consolidated turn-by-turn instructions
)

data class NavInstruction(
    val direction: AStarDirection,
    val distancePx: Double,
    val nodeIndex: Int = 0           // which node in nodeIds this step starts at
)

enum class AStarDirection { STRAIGHT, LEFT, RIGHT, ARRIVED }

// ── Repository ───────────────────────────────────────────────────────────────

object MallGraphRepository {

    private var graph: MallGraph? = null
    var loadedGraph: MallGraph? = null  // public read-only access without context

    fun load(context: Context): MallGraph {
        graph?.let { return it }
        val json = context.assets.open("mall_graph.json").bufferedReader().use { it.readText() }
        val loaded = Gson().fromJson(json, MallGraph::class.java)
        graph = loaded
        loadedGraph = loaded
        return loaded
    }

    fun nodeForShop(graph: MallGraph, shopId: Int): GraphNode? =
        graph.nodes.firstOrNull { it.shopId == shopId }

    /** Look up a GraphNode from the path's nodeIds list by sequential index */
    fun nodeAtPathIndex(graph: MallGraph, path: AStarPath, index: Int): GraphNode? {
        val nodeId = path.nodeIds.getOrNull(index) ?: return null
        return graph.nodes.firstOrNull { it.id == nodeId }
    }

    // ── Public A* entry point ────────────────────────────────────────────────

    fun aStar(graph: MallGraph, startShopId: Int, endShopId: Int): AStarPath? {
        val startNode = nodeForShop(graph, startShopId) ?: return null
        val endNode   = nodeForShop(graph, endShopId)   ?: return null
        return runAStar(graph, startNode.id, endNode.id)
    }

    // ── Core A* with Euclidean edge weights ───────────────────────────────────

    private fun runAStar(graph: MallGraph, startId: Int, goalId: Int): AStarPath? {
        val nodeMap = graph.nodes.associateBy { it.id }
        val goal    = nodeMap[goalId] ?: return null

        // Build undirected adjacency with Euclidean pixel distances as weights
        val adj = mutableMapOf<Int, MutableList<Pair<Int, Double>>>()
        for (e in graph.edges) {
            val a = nodeMap[e.from] ?: continue
            val b = nodeMap[e.to]   ?: continue
            val w = euclidean(a, b)
            adj.getOrPut(e.from) { mutableListOf() }.add(Pair(e.to,   w))
            adj.getOrPut(e.to)   { mutableListOf() }.add(Pair(e.from, w))
        }

        val gCost    = HashMap<Int, Double>()
        val cameFrom = HashMap<Int, Int>()
        gCost[startId] = 0.0

        // Priority queue sorted by fCost = g + h
        val open = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        open.add(Pair(heuristic(nodeMap[startId]!!, goal), startId))

        val closed = HashSet<Int>()

        while (open.isNotEmpty()) {
            val (_, current) = open.poll()!!
            if (current == goalId) return reconstructPath(cameFrom, nodeMap, startId, goalId)
            if (!closed.add(current)) continue

            for ((neighbor, w) in adj[current] ?: emptyList()) {
                if (neighbor in closed) continue
                val tentativeG = (gCost[current] ?: Double.MAX_VALUE) + w
                if (tentativeG < (gCost[neighbor] ?: Double.MAX_VALUE)) {
                    cameFrom[neighbor] = current
                    gCost[neighbor] = tentativeG
                    open.add(Pair(tentativeG + heuristic(nodeMap[neighbor]!!, goal), neighbor))
                }
            }
        }
        return null
    }

    private fun heuristic(a: GraphNode, b: GraphNode) = euclidean(a, b)

    private fun euclidean(a: GraphNode, b: GraphNode): Double {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    // ── Path reconstruction ──────────────────────────────────────────────────

    private fun reconstructPath(
        cameFrom: Map<Int, Int>,
        nodeMap: Map<Int, GraphNode>,
        startId: Int,
        goalId: Int
    ): AStarPath {
        val path = mutableListOf<Int>()
        var current = goalId
        while (current != startId) {
            path.add(current)
            current = cameFrom[current] ?: break
        }
        path.add(startId)
        path.reverse()

        var total = 0.0
        for (i in 0 until path.size - 1) {
            total += euclidean(nodeMap[path[i]]!!, nodeMap[path[i + 1]]!!)
        }

        return AStarPath(path, total, buildConsolidatedInstructions(path, nodeMap))
    }

    // ── Consolidated instruction builder ─────────────────────────────────────
    // Merges consecutive straight segments so the user gets meaningful turn cards
    // instead of one card per node.

    private fun buildConsolidatedInstructions(
        path: List<Int>,
        nodeMap: Map<Int, GraphNode>
    ): List<NavInstruction> {
        if (path.size < 2) return listOf(NavInstruction(AStarDirection.ARRIVED, 0.0, 0))

        val TURN_THRESHOLD = 45.0  // degrees — 45° gives clean corridor turns without false positives

        val instructions = mutableListOf<NavInstruction>()
        var currentDir  = AStarDirection.STRAIGHT
        var accumulatedDist = 0.0
        var segNodeIndex = 0

        for (i in 0 until path.size - 1) {
            val a = nodeMap[path[i]]!!
            val b = nodeMap[path[i + 1]]!!
            val segDist = euclidean(a, b)

            val dir: AStarDirection = if (i == 0) {
                AStarDirection.STRAIGHT
            } else {
                val prev = nodeMap[path[i - 1]]!!
                val angle = angleChange(prev, a, b)
                when {
                    angle > TURN_THRESHOLD  -> AStarDirection.RIGHT
                    angle < -TURN_THRESHOLD -> AStarDirection.LEFT
                    else -> AStarDirection.STRAIGHT
                }
            }

            if (i == 0) {
                currentDir = dir
                segNodeIndex = 0
                accumulatedDist = segDist
            } else if (dir == AStarDirection.STRAIGHT && currentDir == AStarDirection.STRAIGHT) {
                // Continue accumulating straight distance
                accumulatedDist += segDist
            } else {
                // Emit the previous segment
                instructions.add(NavInstruction(currentDir, accumulatedDist, segNodeIndex))
                // Start new segment
                currentDir = dir
                segNodeIndex = i
                accumulatedDist = segDist
            }
        }

        // Emit the last segment
        if (accumulatedDist > 0) {
            instructions.add(NavInstruction(currentDir, accumulatedDist, segNodeIndex))
        }

        // Final arrived
        instructions.add(NavInstruction(AStarDirection.ARRIVED, 0.0, path.size - 1))

        return instructions
    }

    private fun angleChange(prev: GraphNode, cur: GraphNode, next: GraphNode): Double {
        val v1x = cur.x - prev.x; val v1y = cur.y - prev.y
        val v2x = next.x - cur.x; val v2y = next.y - cur.y
        val cross = v1x * v2y - v1y * v2x
        val dot   = v1x * v2x + v1y * v2y
        return Math.toDegrees(atan2(cross, dot))
    }
}