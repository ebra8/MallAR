package com.example.mallar.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.mallar.ar.ArCoordinateTransformer
import com.example.mallar.ar.ArrowSceneManager
import com.example.mallar.data.GraphNode
import com.google.ar.core.*
import io.github.sceneview.ar.ARSceneView

/**
 * AR Navigation Screen — fixed version.
 *
 * Changes from broken original:
 *  1. Uses ArrowSceneManager + ArCoordinateTransformer (the real path-aware system).
 *  2. Full session config: depth, light estimation, LATEST_CAMERA_IMAGE.
 *  3. Plane detection uses frame.getAllTrackables() so it finds planes immediately
 *     instead of waiting for getUpdatedPlanes() which only returns NEW planes per frame.
 *  4. planeRenderer enabled during scanning, hidden once origin is set (less visual noise).
 *  5. Tracking-lost recovery: arrows are NOT destroyed on PAUSED, only on STOPPED.
 *  6. Proper cleanup in DisposableEffect.
 *
 * @param pathNodes  Ordered list of GraphNodes from your A* result (start → destination).
 * @param onBackClick  Called when the user wants to leave AR.
 */
@Composable
fun ArNavigationScreen(
    pathNodes: List<GraphNode> = emptyList(),   // ← has default, optional
    onBackClick: () -> Unit
) {
    // ── State ──────────────────────────────────────────────────────────────────
    var statusText   by remember { mutableStateOf("📷 Point at the floor and move slowly…") }
    var segmentIndex by remember { mutableIntStateOf(0) }

    // Hold a reference to the manager so we can clean up on dispose
    val managerRef = remember { mutableStateOf<ArrowSceneManager?>(null) }

    // ── Layout ─────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory = { ctx ->
                ARSceneView(ctx).apply {

                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    // Show plane grid while scanning so user knows detection is working
                    planeRenderer.isEnabled = true
                    planeRenderer.isVisible = true

                    // ── Full session config (fixes tracking-lost) ──────────────
                    configureSession { session, config ->
                        // Detect floor only — faster than HORIZONTAL (up+down)
                        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL

                        // HDR light estimation — arrows look grounded in real space
                        config.lightEstimationMode =
                            Config.LightEstimationMode.ENVIRONMENTAL_HDR

                        // Process newest frame immediately — reduces latency on movement
                        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

                        // Depth API — drastically reduces tracking loss on featureless
                        // mall floors; silently skipped if device doesn't support it
                        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            config.depthMode = Config.DepthMode.AUTOMATIC
                        } else {
                            config.depthMode = Config.DepthMode.DISABLED
                        }

                        // AUTO focus works for both navigation and logo scanning
                        config.focusMode = Config.FocusMode.AUTO
                    }

                    // ── Frame update loop ──────────────────────────────────────
                    // onSessionUpdated is a property (not an inline function) so
                    // return@onSessionUpdated is not allowed. We delegate to a
                    // local fun so early exits are plain `return`.
                    fun handleFrame(session: Session, frame: Frame) {
                        when (frame.camera.trackingState) {

                            TrackingState.TRACKING -> {
                                val manager = managerRef.value

                                if (manager == null) {
                                    // Step 1: find a floor plane.
                                    // getAllTrackables returns ALL known planes, not
                                    // just newly-detected ones → much faster than
                                    // getUpdatedPlanes() on featureless floors.
                                    val floorPlane = session
                                        .getAllTrackables(Plane::class.java)
                                        .firstOrNull {
                                            it.trackingState == TrackingState.TRACKING &&
                                                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                                    it.extentX > 0.5f
                                        }

                                    if (floorPlane == null) {
                                        statusText = "👀 Scanning floor… move phone slowly"
                                        return
                                    }

                                    // Step 2: hit-test screen center on the plane.
                                    val hit = frame
                                        .hitTest(width / 2f, height / 2f)
                                        .firstOrNull { h ->
                                            val t = h.trackable
                                            t is Plane &&
                                                    t.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                                    t.isPoseInPolygon(h.hitPose)
                                        }

                                    if (hit == null) {
                                        statusText = "⚠️ Point camera center at the floor"
                                        return
                                    }

                                    // Step 3: build the arrow manager.
                                    if (pathNodes.size >= 2) {
                                        statusText = "✅ Floor found! Placing arrows…"

                                        val transformer = ArCoordinateTransformer(
                                            startNode = pathNodes.first()
                                        )
                                        val newManager = ArrowSceneManager(
                                            sceneView   = this,
                                            transformer = transformer,
                                            pathNodes   = pathNodes
                                        )
                                        newManager.placeWorldOrigin(hit)
                                        managerRef.value = newManager

                                        // Hide plane grid once arrows are placed
                                        planeRenderer.isVisible = false
                                        statusText = "🎯 Follow the arrows!"
                                    } else {
                                        statusText = "❌ No navigation path loaded"
                                    }

                                } else {
                                    // Step 4: tick the manager every frame.
                                    manager.onFrame(frame, segmentIndex) { reachedIdx ->
                                        segmentIndex = reachedIdx
                                        if (reachedIdx >= pathNodes.size - 1) {
                                            statusText = "🏁 You have arrived!"
                                        }
                                    }
                                }
                            }

                            TrackingState.PAUSED -> {
                                // Do NOT destroy arrows — tracking will resume.
                                statusText = "⚠️ Move slower — tracking lost"
                            }

                            TrackingState.STOPPED -> {
                                statusText = "❌ AR stopped. Please restart."
                                managerRef.value?.destroy()
                                managerRef.value = null
                            }
                        }
                    }

                    onSessionUpdated = { session, frame -> handleFrame(session, frame) }
                }
            },
            update = { /* no updates needed from Compose side */ },
            modifier = Modifier.fillMaxSize()
        )

        // ── Status banner ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .background(
                    color  = Color.Black.copy(alpha = 0.55f),
                    shape  = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text      = statusText,
                color     = Color.White,
                fontSize  = 15.sp
            )
        }
    }

    // ── Cleanup when the composable leaves the screen ──────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            managerRef.value?.destroy()
            managerRef.value = null
        }
    }
}