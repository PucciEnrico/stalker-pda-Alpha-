package com.stalker.pda.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.GradientDrawable
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.stalker.pda.data.RouteRepository
import com.stalker.pda.data.RouteResult
import com.stalker.pda.data.model.targetGeo
import kotlinx.coroutines.delay
import org.osmdroid.views.overlay.Polyline
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
private const val C_TARGET = "#FFD400"    // target: yellow map pin
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
    var picking by remember { mutableStateOf(false) }
    var centerText by remember { mutableStateOf("") }

    // pedestrian route to the target, or to the selected stage / task that has a geotag
    val dest: Triple<Double, Double, String>? = run {
        val fl = state.mapFocusLat
        val fg = state.mapFocusLng
        if (fl != null && fg != null) {
            Triple(fl, fg, state.mapFocusLabel ?: "Ціль")
        } else {
            val t = state.tasks.find { it.id == state.selectedTaskId }
            val st = t?.subTasks?.find { it.id == state.selectedStageId }
            val g = st?.geoTag ?: t?.targetGeo()
            if (g != null && t != null) Triple(g.lat, g.lng, st?.title ?: t.title) else null
        }
    }
    var route by remember { mutableStateOf<RouteResult?>(null) }
    val me = state.selfLocation
    val destKey = dest?.let { "%.5f,%.5f".format(java.util.Locale.US, it.first, it.second) }
    val originKey = me?.let { "%.3f,%.3f".format(java.util.Locale.US, it.latitude, it.longitude) }
    LaunchedEffect(destKey, originKey) {
        route = if (dest != null && me != null) RouteRepository.fetch(me.latitude, me.longitude, dest.first, dest.second) else null
    }
    val routePoints = route?.points?.map { GeoPoint(it.first, it.second) } ?: emptyList()

    // while picking, show the coordinates under the pin tip (the centre of the map)
    LaunchedEffect(picking) {
        while (picking) {
            holder.view?.mapCenter?.let {
                centerText = "%.6f, %.6f".format(java.util.Locale.US, it.latitude, it.longitude)
            }
            delay(150)
        }
    }

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
            route = routePoints,
            onMapReady = { holder.view = it }
        )

        // buttons: left group, in a row at the top-left
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
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

        // buttons: right group, in a row at the top-right
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(mode.label, style = PdaType.Small.copy(color = PdaColors.TextWhite), modifier = Modifier.background(PdaColors.PanelBg.copy(alpha = 0.8f)).padding(4.dp))
            // 1st tap: a pin appears in the centre, move the map so the point is at its tip;
            // 2nd tap: the coordinates of that point are copied.
            if (picking) MapButton("✕") { picking = false }
            MapButton(if (picking) "✓" else "⎘") {
                if (!picking) {
                    picking = true
                } else {
                    val c = holder.view?.mapCenter
                    if (c != null) {
                        val text = "%.6f, %.6f".format(java.util.Locale.US, c.latitude, c.longitude)
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("coords", text))
                        Toast.makeText(ctx, "Скопійовано: $text", Toast.LENGTH_SHORT).show()
                    }
                    picking = false
                }
            }
            MapButton("🔍") { showSearch = true }
            MapButton("🗺", small = true) { modeIndex = (modeIndex + 1) % MapMode.entries.size }
        }

        if (poiStatus.isNotEmpty()) {
            Text(
                "ПОІ: $poiStatus",
                style = PdaType.Small.copy(color = PdaColors.TextWhite),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 44.dp).background(PdaColors.PanelBg.copy(alpha = 0.8f)).padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        if (picking) {
            PickPin(Modifier.align(Alignment.Center).offset(y = (-17).dp))
            Text(
                "Постав кінчик булавки на потрібну точку: $centerText",
                style = PdaType.Small.copy(color = PdaColors.TextWhite),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).background(PdaColors.PanelBg.copy(alpha = 0.85f)).padding(6.dp)
            )
        }
        route?.let { r ->
            val km = r.distanceM / 1000.0
            val dist = if (r.distanceM >= 1000) "%.1f км".format(java.util.Locale.US, km) else "${r.distanceM.toInt()} м"
            val mins = (r.durationS / 60).toInt().coerceAtLeast(1)
            Text(
                "Маршрут пішки: $dist · ≈ $mins хв" + if (r.straight) " (по прямій, маршрут недоступний)" else "",
                style = PdaType.Small.copy(color = PdaColors.TextWhite),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).background(PdaColors.PanelBg.copy(alpha = 0.85f)).padding(6.dp)
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
            .size(28.dp)
            .background(PdaColors.PanelBg.copy(alpha = 0.88f))
            .border(1.dp, PdaColors.BorderBright)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = PdaType.Display.copy(fontSize = if (small) 8.sp else 13.sp, color = PdaColors.TextWhite))
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

/** Fixed pin in the middle of the screen; its tip (bottom) marks the point whose coordinates are copied. */
@Composable
private fun PickPin(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 24.dp, height = 34.dp)) {
        val r = size.width / 2f
        val red = Color(0xFFE5392E)
        val tail = Path().apply {
            moveTo(r - r * 0.62f, r + r * 0.62f)
            lineTo(r, size.height)
            lineTo(r + r * 0.62f, r + r * 0.62f)
            close()
        }
        drawPath(tail, red)
        drawCircle(red, radius = r * 0.92f, center = Offset(r, r))
        drawCircle(Color(0xFF7A1812), radius = r * 0.92f, center = Offset(r, r), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        drawCircle(Color.White, radius = r * 0.36f, center = Offset(r, r))
    }
}

/** Teardrop map pin (like the Google Maps marker); the tip is at the point. */
private fun addPin(map: MapView, point: GeoPoint, title: String, colorHex: String, widthDp: Int) {
    val d = map.resources.displayMetrics.density
    val w = (widthDp * d).toInt().coerceAtLeast(12)
    val h = (w * 1.4f).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = w / 2f
    val body = android.graphics.Path()
    body.addCircle(r, r, r * 0.9f, android.graphics.Path.Direction.CW)
    val tail = android.graphics.Path()
    tail.moveTo(r - r * 0.62f, r + r * 0.62f)
    tail.lineTo(r, h - 1f)
    tail.lineTo(r + r * 0.62f, r + r * 0.62f)
    tail.close()
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = android.graphics.Color.parseColor(colorHex)
    c.drawPath(body, paint)
    c.drawPath(tail, paint)
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 1.5f * d
    paint.color = android.graphics.Color.parseColor("#8A6F00")
    c.drawPath(body, paint)
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    c.drawCircle(r, r, r * 0.36f, paint)

    val marker = Marker(map)
    marker.position = point
    marker.title = title
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    marker.icon = android.graphics.drawable.BitmapDrawable(map.resources, bmp)
    map.overlays.add(marker)
}

/** OSMdroid map: satellite / street / relief / PDA-green, colored dots for people, targets and places. */
@Composable
fun OsmMapView(
    state: PdaUiState,
    modifier: Modifier = Modifier,
    mode: MapMode = MapMode.SAT,
    pois: List<Poi> = emptyList(),
    route: List<GeoPoint> = emptyList(),
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
                if (route.size >= 2) {
                    val dens = mapView.resources.displayMetrics.density
                    val casing = Polyline(mapView)
                    casing.setPoints(route)
                    casing.outlinePaint.color = android.graphics.Color.WHITE
                    casing.outlinePaint.strokeWidth = 7f * dens
                    casing.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    mapView.overlays.add(casing)
                    val line = Polyline(mapView)
                    line.setPoints(route)
                    line.outlinePaint.color = android.graphics.Color.parseColor("#1E90FF")
                    line.outlinePaint.strokeWidth = 4f * dens
                    line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    mapView.overlays.add(line)
                }
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
                    t.geoTag?.let { g -> addPin(mapView, GeoPoint(g.lat, g.lng), "ЦІЛЬ: " + t.title, C_TARGET, 18) }
                    t.subTasks.forEach { st ->
                        val g = st.geoTag
                        if (g != null && !st.isDone) addPin(mapView, GeoPoint(g.lat, g.lng), "ЕТАП: " + st.title, C_TARGET, 18)
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
                    addPin(mapView, GeoPoint(tl, tg), "ЦІЛЬ: " + (state.mapFocusLabel ?: ""), C_TARGET, 26)
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
