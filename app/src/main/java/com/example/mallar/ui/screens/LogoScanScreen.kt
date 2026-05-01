package com.example.mallar.ui.screens



import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.compose.material.icons.filled.SmartToy
import com.example.mallar.chat.ChatBottomSheet
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.mallar.data.AStarPath
import com.example.mallar.data.MallGraphRepository
import com.example.mallar.data.Place
import com.example.mallar.data.PlaceRepository
import com.example.mallar.ml.LogoDetector
import com.example.mallar.ui.theme.*
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// ── Global navigation state (start + end + A* path) ──────────────────────────
object NavigationState {
    var startPlace: Place?       = null   // where the user IS (scanned start)
    var selectedPlace: Place?    = null   // destination (end point)
    var estimatedDistance: Int   = 0
    var estimatedMinutes: Int    = 0
    var aStarPath: AStarPath?    = null   // full A* result
}

// ── Screen flow states ────────────────────────────────────────────────────────
enum class ScanState { IDLE, SCANNING, FOUND, NOT_FOUND }

enum class ScreenFlow {
    CAMERA_IDLE,           // camera visible, bottom bar shown
    SCAN_CONFIRM,          // scan detected brand — confirm start location
    PICK_DESTINATION,      // user confirmed start → now search for destination
    DESTINATION_DETAIL,    // destination chosen → show detail card + Start button
    COMPUTING              // A* running
}

// ── Main Screen ───────────────────────────────────────────────────────────────
@Composable
fun LogoScanScreen(
    onSettingsClick: () -> Unit,
    onStoreSelected: (Boolean) -> Unit   // navigate to ArNavigation (true) or StaticMap (false)
) {
    val context        = LocalContext.current
    var backPressedTime by remember { mutableLongStateOf(0L) }

    BackHandler {
        val currentTime = System.currentTimeMillis()
        if (currentTime - backPressedTime < 2000) {
            (context as? android.app.Activity)?.finish()
        } else {
            backPressedTime = currentTime
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    var allPlaces by remember { mutableStateOf<List<Place>>(emptyList()) }
    var mallGraph by remember { mutableStateOf<com.example.mallar.data.MallGraph?>(null) }
    var logoDetector by remember { mutableStateOf<LogoDetector?>(null) }

    val scanRequested  = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val loadedPlaces = PlaceRepository.load(context)
            val loadedGraph = MallGraphRepository.load(context)
            val loadedDetector = LogoDetector(context)

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                allPlaces = loadedPlaces
                mallGraph = loadedGraph
                logoDetector = loadedDetector
            }
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    var flow           by remember { mutableStateOf(ScreenFlow.CAMERA_IDLE) }
    var scanState      by remember { mutableStateOf(ScanState.IDLE) }

    var detectedBrand  by remember { mutableStateOf<String?>(null) }
    var detectedScore  by remember { mutableStateOf(0f) }
    var startPlace     by remember { mutableStateOf<Place?>(null) }
    var destination    by remember { mutableStateOf<Place?>(null) }

    var searchQuery    by remember { mutableStateOf("") }
    var showChatBot    by remember { mutableStateOf(false) }
    var chatPath       by remember { mutableStateOf<AStarPath?>(null) }

    val latestBitmap   = remember { AtomicReference<Bitmap?>(null) }

    // The brand detected by the ML model matched with a Place from places.json
    val detectedPlace = remember(detectedBrand) {
        val b = detectedBrand ?: return@remember null
        // Try exact match first, then partial
        allPlaces.firstOrNull { it.brand.equals(b, ignoreCase = true) }
            ?: allPlaces.firstOrNull { it.brand.contains(b, ignoreCase = true) }
            ?: allPlaces.firstOrNull { b.replace(" ", "").contains(it.brand.replace(" ", ""), ignoreCase = true) }
    }

    // Filtered places for destination search
    val filteredPlaces = remember(searchQuery, allPlaces, startPlace) {
        val base = if (searchQuery.isBlank()) allPlaces else
            allPlaces.filter { it.brand.contains(searchQuery, ignoreCase = true) }
        // Exclude the start place from destination options
        base.filter { it.id != startPlace?.id }
    }

    // Compute destination distance using A* when possible, fallback to Euclidean
    val destDistM = remember(destination, startPlace, mallGraph) {
        val d = destination ?: return@remember 0
        val s = startPlace
        val g = mallGraph
        if (s != null && g != null) {
            val path = MallGraphRepository.aStar(g, s.id, d.id)
            if (path != null) return@remember (path.totalDistancePx * 0.05).toInt().coerceAtLeast(1)
        }
        // Fallback: Euclidean between place coords
        val dx = (d.x - (s?.x ?: d.x)).toFloat()
        val dy = (d.y - (s?.y ?: d.y)).toFloat()
        (kotlin.math.sqrt(dx * dx + dy * dy) * 0.05f).toInt().coerceAtLeast(1)
    }
    val destMins = remember(destDistM) { (destDistM / 80f).coerceIn(1f, 20f).toInt() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── Camera Preview (always running) ───────────────────────────────────
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val future: ListenableFuture<ProcessCameraProvider> =
                    ProcessCameraProvider.getInstance(ctx)
                val executor = Executors.newSingleThreadExecutor()
                future.addListener({
                    try {
                        val cp = future.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val ia = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        ia.setAnalyzer(executor) { proxy ->
                            val bmp = proxy.toBitmapSafe()
                            if (bmp != null) {
                                latestBitmap.set(bmp)
                                if (scanRequested.getAndSet(false)) {
                                    // ✅ Use the real ML model
                                    val result = logoDetector?.detect(bmp)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        if (result != null) {
                                            Log.d("LogoScan", "✅ Detected: ${result.brand} (${result.similarity})")
                                            detectedBrand = result.brand
                                            detectedScore = result.similarity
                                            scanState = ScanState.FOUND
                                        } else {
                                            Log.d("LogoScan", "❌ No logo detected above threshold")
                                            scanState = ScanState.NOT_FOUND
                                        }
                                    }
                                }
                            }
                            proxy.close()
                        }
                        cp.unbindAll()
                        cp.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, ia)
                    } catch (e: Exception) {
                        Log.e("LogoScan", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Sync scanState → flow
        LaunchedEffect(scanState) {
            if (scanState == ScanState.FOUND) flow = ScreenFlow.SCAN_CONFIRM
        }

        // ── NOT_FOUND toast ───────────────────────────────────────────────────
        AnimatedVisibility(
            visible = scanState == ScanState.NOT_FOUND,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(), exit = fadeOut()
        ) {
            LaunchedEffect(scanState) {
                if (scanState == ScanState.NOT_FOUND) {
                    kotlinx.coroutines.delay(2500)
                    scanState = ScanState.IDLE
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFB71C1C).copy(alpha = 0.9f)
            ) {
                Text(
                    "❌  No logo recognized — point camera at a store sign and try again",
                    color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
                )
            }
        }

        // ── SCANNING spinner ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = scanState == ScanState.SCANNING,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(), exit = fadeOut()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Teal, modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
                Spacer(modifier = Modifier.height(14.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.6f)) {
                    Text(
                        "Scanning logo…",
                        color = White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // FLOW: SCAN_CONFIRM — "Is this your current location?"
        // ══════════════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = flow == ScreenFlow.SCAN_CONFIRM,
            enter = slideInVertically(tween(300)) { it / 2 } + fadeIn(tween(250)),
            exit  = slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(150))
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.fillMaxWidth().shadow(28.dp, RoundedCornerShape(28.dp)),
                    shape = RoundedCornerShape(28.dp), color = White
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.MyLocation,
                            contentDescription = null,
                            tint = Teal,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "You are currently at:",
                            color = TextSecondary, fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        // Show logo if we found a matching place
                        if (detectedPlace != null) {
                            Surface(
                                modifier = Modifier.size(80.dp),
                                shape = RoundedCornerShape(18.dp),
                                color = SurfaceLight
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data("file:///android_asset/${detectedPlace.logo}")
                                        .crossfade(true).build(),
                                    contentDescription = detectedPlace.brand,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).padding(8.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        Text(
                            text = detectedBrand ?: "Unknown store",
                            fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, color = TextPrimary,
                            textAlign = TextAlign.Center
                        )

                        if (detectedPlace == null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "(not in mall map — please scan again)",
                                color = RedAccent, fontSize = 12.sp, textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Match confidence: ${"%.0f".format(detectedScore * 100)}%",
                            color = TextSecondary, fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // YES — confirm this as START location
                        Button(
                            onClick = {
                                val start = detectedPlace
                                if (start != null) {
                                    startPlace = start
                                    NavigationState.startPlace = start
                                    flow = ScreenFlow.PICK_DESTINATION
                                    scanState = ScanState.IDLE
                                    detectedBrand = null
                                } else {
                                    // Brand found but not in graph — scan again
                                    flow = ScreenFlow.CAMERA_IDLE
                                    scanState = ScanState.IDLE
                                    detectedBrand = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (detectedPlace != null) Teal else Color.Gray
                            )
                        ) {
                            Text(
                                if (detectedPlace != null) "Yes, I'm here"
                                else "Not found — Scan Again",
                                fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = White
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // SCAN AGAIN
                        OutlinedButton(
                            onClick = {
                                flow = ScreenFlow.CAMERA_IDLE
                                scanState = ScanState.IDLE
                                detectedBrand = null
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            border = androidx.compose.foundation.BorderStroke(2.dp, Teal),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Teal)
                        ) {
                            Icon(Icons.Filled.Search, null, tint = Teal, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan Again", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Teal)
                        }
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // FLOW: PICK_DESTINATION — search for where user wants to go
        // ══════════════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = flow == ScreenFlow.PICK_DESTINATION,
            enter = fadeIn(tween(200)),
            exit  = fadeOut(tween(150))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = {
                                flow = ScreenFlow.CAMERA_IDLE
                                startPlace = null
                                NavigationState.startPlace = null
                                searchQuery = ""
                            },
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = White.copy(alpha = 0.9f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "📍 From: ${startPlace?.brand ?: "?"}",
                                color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Where do you want to go?",
                                color = White.copy(alpha = 0.7f), fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    // Search field
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(46.dp).shadow(6.dp, RoundedCornerShape(23.dp)),
                        shape = RoundedCornerShape(23.dp), color = White
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Search, null, tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = TextPrimary),
                                modifier = Modifier.weight(1f), singleLine = true,
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) Text("Search destination…", color = TextSecondary.copy(alpha = 0.5f), fontSize = 15.sp)
                                    inner()
                                }
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Store list
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
                    color = White, shadowElevation = 8.dp
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 8.dp, start = 14.dp, end = 14.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        itemsIndexed(filteredPlaces) { _, place ->
                            DestinationRow(place = place, onClick = {
                                destination = place
                                flow = ScreenFlow.DESTINATION_DETAIL
                            })
                            HorizontalDivider(
                                color = DividerColor.copy(alpha = 0.4f),
                                thickness = 0.7.dp,
                                modifier = Modifier.padding(start = 82.dp)
                            )
                        }
                        if (filteredPlaces.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                                    Text("No stores found", color = TextSecondary, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // FLOW: DESTINATION_DETAIL — store card + Start button (matches mockup 2)
        // ══════════════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = flow == ScreenFlow.DESTINATION_DETAIL && destination != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(280)) { it / 10 },
            exit  = fadeOut(tween(150))
        ) {
            val dest = destination
            if (dest != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top bar — back + store name + Search button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { flow = ScreenFlow.PICK_DESTINATION },
                            modifier = Modifier.size(42.dp), shape = CircleShape,
                            color = White.copy(alpha = 0.9f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Surface(
                            modifier = Modifier.weight(1f).height(44.dp).shadow(6.dp, RoundedCornerShape(22.dp)),
                            shape = RoundedCornerShape(22.dp), color = White
                        ) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                                Text(dest.brand, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Surface(shape = RoundedCornerShape(22.dp), color = Teal, modifier = Modifier.height(44.dp)) {
                            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                Text("Search", color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }

                    // White store card
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 110.dp, start = 16.dp, end = 16.dp)
                            .shadow(24.dp, RoundedCornerShape(28.dp)),
                        shape = RoundedCornerShape(28.dp), color = White
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier.size(70.dp),
                                    shape = RoundedCornerShape(16.dp), color = SurfaceLight
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data("file:///android_asset/${dest.logo}").crossfade(true).build(),
                                        contentDescription = dest.brand,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).padding(8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dest.brand, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = TextPrimary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.LocationOn, null, tint = RedAccent, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("${destDistM}m", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Icon(Icons.Filled.AccessTime, null, tint = RedAccent, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("${destMins}min", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Surface(
                                    onClick = { flow = ScreenFlow.PICK_DESTINATION; destination = null },
                                    modifier = Modifier.size(32.dp), shape = CircleShape, color = SurfaceLight
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Close, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }

                            // Show route info if we have A* start
                            if (startPlace != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Teal.copy(alpha = 0.08f)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.MyLocation, null, tint = Teal, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "From: ${startPlace!!.brand}",
                                            color = Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // Action Buttons: AR and Static Map
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        startNavigation(mallGraph, startPlace, destination, destDistM, onStoreSelected, true)
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    shape = RoundedCornerShape(27.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Teal)
                                ) {
                                    Icon(Icons.Filled.ViewInAr, null, tint = White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("AR", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = White)
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Button(
                                    onClick = {
                                        startNavigation(mallGraph, startPlace, destination, destDistM, onStoreSelected, false)
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    shape = RoundedCornerShape(27.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                                ) {
                                    Icon(Icons.Filled.Map, null, tint = White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Map", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = White)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "Choose your navigation mode",
                                color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // FLOW: CAMERA_IDLE — bottom bar with search + scan
        // ══════════════════════════════════════════════════════════════════════
        val showBottomBar = flow == ScreenFlow.CAMERA_IDLE && scanState == ScanState.IDLE

        AnimatedVisibility(
            visible = showBottomBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(250)) { it } + fadeIn(),
            exit  = slideOutVertically(tween(200)) { it } + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            ) {
                // Tip label
                Text(
                    "📸 Scan a store logo to set your start location, or search your destination",
                    color = White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Search field — taps into PICK_DESTINATION with no start
                    Surface(
                        modifier = Modifier.weight(1f).height(56.dp)
                            .shadow(10.dp, RoundedCornerShape(28.dp))
                            .clickable {
                                // Allow searching destination even without scanning first
                                startPlace = NavigationState.startPlace
                                flow = ScreenFlow.PICK_DESTINATION
                                searchQuery = ""
                            },
                        shape = RoundedCornerShape(28.dp), color = White
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Search, null, tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Search for stores…", color = TextSecondary.copy(alpha = 0.5f), fontSize = 14.sp)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    // SCAN button
                    Button(
                        onClick = {
                            if (scanState != ScanState.SCANNING) {
                                scanState = ScanState.SCANNING
                                detectedBrand = null
                                scanRequested.set(true)
                            }
                        },
                        modifier = Modifier.height(56.dp).width(90.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                        enabled = scanState != ScanState.SCANNING
                    ) {
                        Text(
                            if (scanState == ScanState.SCANNING) "…" else "Scan",
                            fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = White
                        )
                    }
                }
            }
        }


        // ── CHATBOT: Floating "Ask me" button — visible on CAMERA_IDLE ──────────
        AnimatedVisibility(
            visible  = flow == ScreenFlow.CAMERA_IDLE && scanState == ScanState.IDLE,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 90.dp),  // sits above the search bar
            enter = scaleIn(tween(250)) + fadeIn(tween(250)),
            exit  = scaleOut(tween(200)) + fadeOut(tween(200))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // "Ask me" label chip
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF009688).copy(alpha = 0.92f),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "Ask me!",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                // FAB
                FloatingActionButton(
                    onClick        = { showChatBot = true },
                    containerColor = Color(0xFF009688),
                    contentColor   = Color.White,
                    shape          = CircleShape,
                    elevation      = FloatingActionButtonDefaults.elevation(6.dp),
                    modifier       = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = "Open Mall Assistant",
                        modifier    = Modifier.size(28.dp)
                    )
                }
            }
        }

        // ── CHATBOT: Bottom sheet dialog ──────────────────────────────────────
        if (showChatBot) {
            Dialog(
                onDismissRequest = { showChatBot = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside   = true
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Tap outside area to dismiss
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { showChatBot = false }
                    )
                    // Sheet at bottom
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        ChatBottomSheet(
                            graph       = mallGraph,
                            onDismiss   = { showChatBot = false },
                            onPathFound = { path: AStarPath ->
                                chatPath = path
                                // Auto-set navigation state so user can jump straight to AR
                                NavigationState.aStarPath = path
                            },
                            onStartNavigation = { useAr ->
                                showChatBot = false
                                onStoreSelected(useAr)
                            }
                        )
                    }
                }
            }
        }

        // Settings button — only in CAMERA_IDLE
        AnimatedVisibility(
            visible = flow == ScreenFlow.CAMERA_IDLE && scanState == ScanState.IDLE,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = fadeIn(), exit = fadeOut()
        ) {
            Box(modifier = Modifier.statusBarsPadding().padding(16.dp)) {
                Surface(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape, color = White.copy(alpha = 0.88f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Settings, "Settings", tint = Teal)
                    }
                }
            }
        }
    }
}

private fun startNavigation(
    mallGraph: com.example.mallar.data.MallGraph?,
    startPlace: Place?,
    destination: Place?,
    destDistM: Int,
    onStoreSelected: (Boolean) -> Unit,
    useAr: Boolean
) {
    val start = startPlace
    val end   = destination
    if (start != null && end != null) {
        // ── Run A* to get the optimal path ──────────────
        val path = mallGraph?.let {
            MallGraphRepository.aStar(it, start.id, end.id)
        }
        // Use A* path distance for accurate estimates
        val realDistM = if (path != null)
            (path.totalDistancePx * 0.05).toInt().coerceAtLeast(1)
        else destDistM
        val realMins = (realDistM / 80f).coerceIn(1f, 20f).toInt()

        NavigationState.selectedPlace     = end
        NavigationState.startPlace        = start
        NavigationState.estimatedDistance = realDistM
        NavigationState.estimatedMinutes  = realMins
        NavigationState.aStarPath         = path
        onStoreSelected(useAr)
    } else if (end != null) {
        // No scanned start — navigate without start node
        NavigationState.selectedPlace     = end
        NavigationState.estimatedDistance = destDistM
        NavigationState.estimatedMinutes  = (destDistM / 72).coerceIn(1, 20)
        NavigationState.aStarPath         = null
        onStoreSelected(useAr)
    }
}

// ── Destination search row ───────────────────────────────────────────────────
@Composable
private fun DestinationRow(place: Place, onClick: () -> Unit) {
    val distM = remember(place.id) {
        // Simple Euclidean estimate in pixels * scale — will be overridden by A* when navigation starts
        val dx = place.x.toFloat(); val dy = place.y.toFloat()
        (kotlin.math.sqrt(dx * dx + dy * dy) * 0.05f).toInt().coerceAtLeast(1)
    }
    val mins = remember(distM) { (distM / 80f).coerceIn(1f, 10f).toInt() }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(60.dp), shape = RoundedCornerShape(13.dp),
            color = SurfaceLight, shadowElevation = 1.dp
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data("file:///android_asset/${place.logo}").crossfade(true).build(),
                contentDescription = place.brand,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp)).padding(7.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(place.brand, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, null, tint = RedAccent, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("${distM}m", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Icon(Icons.Filled.AccessTime, null, tint = RedAccent, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("${mins}min", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

// ── YUV → Bitmap ─────────────────────────────────────────────────────────────
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun ImageProxy.toBitmapSafe(): Bitmap? {
    val image = this.image ?: return null
    if (image.format != ImageFormat.YUV_420_888) {
        return try { this.toBitmap() } catch (e: Exception) { null }
    }
    val yBuf = image.planes[0].buffer
    val uBuf = image.planes[1].buffer
    val vBuf = image.planes[2].buffer
    val nv21 = ByteArray(yBuf.remaining() + uBuf.remaining() + vBuf.remaining())
    yBuf.get(nv21, 0, yBuf.remaining())
    vBuf.get(nv21, yBuf.capacity(), vBuf.remaining())
    uBuf.get(nv21, yBuf.capacity() + vBuf.capacity(), uBuf.remaining())
    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, width, height), 95, out)
    val bytes = out.toByteArray()
    val bmp   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val mat   = android.graphics.Matrix().also { it.postRotate(imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, true)
}