package com.stalker.pda.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.GradientDrawable
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.stalker.pda.data.Poi
import com.stalker.pda.data.PoiKind
import com.stalker.pda.data.PoiRepository
import com.stalker.pda.data.model.ContactType
import com.stalker.pda.data.model.TaskStatus
import com.stalker.pda.data.model.parseGeo
import com.stalker.pda.ui.components.Panel
import com.stalker.pda.ui.components.PdaButton
import com.stalker.pda.ui.components.PdaDialog
import com.stalker.pda.ui.components.PdaTextField
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.viewmodel.PdaUiState
import com.stalker.pda.viewmodel.PdaViewModel
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

private const val C_SELF = "#2F80FF"      // me: blue
private const val C_FRIEND = "#3DFF3D"    // friends: green
private const val C_STRANGER = "#FF8A1F"  // strangers: orange
private const val C_TARGET = "#FF3DCB"    // target
private const val C_MEDIC = "#E23B2E"
private const val C_TRADER = "#F2B632"

enum class MapMode(val label: String) { SAT("Супутник"), STREET("Схема"), TOPO("Рельєф"), PDA("ПДА") }

private val ESRI_SAT = object : OnlineTileSourceBase(
    "EsriWorldImagery", 0, 19, 256, ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

private val OPEN_TOPO = XYTileSource(
    "OpenTopoMap", 0, 17, 256, ".png",
    arrayOf("https://a.tile.opentopomap.org/", "https://b.tile.opentopomap.org/", "https://c.tile.opentopomap.org/")
)

class MapHolder {
    var view: MapView? = null
}

@Composable
fun MapScreen(state: PdaUiState, vm: PdaViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { MapHolder() }
    var pois by remember { mutableStateOf<List<Poi>>(emptyList()) }
    var showPoi by rememberSaveable { mutableStateOf(false) }
    var poiStatus by remember { mutableStateOf("") }
    var modeIndex by rememberSaveable { mutableStateOf(0) }
    var showSearch by remember { mutableStateOf(false) }
    val mode = MapMode.entries[modeIndex % MapMode.entries.size]

    fun loadPois() {
        val mv = holder.view ?: return
        val bb = mv.boundingBox
        poiStatus = "пошук..."
        scope.launch {
            try {
                val result = PoiRepository.fetch(bb.latSouth, bb.lonWest, bb.latNorth, bb.lonEast)
                pois = result
                poiStatus = if (result.isEmpty()) "нічого не знайдено" else "знайдено: ${result.size}"
            } catch (e: Exception) {
                poiStatus = "помилка: ${e.message}"
            }
        }
    }

    // a request to centre the map (double tap on a target, search, ...)
    LaunchedEffect(state.mapCenterToken) {
        if (state.mapCenterToken != state.mapCenterConsumed) {
            val la = state.mapCenterLat
            val lo = state.mapCenterLng
            if (la != null && lo != null) {
                holder.view?.controller?.setZoom(17.0)
                holder.view?.controller?.animateTo(GeoPoint(la, lo))
            }
            vm.consumeCenter(state.mapCenterToken)
        }
    }

    Panel(modifier = modifier.fillMaxSize().padding(6.dp)) {
        OsmMapView(
            state = state,
            modifier = Modifier.fillMaxSize(),
            mode = mode,
            pois = if (showPoi) pois else emptyList(),
            onMapReady = { holder.view = it }
        )

        // big buttons, left column
        Column(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MapButton("⌖") {
                val loc = state.selfLocation
                if (loc != null) vm.centerMap(loc.latitude, loc.longitude)
                else Toast.makeText(ctx, "GPS ще не визначив позицію", Toast.LENGTH_SHORT).show()
            }
            MapButton("+") { holder.view?.controller?.zoomIn() }
            MapButton("−") { holder.view?.controller?.zoomOut() }
            MapButton(if (showPoi) "ПОІ✓" else "ПОІ", small = true) {
                showPoi = !showPoi
                if (showPoi) loadPois() else poiStatus = ""
            }
            if (showPoi) MapButton("↻") { loadPois() }
        }

        // big buttons, right column
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            MapButton("⎘") {
                val loc = state.selfLocation
                if (loc != null) {
                    val text = "%.6f, %.6f".format(java.util.Locale.US, loc.latitude, loc.longitude)
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("coords", text))
                    Toast.makeText(ctx, "Скопійовано: $text", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "GPS ще не визначив позицію", Toast.LENGTH_SHORT).show()
                }
            }
            MapButton("🔍") { showSearch = true }
            MapButton("🗺", small = true) { modeIndex = (modeIndex + 1) % MapMode.entries.size }
            Text(mode.label, style = PdaType.Small.copy(color = PdaColors.TextWhite), modifier = Modifier.background(PdaColors.PanelBg.copy(alpha = 0.8f)).padding(4.dp))
        }

        if (poiStatus.isNotEmpty()) {
            Text(
                "ПОІ: $poiStatus",
                style = PdaType.Small.copy(color = PdaColors.TextWhite),
                modifier = Modifier.align(Alignment.TopCenter).background(PdaColors.PanelBg.copy(alpha = 0.8f)).padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(PdaColors.PanelBg.copy(alpha = 0.8f)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Legend("Я", C_SELF)
            Legend("Друзі", C_FRIEND)
            Legend("Незнайомці", C_STRANGER)
            Legend("Ціль", C_TARGET)
        }
        Text(
            if (mode == MapMode.SAT) "Карта Зони · © Esri" else "Карта Зони · © OSM",
            style = PdaType.Tiny.copy(color = PdaColors.TextWhite),
            modifier = Modifier.align(Alignment.BottomEnd).background(PdaColors.PanelBg.copy(alpha = 0.8f)).padding(6.dp)
        )
    }

    if (showSearch) {
        var lat by remember { mutableStateOf("") }
        var lng by remember { mutableStateOf("") }
        PdaDialog(title = "Перейти за координатами", onDismiss = { showSearch = false }) {
            PdaTextField(lat, { lat = it }, "Широта (lat), напр. 51.2741", Modifier.fillMaxWidth(), singleLine = true)
            PdaTextField(lng, { lng = it }, "Довгота (lng), напр. 30.2219", Modifier.fillMaxWidth(), singleLine = true)
            Text("Можна вставити обидві в перше поле через кому", style = PdaType.Tiny)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PdaButton("Перейти", PdaColors.Green, {
                    val g = if (lng.isBlank()) parseGeo(lat) else parseGeo("$lat, $lng")
                    if (g != null) {
                        vm.openMapAt(g.lat, g.lng, "Пошук")
                        showSearch = false
                    } else {
                        Toast.makeText(ctx, "Невірні координати", Toast.LENGTH_SHORT).show()
                    }
                })
                PdaButton("Скасувати", PdaColors.TextGrey, { showSearch = false })
            }
        }
    }
}

@Composable
private fun MapButton(label: String, small: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(PdaColors.PanelBg.copy(alpha = 0.88f))
            .border(1.dp, PdaColors.BorderBright)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = PdaType.Display.copy(fontSize = if (small) 12.sp else 20.sp, color = PdaColors.TextWhite))
    }
}

@Composable
private fun Legend(label: String, colorHex: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Color(android.graphics.Color.parseColor(colorHex)), androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = PdaType.Small.copy(color = PdaColors.TextWhite))
    }
}

private class MapMemo {
    var mode: MapMode? = null
    var centeredOnSelf = false
}

private fun addDot(map: MapView, point: GeoPoint, title: String, colorHex: String, sizeDp: Int) {
    val marker = Marker(map)
    marker.position = point
    marker.title = title
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    val px = (sizeDp * map.resources.displayMetrics.density).toInt()
    val dot = GradientDrawable()
    dot.shape = GradientDrawable.OVAL
    dot.setColor(android.graphics.Color.parseColor(colorHex))
    dot.setStroke((2 * map.resources.displayMetrics.density).toInt().coerceAtLeast(2), android.graphics.Color.WHITE)
    dot.setSize(px, px)
    marker.icon = dot
    map.overlays.add(marker)
}

/** OSMdroid map: satellite / street / relief / PDA-green, colored dots for people, targets and places. */
@Composable
fun OsmMapView(
    state: PdaUiState,
    modifier: Modifier = Modifier,
    mode: MapMode = MapMode.SAT,
    pois: List<Poi> = emptyList(),
    onMapReady: (MapView) -> Unit = {}
) {
    val memo = remember { MapMemo() }

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            factory = { context ->
                val cfg = Configuration.getInstance()
                cfg.userAgentValue = context.packageName
                val base = File(context.filesDir, "osmdroid")
                cfg.osmdroidBasePath = base
                cfg.osmdroidTileCache = File(base, "tiles")

                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    minZoomLevel = 5.0
                    maxZoomLevel = 19.0
                    isHorizontalMapRepetitionEnabled = false
                    isVerticalMapRepetitionEnabled = false
                    setScrollableAreaLimitLatitude(85.0, -85.0, 0)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(51.2741, 30.2219))
                    onMapReady(this)
                }
            },
            update = { mapView ->
                if (memo.mode != mode) {
                    memo.mode = mode
                    mapView.setTileSource(
                        when (mode) {
                            MapMode.SAT -> ESRI_SAT
                            MapMode.TOPO -> OPEN_TOPO
                            else -> TileSourceFactory.MAPNIK
                        }
                    )
                    if (mode == MapMode.PDA) {
                        // PDA look: invert luminance and push it into the green channel
                        val tint = android.graphics.ColorMatrix(
                            floatArrayOf(
                                -0.045f, -0.0885f, -0.0165f, 0f, 38f,
                                -0.24f, -0.472f, -0.088f, 0f, 204f,
                                -0.015f, -0.0295f, -0.0055f, 0f, 12.75f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        mapView.overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(tint))
                    } else {
                        mapView.overlayManager.tilesOverlay.setColorFilter(null)
                    }
                }

                if (!memo.centeredOnSelf) {
                    val loc = state.selfLocation
                    if (loc != null) {
                        memo.centeredOnSelf = true
                        mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                    }
                }

                mapView.overlays.clear()
                pois.forEach { p ->
                    val medic = p.kind == PoiKind.MEDIC
                    addDot(
                        mapView, GeoPoint(p.lat, p.lng),
                        (if (medic) "МЕДИК: " else "ТОРГОВЕЦЬ: ") + p.name,
                        if (medic) C_MEDIC else C_TRADER, 12
                    )
                }
                state.tasks.forEach { t ->
                    if (t.status != TaskStatus.ACTIVE) return@forEach
                    t.geoTag?.let { g -> addDot(mapView, GeoPoint(g.lat, g.lng), "ЦІЛЬ: " + t.title, C_TARGET, 14) }
                    t.subTasks.forEach { st ->
                        val g = st.geoTag
                        if (g != null && !st.isDone) addDot(mapView, GeoPoint(g.lat, g.lng), "ЕТАП: " + st.title, C_TARGET, 14)
                    }
                }
                state.contacts.forEach { c ->
                    val la = c.lastKnownLat
                    val ln = c.lastKnownLng
                    if (la != null && ln != null && c.onlineStatus != com.stalker.pda.data.model.ContactOnlineStatus.OFFLINE) {
                        val friend = c.type == ContactType.FRIEND
                        addDot(
                            mapView, GeoPoint(la, ln),
                            (if (friend) "ДРУГ: " else "НЕЗНАЙОМЕЦЬ: ") + c.callsign,
                            if (friend) C_FRIEND else C_STRANGER, 18
                        )
                    }
                }
                val tl = state.mapFocusLat
                val tg = state.mapFocusLng
                if (tl != null && tg != null) {
                    addDot(mapView, GeoPoint(tl, tg), "ЦІЛЬ: " + (state.mapFocusLabel ?: ""), C_TARGET, 24)
                }
                state.selfLocation?.let { loc ->
                    addDot(mapView, GeoPoint(loc.latitude, loc.longitude), "Я", C_SELF, 18)
                }
                mapView.invalidate()
            },
            onRelease = { it.onDetach() },
            modifier = Modifier.fillMaxSize()
        )
    }
}
