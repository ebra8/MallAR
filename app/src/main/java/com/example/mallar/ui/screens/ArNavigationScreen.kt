package com.example.mallar.ui.screens

import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.mallar.ar.ArCoordinateTransformer
import com.example.mallar.ar.configureArSessionForNavigation
import com.example.mallar.ar.ArrowSceneManager
import com.example.mallar.data.AStarDirection
import com.example.mallar.data.GraphNode
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ar.core.*
import io.github.sceneview.ar.ARSceneView
import kotlin.math.roundToInt

// ── Design tokens ─────────────────────────────────────────────────────────────
private val NavTeal     = Color(0xFF009688)
private val NavTealDark = Color(0xFF00695C)
private val NavGreen    = Color(0xFF43A047)

/** Scale for DISPLAY distances shown to user (metres).
 *  Must match AR_SCALE in ArCoordinateTransformer (1 px = 5 cm = 0.05 m).
 *  Bug 2 fix: was incorrectly 0.25f, causing all displayed distances to be 5× too large. */
private const val DISPLAY_SCALE = 0.05f
/** Scale used in ArCoordinateTransformer for 3D AR world positions (same as DISPLAY_SCALE). */
private const val PX_TO_M = 0.05f
private const val M_PER_MIN = 80f     // comfortable mall walking speed

// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ArNavigationScreen(
    onBackClick: () -> Unit,
    viewModel: ArNavigationViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // ── Path data ─────────────────────────────────────────────────────────────
    val pathNodes       = state.pathNodes
    val aStarPath       = state.aStarPath
    val destinationNode = state.destinationNode
    val distanceM       = state.distanceM
    val walkMinutes     = state.walkMinutes
    val currentInstructionLabel = state.currentInstructionLabel
    val routeSteps      = state.routeSteps

    // ── UI state ──────────────────────────────────────────────────────────────
    val statusText          = state.statusText
    val segmentIndex        = state.segmentIndex
    val showRouteSheet      = state.showRouteSheet
    val showStoreCard       = state.showStoreCard
    val reachedNotification = state.reachedNotification
    val showAlignmentUi     = state.showAlignmentUi
    val alignmentAngle      = state.alignmentAngle

    val managerRef = remember { mutableStateOf<ArrowSceneManager?>(null) }


    // ── Root layout ───────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {

        // ── AR view (full screen background) ──────────────────────────────────
        AndroidView(
            factory = { ctx ->
                ARSceneView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    planeRenderer.isEnabled = true
                    planeRenderer.isVisible = true

                    configureSession { session, config ->
                        configureArSessionForNavigation(session, config)
                    }

                    fun handleFrame(session: Session, frame: Frame) {
                        when (frame.camera.trackingState) {
                            TrackingState.TRACKING -> {
                                val manager = managerRef.value

                                // ── Phase 1: create manager once on first TRACKING frame ──
                                if (manager == null) {
                                    if (pathNodes.size < 2) {
                                        Handler(Looper.getMainLooper()).post {
                                            viewModel.updateStatusText("❌ No navigation path loaded")
                                        }
                                        return
                                    }
                                    val transformer = ArCoordinateTransformer(
                                        startNode = pathNodes.first()
                                    )
                                    val newManager = ArrowSceneManager(
                                        sceneView   = this,
                                        transformer = transformer,
                                        pathNodes   = pathNodes
                                    )
                                    managerRef.value = newManager
                                    return
                                }

                                // ── Phase 2: collect floor anchor samples ─────────────────
                                if (!manager.isWorldOriginSet) {
                                    val floorPlane = session
                                        .getAllTrackables(Plane::class.java)
                                        .firstOrNull {
                                            it.trackingState == TrackingState.TRACKING &&
                                                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                                    it.extentX > 0.3f && it.extentZ > 0.3f
                                        }
                                    if (floorPlane == null) {
                                        Handler(Looper.getMainLooper()).post {
                                            viewModel.updateStatusText("👀 Scanning floor… move phone slowly")
                                        }
                                        return
                                    }

                                    val hit = frame
                                        .hitTest(width / 2f, height / 2f)
                                        .firstOrNull { h ->
                                            val t = h.trackable
                                            t is Plane &&
                                                    t.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                                    t.isPoseInPolygon(h.hitPose)
                                        }
                                    if (hit == null) {
                                        Handler(Looper.getMainLooper()).post {
                                            viewModel.updateStatusText("⚠️ Point camera centre at the floor")
                                        }
                                        return
                                    }

                                    if (!manager.isCollectingSamples) {
                                        manager.startSampleCollection()
                                        Handler(Looper.getMainLooper()).post {
                                            viewModel.updateStatusText("📍 Hold steady — calibrating floor…")
                                        }
                                    }

                                    val ready = manager.feedHitSample(hit)
                                    val pct   = (manager.sampleProgress * 100).roundToInt()
                                    Handler(Looper.getMainLooper()).post {
                                        viewModel.updateStatusText("📍 Calibrating… $pct%")
                                    }

                                    if (ready) {
                                        manager.commitAnchor(session, frame)
                                        manager.showHeadingPreview()
                                        planeRenderer.isVisible = false
                                        Handler(Looper.getMainLooper()).post {
                                            viewModel.updateStatusText("🔄 Align the arrow with the hallway")
                                            viewModel.requestHeadingAlignment()
                                        }
                                    }
                                    return
                                }

                                // ── Phase 3: track user + update arrows ───────────────────
                                manager.onFrame(frame, segmentIndex) { reachedIdx ->
                                    Handler(Looper.getMainLooper()).post {
                                        viewModel.onNodeReached(reachedIdx)
                                    }
                                }
                            }

                            TrackingState.PAUSED -> Handler(Looper.getMainLooper()).post {
                                viewModel.updateStatusText("⚠️ Move slower — tracking lost")
                            }

                            TrackingState.STOPPED -> {
                                Handler(Looper.getMainLooper()).post {
                                    viewModel.updateStatusText("❌ AR stopped. Please restart.")
                                }
                                managerRef.value?.destroy()
                                managerRef.value = null
                            }
                        }
                    }

                    onSessionUpdated = { session, frame -> handleFrame(session, frame) }
                }
            },
            update = { /* AR session drives itself */ },
            onRelease = { view ->
                view.destroy()
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── TOP: back button + target button + store card ─────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ← Back button (white circle)
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { onBackClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // ⊙ Target button (teal circle)
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(NavTeal),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Location",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Store info card ──────────────────────────────────────────────
            AnimatedVisibility(
                visible = showStoreCard && destinationNode != null,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit  = fadeOut(tween(300)) + shrinkVertically(tween(300))
            ) {
                if (destinationNode != null) {
                    StoreInfoCard(
                        node        = destinationNode,
                        distanceM   = distanceM,
                        walkMinutes = walkMinutes,
                        statusText  = statusText,
                        onClose     = { viewModel.closeStoreCard() }
                    )
                }
            }
        }

        // ── CENTER: Waypoint reached notification ─────────────────────────────
        AnimatedVisibility(
            visible  = reachedNotification != null,
            modifier = Modifier.align(Alignment.Center),
            enter    = fadeIn() + scaleIn(),
            exit     = fadeOut() + scaleOut()
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = NavGreen.copy(alpha = 0.93f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                Text(
                    text       = reachedNotification ?: "",
                    color      = Color.White,
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── BOTTOM: action button + "Show Road →" ────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showAlignmentUi) {
                AlignmentSliderUi(
                    alignmentAngle = alignmentAngle,
                    onAngleChange = { newAngle ->
                        viewModel.updateAlignmentAngle(newAngle)
                        managerRef.value?.updatePreviewHeading(newAngle)
                    },
                    onConfirm = {
                        viewModel.confirmAlignment()
                        viewModel.updateStatusText("🎯 Follow the arrows!")
                        managerRef.value?.applyHeadingAndPlaceArrows(alignmentAngle)
                    }
                )
            } else {
                // Teal direction / action button
                if (pathNodes.size >= 2) {
                Button(
                    onClick  = { /* informational */ },
                    shape    = RoundedCornerShape(24.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = NavTeal),
                    elevation = ButtonDefaults.buttonElevation(4.dp),
                    modifier = Modifier
                        .widthIn(min = 200.dp)
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(currentInstructionLabel, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(14.dp))
            }
            } // close else block

            // "Show Road →" tap row
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { viewModel.toggleRouteSheet() }
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Show Road", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (showRouteSheet) Icons.Default.KeyboardArrowDown
                    else Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // ── BOTTOM SHEET: route detail ────────────────────────────────────────
        AnimatedVisibility(
            visible  = showRouteSheet,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter    = slideInVertically(tween(350)) { it },
            exit     = slideOutVertically(tween(300)) { it }
        ) {
            RouteSheet(
                destination  = destinationNode,
                steps        = routeSteps,
                segmentIndex = segmentIndex,
                distanceM    = distanceM,
                walkMinutes  = walkMinutes,
                onEndNavigation = onBackClick
            )
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            // managerRef.value?.destroy() is removed. ARSceneView's onRelease handles destruction.
            managerRef.value = null
        }
    }
}

@Composable
private fun AlignmentSliderUi(
    alignmentAngle: Float,
    onAngleChange: (Float) -> Unit,
    onConfirm: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Rotate to align arrow with hallway",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = Color.Black
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Slider(
                value = alignmentAngle,
                onValueChange = onAngleChange,
                valueRange = -180f..180f,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavTeal),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Confirm Direction", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Store info card  (top white card matching the mockup)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StoreInfoCard(
    node: GraphNode,
    distanceM: Int,
    walkMinutes: Int,
    statusText: String,
    onClose: () -> Unit
) {
    Card(
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // logos — must use the android_asset URI scheme for Coil
            if (node.logo != null) {
                AsyncImage(
                    model              = "file:///android_asset/${node.logo}",
                    contentDescription = node.shopName,
                    modifier           = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale       = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NavTeal),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Store, null, tint = Color.White)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text       = node.shopName ?: "Destination",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 17.sp,
                    color      = Color.Black
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn,  null, tint = Color(0xFFE53935), modifier = Modifier.size(13.dp))
                    Text(" ${distanceM}m",     fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Default.AccessTime,  null, tint = NavTeal,           modifier = Modifier.size(13.dp))
                    Text(" ${walkMinutes}min", fontSize = 13.sp, color = Color.Gray)
                }
                Text(statusText, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
            }

            // ✕ close
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Route sheet  (slide-up panel matching the right mockup screen)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RouteSheet(
    destination: GraphNode?,
    steps: List<StepItem>,
    segmentIndex: Int,
    distanceM: Int,
    walkMinutes: Int,
    onEndNavigation: () -> Unit
) {

    Card(
        shape  = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            // Drag handle
            Box(
                Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .background(Color(0xFFDDDDDD), CircleShape)
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))

            // Destination name
            Text(
                text       = destination?.shopName ?: "Destination",
                fontSize   = 22.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.Black
            )
            Spacer(Modifier.height(4.dp))

            // Floor + walk time
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("1st Floor", fontSize = 14.sp, color = Color.Gray)
                Text("  |  ${walkMinutes} min walk", fontSize = 14.sp, color = Color.Gray)
                Spacer(Modifier.weight(1f))
                Text("${distanceM}m", fontSize = 13.sp, color = NavTeal, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFFEEEEEE))
            Spacer(Modifier.height(16.dp))

            // Step list
            steps.forEachIndexed { i, step ->
                RouteStepRow(
                    step      = step,
                    isFirst   = i == 0,
                    isLast    = i == steps.size - 1,
                    isPassed  = step.nodeIndex != null && step.nodeIndex < segmentIndex,
                    isCurrent = step.nodeIndex != null && step.nodeIndex == segmentIndex,
                    showLine  = i < steps.size - 1
                )
            }

            Spacer(Modifier.height(24.dp))

            // End Navigation button
            Button(
                onClick  = onEndNavigation,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape  = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavTeal)
            ) {
                Text("End Navigation", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step row
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RouteStepRow(
    step: StepItem,
    isFirst: Boolean,
    isLast: Boolean,
    isPassed: Boolean,
    isCurrent: Boolean,
    showLine: Boolean
) {
    Row(modifier = Modifier.fillMaxWidth()) {

        // Dot + vertical line column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(24.dp)
        ) {
            if (step.isWaypoint) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isFirst   -> NavGreen
                                isPassed  -> NavTeal
                                isCurrent -> NavTeal
                                isLast    -> Color(0xFFBDBDBD)
                                else      -> Color(0xFFBDBDBD)
                            }
                        )
                )
            } else {
                // Instruction — small dot
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFCCCCCC))
                )
            }
            if (showLine) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(28.dp)
                        .background(Color(0xFFDDDDDD))
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.padding(bottom = 4.dp)) {
            if (step.isWaypoint && isFirst) {
                Text("Your Location", fontSize = 10.sp, color = NavGreen, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text       = step.label,
                fontSize   = if (step.isWaypoint) 15.sp else 13.sp,
                fontWeight = if (step.isWaypoint) FontWeight.SemiBold else FontWeight.Normal,
                color      = when {
                    isPassed  -> Color(0xFFAAAAAA)
                    isCurrent -> NavTeal
                    isLast    -> Color(0xFF555555)
                    else      -> Color(0xFF333333)
                }
            )
        }
    }
}