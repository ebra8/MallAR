package com.example.mallar.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * A single brand detection result.
 *
 * [imageX] / [imageY]: normalised [0, 1] position of the logo centroid within
 * the camera frame (0 = left/top, 1 = right/bottom, 0.5 = centre).
 * These are populated by [LogoDetector.detectTopNWithLocation]; when using
 * [detect] or [detectTopN] they default to 0.5 (centre-frame assumption).
 */
data class DetectionResult(
    val brand: String,
    val similarity: Float,
    /** Normalised horizontal position in the camera frame [0 = left, 1 = right]. */
    val imageX: Float = 0.5f,
    /** Normalised vertical position in the camera frame [0 = top, 1 = bottom]. */
    val imageY: Float = 0.5f
)

class LogoDetector(private val context: Context) {

    private val IMG_SIZE = 224
    private lateinit var tflite: Interpreter

    // Each brand keeps ALL its embeddings (not averaged) — same as working app
    private val shopDatabase = mutableMapOf<String, List<FloatArray>>()

    private var modelLoaded = false
    private var databaseLoaded = false

    // ✅ Same threshold as working app
    private val CONFIDENCE_THRESHOLD = 0.35f
    // ✅ Same top-K as working app
    private val TOP_K_EMBEDDINGS = 3

    init {
        loadModel()
        loadDatabase()
    }

    // ── model ─────────────────────────────────────────────────────────────────

    private fun loadModelFile(): MappedByteBuffer {
        val fd = context.assets.openFd("mall_embedding_256.tflite")
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
    }

    private fun loadModel() {
        try {
            tflite = Interpreter(loadModelFile(), Interpreter.Options())
            modelLoaded = true
            val outShape = tflite.getOutputTensor(0).shape()
            Log.d(TAG, "✅ Model loaded — output size: ${outShape[1]}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Model load failed: ${e.message}")
            modelLoaded = false
        }
    }

    // ── database ──────────────────────────────────────────────────────────────

    fun loadDatabase() {
        try {
            val json = context.assets.open("embeddings_database.json")
                .bufferedReader().use { it.readText() }

            val type = object : TypeToken<Map<String, List<List<Float>>>>() {}.type
            val raw: Map<String, List<List<Float>>> = Gson().fromJson(json, type)

            shopDatabase.clear()
            for ((brand, embedList) in raw) {
                // ✅ Keep ALL embeddings per brand — don't average them
                shopDatabase[brand] = embedList.map { it.toFloatArray() }
            }

            databaseLoaded = true
            val first = shopDatabase.entries.firstOrNull()
            Log.d(TAG, "✅ DB loaded: ${shopDatabase.size} brands")
            Log.d(TAG, "✅ Embedding size: ${first?.value?.firstOrNull()?.size}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ DB load failed: ${e.message}")
            databaseLoaded = false
        }
    }

    // ── detection ─────────────────────────────────────────────────────────────

    /** Returns the single best detection above [CONFIDENCE_THRESHOLD], or null. */
    fun detect(bitmap: Bitmap): DetectionResult? {
        if (!modelLoaded)    { Log.w(TAG, "Model not ready");    return null }
        if (!databaseLoaded) { Log.w(TAG, "Database not ready"); return null }

        return try {
            val inputBuffer  = preprocessImage(bitmap)
            val queryVector  = extractEmbedding(inputBuffer)

            val scores = compareWithDatabase(queryVector)
            val best   = scores.firstOrNull() ?: return null

            Log.d(TAG, "🏆 Best: ${best.brand}  sim=${"%.4f".format(best.similarity)}  threshold=$CONFIDENCE_THRESHOLD")

            if (best.similarity > CONFIDENCE_THRESHOLD)
                DetectionResult(best.brand, best.similarity)
            else
                null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Detection error: ${e.message}")
            null
        }
    }

    /**
     * Returns the top [maxResults] detections with similarity ≥ [minSimilarity],
     * sorted descending by score. Used by [com.example.mallar.ml.LocalizationEngine]
     * for multi-landmark pose estimation.
     *
     * @param bitmap      Camera frame bitmap (any orientation — preprocessed internally).
     * @param maxResults  Maximum number of results to return (default 5).
     * @param minSimilarity Minimum similarity cutoff (default = CONFIDENCE_THRESHOLD).
     */
    fun detectTopN(
        bitmap: Bitmap,
        maxResults: Int = 5,
        minSimilarity: Float = CONFIDENCE_THRESHOLD
    ): List<DetectionResult> {
        if (!modelLoaded)    { Log.w(TAG, "Model not ready");    return emptyList() }
        if (!databaseLoaded) { Log.w(TAG, "Database not ready"); return emptyList() }

        return try {
            val inputBuffer = preprocessImage(bitmap)
            val queryVector = extractEmbedding(inputBuffer)
            val scores      = compareWithDatabase(queryVector)

            scores
                .filter  { it.similarity >= minSimilarity }
                .take    (maxResults)
                .also    { results ->
                    Log.d(TAG, "🔎 detectTopN: ${results.size} results above $minSimilarity")
                    results.forEachIndexed { i, r ->
                        Log.d(TAG, "  [$i] ${r.brand} → ${"%.4f".format(r.similarity)}")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "❌ detectTopN error: ${e.message}")
            emptyList()
        }
    }

    // ── image preprocessing — MUST match training normalization ───────────────

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        // Crop the center of the image to be square
        val minDim = Math.min(bitmap.width, bitmap.height)
        val startX = (bitmap.width - minDim) / 2
        val startY = (bitmap.height - minDim) / 2
        val cropped = Bitmap.createBitmap(bitmap, startX, startY, minDim, minDim)
        
        val resized = Bitmap.createScaledBitmap(cropped, IMG_SIZE, IMG_SIZE, true)

        // ✅ KEY FIX: normalize to [-1, 1] — same as working app
        //    Wrong (our old code): pixel / 255f          → [0, 1]
        //    Correct:              (pixel / 127.5f) - 1  → [-1, 1]
        val buf = ByteBuffer.allocateDirect(1 * IMG_SIZE * IMG_SIZE * 3 * 4)
        buf.order(ByteOrder.nativeOrder())

        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        resized.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)

        for (pixel in pixels) {
            buf.putFloat(((pixel shr 16 and 0xFF) / 127.5f) - 1.0f)  // R
            buf.putFloat(((pixel shr 8  and 0xFF) / 127.5f) - 1.0f)  // G
            buf.putFloat(((pixel        and 0xFF) / 127.5f) - 1.0f)  // B
        }
        buf.rewind()
        return buf
    }

    // ── embedding extraction ──────────────────────────────────────────────────

    private fun extractEmbedding(inputBuffer: ByteBuffer): FloatArray {
        val vectorSize = tflite.getOutputTensor(0).shape()[1]
        val output = Array(1) { FloatArray(vectorSize) }
        tflite.run(inputBuffer, output)
        Log.d(TAG, "📊 Embedding size: ${output[0].size}")
        return output[0]
    }

    // ── comparison ────────────────────────────────────────────────────────────

    private fun compareWithDatabase(query: FloatArray): List<DetectionResult> {
        return shopDatabase.map { (brand, embeddings) ->
            // ✅ Compare against every stored embedding, take avg of top-3
            val similarities = embeddings.map { cosineSimilarity(query, it) }
            val avgSim = similarities.sortedDescending().take(TOP_K_EMBEDDINGS).average().toFloat()
            DetectionResult(brand, avgSim)
        }.sortedByDescending { it.similarity }
    }

    // ── cosine similarity ─────────────────────────────────────────────────────

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            Log.e(TAG, "❌ Size mismatch: ${a.size} vs ${b.size}")
            return 0f
        }
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i]*b[i]; normA += a[i]*a[i]; normB += b[i]*b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    fun isReady() = modelLoaded && databaseLoaded && shopDatabase.isNotEmpty()

    // ── Sliding-window location-aware detection ───────────────────────────────
    //
    // Divides the frame into a GRID × GRID grid and runs the embedding model on
    // each cell.  For every brand above [minSimilarity] we record which grid cell
    // gave the highest similarity — its centre becomes (imageX, imageY).
    //
    // This gives us genuine image-space coordinates for PnP input, replacing the
    // naive "assume logo is at image centre" assumption.
    //
    // Cost: GRID² inference calls per scan (9 by default).
    // Typically <300 ms on a mid-range phone — acceptable for a one-shot scan.

    fun detectTopNWithLocation(
        bitmap: Bitmap,
        maxResults: Int = 5,
        minSimilarity: Float = CONFIDENCE_THRESHOLD,
        gridSize: Int = 3          // 3×3 = 9 cells
    ): List<DetectionResult> {
        if (!modelLoaded || !databaseLoaded) return emptyList()

        data class CellResult(
            val cellRow: Int, val cellCol: Int,
            val scores: List<DetectionResult>   // full ranked list for this cell
        )

        val cellW = bitmap.width  / gridSize.toFloat()
        val cellH = bitmap.height / gridSize.toFloat()

        // Run inference on every grid cell
        val cellResults = mutableListOf<CellResult>()
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val x = (col * cellW).toInt().coerceAtLeast(0)
                val y = (row * cellH).toInt().coerceAtLeast(0)
                val w = cellW.toInt().coerceAtMost(bitmap.width  - x)
                val h = cellH.toInt().coerceAtMost(bitmap.height - y)
                if (w <= 0 || h <= 0) continue

                val crop = Bitmap.createBitmap(bitmap, x, y, w, h)
                val buf  = preprocessImage(crop)
                val vec  = extractEmbedding(buf)
                val scores = compareWithDatabase(vec)
                cellResults += CellResult(row, col, scores)
            }
        }

        // For each brand: find which cell gave the highest similarity
        val brandBest = mutableMapOf<String, Pair<Float, CellResult>>()
        for (cell in cellResults) {
            for (det in cell.scores) {
                val prev = brandBest[det.brand]
                if (prev == null || det.similarity > prev.first) {
                    brandBest[det.brand] = Pair(det.similarity, cell)
                }
            }
        }

        // Build final results: use the best-cell similarity + its centre as imageX/Y
        return brandBest.entries
            .filter  { (_, v) -> v.first >= minSimilarity }
            .sortedByDescending { (_, v) -> v.first }
            .take(maxResults)
            .map { (brand, v) ->
                val (sim, cell) = v
                // Centre of the winning cell in normalised [0,1] coordinates
                val imgX = ((cell.cellCol + 0.5f) / gridSize).coerceIn(0f, 1f)
                val imgY = ((cell.cellRow + 0.5f) / gridSize).coerceIn(0f, 1f)
                Log.d(TAG, "📍 $brand → sim=${"%.3f".format(sim)} cell=(${cell.cellRow},${cell.cellCol}) imgXY=(${"%.2f".format(imgX)}, ${"%.2f".format(imgY)})")
                DetectionResult(brand, sim, imgX, imgY)
            }
    }

    companion object { private const val TAG = "LogoDetector" }
}