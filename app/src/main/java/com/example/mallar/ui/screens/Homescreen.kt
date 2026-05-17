package com.example.mallar.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.mallar.R
import com.example.mallar.data.Place
import com.example.mallar.data.PlaceRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Brand colors ─────────────────────────────────────────────────────────────
private val HomePrimary      = Color(0xFF258799)
private val HomePrimaryLight = Color(0xFF2fa3b8)
private val HomePrimaryDark  = Color(0xFF1a6b78)
private val HomeSurface      = Color(0xFFF7F9FA)
private val HomeCard         = Color(0xFFFFFFFF)
private val HomeTextMain     = Color(0xFF1A1A2E)
private val HomeTextSub      = Color(0xFF888EA8)
private val HomeRed          = Color(0xFFE53935)

// ── Category data ─────────────────────────────────────────────────────────────
// icon is Any so it can hold either ImageVector or Painter
private data class Category(val label: String, val icon: Any, val keywords: List<String>)

// ── Bottom nav items ──────────────────────────────────────────────────────────
private data class NavItem(val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem("Home",    Icons.Default.MyLocation),
    NavItem("Map",     Icons.Default.Map),
    NavItem("Saved",   Icons.Default.Bookmark),
    NavItem("Profile", Icons.Default.Settings),
)

// ─────────────────────────────────────────────────────────────────────────────
// HomeScreen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    onDestinationSelected: (Place) -> Unit,
    onSettingsClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onMapClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val isDarkMode by com.example.mallar.data.AppPreferences.isDarkMode.collectAsState()

    val currentSurface = if (isDarkMode) com.example.mallar.ui.theme.DarkBackground else HomeSurface
    val currentCard = if (isDarkMode) com.example.mallar.ui.theme.DarkCard else HomeCard
    val currentTextMain = if (isDarkMode) com.example.mallar.ui.theme.DarkTextPrimary else HomeTextMain
    val currentTextSub = if (isDarkMode) com.example.mallar.ui.theme.DarkTextSecondary else HomeTextSub

    // painterResource must be called inside a @Composable
    val clothesPainter = painterResource(R.drawable.clothes)

    // categories lives here so painterResource is inside the composition
    val categories = remember(clothesPainter) {
        listOf(
            Category("All",         Icons.Default.GridView,   emptyList()),
            Category("Clothes",     clothesPainter,           listOf("zara","h&m","bershka","pull","mango","gap","defacto","lcw","american")),
            Category("Food",        Icons.Default.Explore,    listOf("starbucks","mcdonalds","kfc","subway","pizza","coffee","burger","cafe")),
            Category("Shops",       Icons.Default.Settings,   listOf("shop","store","market","carrefour","lulu","virgin")),
            Category("Accessories", Icons.Default.AccessTime, listOf("swarovski","pandora","fossil","accessorize","claire")),
            Category("Beauty",      Icons.Default.Edit,       listOf("mac","nyx","sephora","inglot","beauty","cosmetics","salon")),
        )
    }

    // ── state ────────────────────────────────────────────────────────────────
    var allPlaces      by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searchQuery    by remember { mutableStateOf("") }
    var searchFocused  by remember { mutableStateOf(false) }
    var selectedCatIdx by remember { mutableStateOf(0) }
    var activeNavIdx   by remember { mutableStateOf(0) }

    val userName = remember {
        FirebaseAuth.getInstance().currentUser?.displayName
            ?.split(" ")?.firstOrNull()
            ?: FirebaseAuth.getInstance().currentUser?.phoneNumber?.takeLast(4)
            ?: "there"
    }

    // ── load data ─────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        val places = withContext(Dispatchers.IO) { PlaceRepository.load(context) }
        allPlaces = places
    }

    // ── filtered places ───────────────────────────────────────────────────────
    val displayedPlaces by remember {
        derivedStateOf {
            var base = if (searchQuery.isBlank()) allPlaces
            else allPlaces.filter { it.brand.contains(searchQuery, ignoreCase = true) }
            val cat = categories.getOrNull(selectedCatIdx)
            if (cat != null && cat.keywords.isNotEmpty()) {
                base = base.filter { place ->
                    cat.keywords.any { kw -> place.brand.contains(kw, ignoreCase = true) }
                }
            }
            base
        }
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(currentSurface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ═══════════════════════════════════════ HERO HEADER ══════════════
            HeroHeader(
                userName        = userName,
                searchQuery     = searchQuery,
                searchFocused   = searchFocused,
                isDarkMode      = isDarkMode,
                onSearchChange  = { searchQuery = it; selectedCatIdx = 0 },
                onSearchFocus   = { searchFocused = it },
                onSettingsClick = onSettingsClick,
                onVoiceClick    = onVoiceClick,
            )

            // ═══════════════════════════════════════ LAZY BODY ════════════════
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {

                // ── Categories ────────────────────────────────────────────────
                item(key = "spacer_top")  { Spacer(Modifier.height(22.dp)) }
                item(key = "cat_header")  { SectionHeader(title = "Categories", onSeeAll = {}, currentTextMain = currentTextMain) }
                item(key = "spacer_cat")  { Spacer(Modifier.height(12.dp)) }
                item(key = "cat_row") {
                    LazyRow(
                        contentPadding        = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(categories, key = { _, cat -> cat.label }) { idx, cat ->
                            CategoryChip(
                                category = cat,
                                selected = selectedCatIdx == idx,
                                isDarkMode = isDarkMode,
                                onClick  = { selectedCatIdx = idx }
                            )
                        }
                    }
                }

                if (searchQuery.isNotBlank()) {
                    // ── Search results ─────────────────────────────────────────
                    item(key = "search_spacer")  { Spacer(Modifier.height(24.dp)) }
                    item(key = "search_header")  {
                        SectionHeader(title = "Results (${displayedPlaces.size})", onSeeAll = null, currentTextMain = currentTextMain)
                    }
                    item(key = "search_spacer2") { Spacer(Modifier.height(12.dp)) }
                    if (displayedPlaces.isEmpty()) {
                        item(key = "search_empty") { EmptyState(currentTextSub) }
                    } else {
                        item(key = "search_row") {
                            LazyRow(
                                contentPadding        = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(displayedPlaces, key = { it.brand }) { place ->
                                    PlaceCard(place = place, isDarkMode = isDarkMode, currentCard = currentCard, currentTextMain = currentTextMain, currentTextSub = currentTextSub, onClick = { onDestinationSelected(place) })
                                }
                            }
                        }
                    }
                } else {


                    // ── All Stores ─────────────────────────────────────────────
                    item(key = "stores_spacer")  { Spacer(Modifier.height(28.dp)) }
                    item(key = "stores_header")  { SectionHeader(title = "All Stores", onSeeAll = {}, currentTextMain = currentTextMain) }
                    item(key = "stores_spacer2") { Spacer(Modifier.height(12.dp)) }

                    val list = if (selectedCatIdx == 0) allPlaces else displayedPlaces
                    if (list.isEmpty()) {
                        item(key = "stores_empty") { EmptyState(currentTextSub) }
                    } else {
                        items(list, key = { it.brand }) { place ->
                            StoreRow(
                                place    = place,
                                isDarkMode = isDarkMode,
                                currentCard = currentCard,
                                currentTextMain = currentTextMain,
                                currentTextSub = currentTextSub,
                                onClick  = { onDestinationSelected(place) },
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }

                item(key = "spacer_bottom") { Spacer(Modifier.height(12.dp)) }
            }
        }

        // ═══════════════════════════════════════ BOTTOM NAV ═══════════════════
        BottomNav(
            items       = navItems,
            activeIndex = activeNavIdx,
            isDarkMode  = isDarkMode,
            currentCard = currentCard,
            onSelect    = { idx ->
                activeNavIdx = idx
                when (idx) {
                    1 -> onMapClick()
                    3 -> onSettingsClick()
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hero Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeroHeader(
    userName: String,
    searchQuery: String,
    searchFocused: Boolean,
    isDarkMode: Boolean,
    onSearchChange: (String) -> Unit,
    onSearchFocus: (Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    onVoiceClick: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val waveTargets   = remember { listOf(16f, 22f, 14f) }
    val waveDurations = remember { listOf(600, 800, 550) }
    val waveHeights = List(3) { i ->
        infiniteTransition.animateFloat(
            initialValue  = 3f,
            targetValue   = waveTargets[i],
            animationSpec = infiniteRepeatable(
                animation  = tween(durationMillis = waveDurations[i], easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar$i"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = if (isDarkMode) listOf(Color(0xFF0F5F5F), Color(0xFF0A3D42), com.example.mallar.ui.theme.DarkBackground)
                             else listOf(HomePrimary, HomePrimaryLight, Color(0xFF9dd8e2), Color(0xFFe8f6f8))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 28.dp)
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier) {}
                Row(modifier = Modifier) {}
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text          = "Hello, $userName 👋",
                color         = Color.White,
                fontWeight    = FontWeight.Bold,
                fontSize      = 26.sp,
                letterSpacing = (-0.3).sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text       = "Where would you like to go?",
                color      = Color.White.copy(alpha = 0.82f),
                fontSize   = 15.sp,
                fontWeight = FontWeight.Normal
            )

            Spacer(Modifier.height(20.dp))

            // Search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation    = if (isDarkMode) 0.dp else if (searchFocused) 6.dp else 4.dp,
                        shape        = RoundedCornerShape(28.dp),
                        ambientColor = if (searchFocused) HomePrimary.copy(0.2f) else Color.Black.copy(0.1f)
                    )
                    .background(if (isDarkMode) com.example.mallar.ui.theme.DarkCard else Color.White, RoundedCornerShape(28.dp))
                    .border(
                        width = if (searchFocused) 2.dp else if (isDarkMode) 1.dp else 0.dp,
                        color = if (searchFocused) HomePrimary else if (isDarkMode) Color.White.copy(0.1f) else Color.Transparent,
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint     = if (searchFocused) HomePrimary else Color(0xFFAAAAAA),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value         = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine    = true,
                    textStyle     = androidx.compose.ui.text.TextStyle(
                        fontSize   = 15.sp,
                        color      = if (isDarkMode) com.example.mallar.ui.theme.DarkTextPrimary else HomeTextMain,
                        fontWeight = FontWeight.Normal
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 16.dp)
                        .onFocusChanged { focusState -> onSearchFocus(focusState.isFocused) },
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) {
                            Text("Where to go...", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                        }
                        inner()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick  = { onSearchChange("") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close, null,
                            tint     = Color(0xFFAAAAAA),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Category chip — smart-casts icon to ImageVector or Painter
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryChip(category: Category, selected: Boolean, isDarkMode: Boolean, onClick: () -> Unit) {
    val bgColor   by animateColorAsState(if (selected) HomePrimary else if (isDarkMode) com.example.mallar.ui.theme.DarkCard else HomeCard,    tween(200), label = "catBg")
    val textColor by animateColorAsState(if (selected) Color.White else if (isDarkMode) com.example.mallar.ui.theme.DarkTextSecondary else HomeTextSub, tween(200), label = "catText")
    val elevation by animateDpAsState(if (isDarkMode || selected) 0.dp else 2.dp, label = "catElev")

    Column(
        modifier = Modifier
            .shadow(elevation, RoundedCornerShape(18.dp))
            .background(bgColor, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .width(68.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // smart cast: supports both ImageVector (material icons) and Painter (drawables)
        when (val ic = category.icon) {
            is ImageVector -> Icon(
                imageVector        = ic,
                contentDescription = category.label,
                tint               = if (selected) Color.White else HomePrimary,
                modifier           = Modifier.size(26.dp)
            )
            is Painter -> Icon(
                painter            = ic,
                contentDescription = category.label,
                tint               = if (selected) Color.White else HomePrimary,
                modifier           = Modifier.size(26.dp)
            )
        }
        Text(
            text       = category.label,
            color      = textColor,
            fontSize   = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign  = TextAlign.Center,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Place card (horizontal scroll)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlaceCard(place: Place, isDarkMode: Boolean, currentCard: Color, currentTextMain: Color, currentTextSub: Color, onClick: () -> Unit) {
    var saved by remember { mutableStateOf(false) }

    Card(
        modifier  = Modifier.width(150.dp).clickable { onClick() }.border(if (isDarkMode) 1.dp else 0.dp, Color.White.copy(0.05f), RoundedCornerShape(20.dp)),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = currentCard),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDarkMode) 0.dp else 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(HomePrimary.copy(0.15f), HomePrimaryLight.copy(0.08f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (place.logo.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data("file:///android_asset/${place.logo}")
                            .crossfade(false)
                            .build(),
                        contentDescription = place.brand,
                        contentScale       = ContentScale.Fit,
                        modifier           = Modifier.fillMaxSize().padding(14.dp)
                    )
                } else {
                    Text(
                        text       = place.brand.take(2).uppercase(),
                        color      = HomePrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 26.sp
                    )
                }
                IconButton(
                    onClick  = { saved = !saved },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .background(Color.White.copy(0.85f), CircleShape)
                ) {
                    Icon(
                        imageVector        = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint               = if (saved) HomePrimary else HomePrimary.copy(0.35f),
                        modifier           = Modifier.size(15.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp, 10.dp, 10.dp, 12.dp)) {
                Text(
                    text       = place.brand,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp,
                    color      = currentTextMain,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Text(
                    text     = "Store",
                    fontSize = 11.sp,
                    color    = currentTextSub,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn, null,
                        tint     = HomePrimary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text       = "Inside Mall",
                        fontSize   = 11.sp,
                        color      = HomePrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Store row (vertical list)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StoreRow(place: Place, onClick: () -> Unit, modifier: Modifier = Modifier, isDarkMode: Boolean, currentCard: Color, currentTextMain: Color, currentTextSub: Color) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (isDarkMode) 0.dp else 1.dp, RoundedCornerShape(16.dp))
            .background(currentCard, RoundedCornerShape(16.dp))
            .border(if (isDarkMode) 1.dp else 0.dp, Color.White.copy(0.05f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    Brush.linearGradient(listOf(HomePrimary.copy(0.12f), HomePrimaryLight.copy(0.06f))),
                    RoundedCornerShape(13.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (place.logo.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data("file:///android_asset/${place.logo}")
                        .crossfade(false)
                        .build(),
                    contentDescription = place.brand,
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize().padding(8.dp)
                )
            } else {
                Text(
                    text       = place.brand.take(2).uppercase(),
                    color      = HomePrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 16.sp
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = place.brand,
                fontWeight = FontWeight.Bold,
                fontSize   = 15.sp,
                color      = currentTextMain,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(text = "Store · Inside Mall", fontSize = 12.sp, color = currentTextSub)
        }
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(HomePrimary.copy(0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Navigate",
                tint     = HomePrimary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, onSeeAll: (() -> Unit)?, currentTextMain: Color) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            text          = title,
            fontWeight    = FontWeight.Bold,
            fontSize      = 17.sp,
            color         = currentTextMain,
            letterSpacing = (-0.2).sp
        )
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll, contentPadding = PaddingValues(0.dp)) {
                Text("See all", color = HomePrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward, null,
                    tint     = HomePrimary,
                    modifier = Modifier.padding(start = 3.dp).size(13.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom navigation bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BottomNav(
    items: List<NavItem>,
    activeIndex: Int,
    isDarkMode: Boolean,
    currentCard: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(if (isDarkMode) 0.dp else 8.dp, RoundedCornerShape(28.dp))
                .background(currentCard, RoundedCornerShape(28.dp))
                .border(if (isDarkMode) 1.dp else 0.dp, Color.White.copy(0.05f), RoundedCornerShape(28.dp))
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            items.forEachIndexed { idx, item ->
                val active = activeIndex == idx
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSelect(idx) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier.then(
                            if (active)
                                Modifier
                                    .background(HomePrimary.copy(0.12f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 14.dp, vertical = 4.dp)
                            else Modifier
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector        = item.icon,
                            contentDescription = item.label,
                            tint               = if (active) HomePrimary else Color(0xFFBBBBBB),
                            modifier           = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text       = item.label,
                        fontSize   = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color      = if (active) HomePrimary else Color(0xFFBBBBBB)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(currentTextSub: Color) {
    Box(
        modifier         = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.SearchOff, null,
                tint     = HomePrimaryLight.copy(0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text("No stores found", color = currentTextSub, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}