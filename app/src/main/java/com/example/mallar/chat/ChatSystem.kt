package com.example.mallar.chat

import com.example.mallar.data.AStarDirection
import com.example.mallar.data.AStarPath
import com.example.mallar.data.GraphNode
import com.example.mallar.data.MallGraph
import com.example.mallar.data.MallGraphRepository
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// 1. Chat message model
// ─────────────────────────────────────────────────────────────────────────────

enum class MessageSender { USER, BOT }

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val sender: MessageSender,
    val text: String,
    val mapPath: AStarPath? = null   // non-null → show mini-map inside this bubble
)

// ─────────────────────────────────────────────────────────────────────────────
// 2. Input parser — no AI API, pure string matching
//    Handles both Arabic and English
// ─────────────────────────────────────────────────────────────────────────────

data class ParsedQuery(val startName: String, val destName: String)

object ChatInputParser {

    /**
     * Returns a ParsedQuery if we can extract both endpoints, otherwise null.
     *
     * Supported patterns:
     *   EN: "how do I go to ZARA from BERSHKA"
     *       "navigate from BERSHKA to ZARA"
     *       "take me to ZARA from BERSHKA"
     *   AR: "ازاي اروح زارا من برشكا"
     *       "خد من برشكا لزارا"
     *       "روح [dest] من [start]"
     */
    fun parse(input: String): ParsedQuery? {
        val text = input.trim()

        // ── English patterns ─────────────────────────────────────────────────
        val enPatterns = listOf(
            // "... to DEST from START"
            Regex("""(?:go|navigate|take me|directions?|how (?:do i|to) (?:get|go))\s+to\s+(.+?)\s+from\s+(.+)""", RegexOption.IGNORE_CASE),
            // "... from START to DEST"
            Regex("""from\s+(.+?)\s+to\s+(.+)""", RegexOption.IGNORE_CASE),
            // "... to DEST"  (no start — return null for single-endpoint)
        )

        for (pattern in enPatterns) {
            val m = pattern.find(text) ?: continue
            return when (pattern.pattern.contains("from\\s+(.+?)\\s+to")) {
                true  -> ParsedQuery(startName = m.groupValues[1].trim(), destName = m.groupValues[2].trim())
                false -> ParsedQuery(startName = m.groupValues[2].trim(), destName = m.groupValues[1].trim())
            }
        }

        // ── Arabic patterns ──────────────────────────────────────────────────
        // "روح/اروح [DEST] من [START]"
        val arToFrom = Regex("""(?:اروح|روح|عايز اروح|أروح)\s+(.+?)\s+من\s+(.+)""")
        arToFrom.find(text)?.let {
            return ParsedQuery(startName = it.groupValues[2].trim(), destName = it.groupValues[1].trim())
        }

        // "من [START] ل/لـ [DEST]"  OR  "من [START] الى [DEST]"
        val arFromTo = Regex("""من\s+(.+?)\s+(?:ل|لـ|الى|إلى|لـ)\s+(.+)""")
        arFromTo.find(text)?.let {
            return ParsedQuery(startName = it.groupValues[1].trim(), destName = it.groupValues[2].trim())
        }

        // "ازاي اروح [DEST] من [START]"
        val arHowTo = Regex("""(?:ازاي|إزاي|كيف)\s+(?:اروح|أروح)\s+(.+?)\s+من\s+(.+)""")
        arHowTo.find(text)?.let {
            return ParsedQuery(startName = it.groupValues[2].trim(), destName = it.groupValues[1].trim())
        }

        return null
    }

    /**
     * Fuzzy-match a query string against all shop names in the graph.
     * Returns the best matching GraphNode or null.
     */
    fun matchNode(graph: MallGraph, query: String): GraphNode? {
        val q = query.trim().lowercase()

        // 1. Exact match
        graph.nodes.firstOrNull { it.shopName?.lowercase() == q }?.let { return it }

        // 2. Contains match (both directions)
        graph.nodes.firstOrNull {
            val name = it.shopName?.lowercase() ?: return@firstOrNull false
            name.contains(q) || q.contains(name)
        }?.let { return it }

        // 3. Remove spaces and retry
        val qNoSpace = q.replace(" ", "")
        graph.nodes.firstOrNull {
            val name = (it.shopName?.lowercase() ?: return@firstOrNull false).replace(" ", "")
            name.contains(qNoSpace) || qNoSpace.contains(name)
        }?.let { return it }

        // 4. Levenshtein distance ≤ 3 for typo tolerance
        return graph.nodes
            .filter { it.shopName != null }
            .minByOrNull { levenshtein(it.shopName!!.lowercase(), q) }
            ?.takeIf { levenshtein(it.shopName!!.lowercase(), q) <= 3 }
    }

    // Simple iterative Levenshtein
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) { 0 } }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
            }
        }
        return dp[a.length][b.length]
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Direction text generator — converts real A* path into natural language
// ─────────────────────────────────────────────────────────────────────────────

private const val PX_TO_M = 0.05f

object DirectionsGenerator {

    data class DirectionResult(
        val responseText: String,
        val path: AStarPath?
    )

    fun generate(
        graph: MallGraph,
        startNode: GraphNode,
        destNode: GraphNode,
        isArabic: Boolean
    ): DirectionResult {
        val path = MallGraphRepository.aStar(graph, startNode.shopId ?: -1, destNode.shopId ?: -1)
            ?: return DirectionResult(
                responseText = if (isArabic)
                    "😔 معنديش طريق بين ${startNode.shopName} و ${destNode.shopName}"
                else
                    "😔 No path found between ${startNode.shopName} and ${destNode.shopName}.",
                path = null
            )

        val totalM = (path.totalDistancePx * PX_TO_M).roundToInt().coerceAtLeast(1)
        val steps  = path.steps.filter { it.direction != AStarDirection.ARRIVED }

        val sb = StringBuilder()

        if (isArabic) {
            sb.append("تمام 👌 من *${startNode.shopName}* لـ *${destNode.shopName}*:\n\n")
            steps.forEachIndexed { i, step ->
                val distM = (step.distancePx * PX_TO_M).roundToInt().coerceAtLeast(1)
                val instr = when (step.direction) {
                    AStarDirection.STRAIGHT -> "امشي للأمام ~${distM} متر"
                    AStarDirection.LEFT     -> "لف يسار"
                    AStarDirection.RIGHT    -> "لف يمين"
                    AStarDirection.ARRIVED  -> "وصلت!"
                }
                sb.append("${i + 1}. $instr\n")
            }
            sb.append("\n🏁 ${destNode.shopName} هتكون قدامك — المسافة الكلية ~${totalM} متر")
        } else {
            sb.append("Sure! From *${startNode.shopName}* to *${destNode.shopName}*:\n\n")
            steps.forEachIndexed { i, step ->
                val distM = (step.distancePx * PX_TO_M).roundToInt().coerceAtLeast(1)
                val instr = when (step.direction) {
                    AStarDirection.STRAIGHT -> "Go straight ~${distM}m"
                    AStarDirection.LEFT     -> "Turn left"
                    AStarDirection.RIGHT    -> "Turn right"
                    AStarDirection.ARRIVED  -> "Arrived!"
                }
                sb.append("${i + 1}. $instr\n")
            }
            sb.append("\n🏁 ${destNode.shopName} will be ahead — total ~${totalM}m")
        }

        return DirectionResult(responseText = sb.toString().trim(), path = path)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Language detection helper
// ─────────────────────────────────────────────────────────────────────────────

fun isArabicInput(text: String): Boolean =
    text.any { it.code in 0x0600..0x06FF }