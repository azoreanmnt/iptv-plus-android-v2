package pt.iptvplus.app

import android.content.res.Configuration
import android.util.JsonReader
import android.util.JsonToken
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val bg = Color(0xFF080D18)
private val panel = Color(0xFF111A2B)
private val muted = Color(0xFF9BAAC2)
private val accents = listOf(Color(0xFF7C5CFF), Color(0xFF24C8E8), Color(0xFF47E0C0), Color(0xFFFF6B8A), Color(0xFFFFB547), Color(0xFF4C8DFF), Color(0xFFB879FF), Color(0xFFFA6BCE))
private data class TvItem(val name:String, val url:String, val group:String="Geral", val logo:String="", val kind:String="live", val id:String="", val extension:String="mp4")
private data class Programme(val channel:String, val title:String, val start:String, val stop:String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { IPTVPlusApp() } }
}

@OptIn(UnstableApi::class)
@Composable
private fun IPTVPlusApp() {
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val isTv = cfg.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION || cfg.screenWidthDp >= 800
    var page by remember { mutableStateOf("Início") }
    var accentIndex by remember { mutableIntStateOf(0) }
    var sourceType by remember { mutableStateOf("Xtream Codes") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var playlistUrl by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Adiciona uma lista IPTV autorizada para começar.") }
    var loading by remember { mutableStateOf(false) }
    var vodLoaded by remember { mutableStateOf(false) }
    var seriesLoaded by remember { mutableStateOf(false) }
    var homePreviewLoaded by remember { mutableStateOf(false) }
    val maxLiveChannels = 5000 // limite de segurança para listas muito grandes
    val homeMovies = remember { mutableStateListOf<TvItem>() }
    val homeSeries = remember { mutableStateListOf<TvItem>() }
    var channels by remember { mutableStateOf<List<TvItem>>(emptyList()) }
    val programmes = remember { mutableStateListOf<Programme>() }
    var selected by remember { mutableStateOf<TvItem?>(null) }
    var filter by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Todos") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var bufferSetting by remember { mutableStateOf("10") }
    var decoderMode by remember { mutableStateOf("auto") }
    var selectedSeries by remember { mutableStateOf<TvItem?>(null) }
    val movies = remember { mutableStateListOf<TvItem>() }
    val series = remember { mutableStateListOf<TvItem>() }
    val episodes = remember { mutableStateListOf<TvItem>() }
    val favorites = remember { mutableStateListOf<String>() }
    val exoPlayer = remember(context, bufferSetting, decoderMode) {
        val (minBufferMs, maxBufferMs) = when (bufferSetting) {
            "5" -> 5_000 to 15_000
            "20" -> 20_000 to 50_000
            "30" -> 30_000 to 60_000
            else -> 10_000 to 30_000
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, 1_500, 3_000)
            .build()
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(if (decoderMode == "software") MediaCodecSelector.PREFER_SOFTWARE else MediaCodecSelector.DEFAULT)
        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    val accent = accents[accentIndex]
    LaunchedEffect(fullscreen) {
        val activity = context as? Activity
        activity?.window?.decorView?.systemUiVisibility = if (fullscreen) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else 0
    }

    fun play(item: TvItem) { selected = item; exoPlayer.setMediaItem(MediaItem.fromUri(item.url)); exoPlayer.prepare(); exoPlayer.playWhenReady = true; page = "Leitor"; fullscreen = false }
    fun launchLoad(block: suspend () -> Unit) {
        loading = true
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            try { block() } catch (e: Exception) { status = "Erro: ${e.message ?: "não foi possível carregar a lista"}" } finally { loading = false }
        } ?: run { loading = false }
    }
    fun openSeries(item: TvItem) = launchLoad {
        val found = withContext(Dispatchers.IO) { IptvData.loadSeriesEpisodes(server, username, password, item.id) }
        selectedSeries = item; episodes.clear(); episodes.addAll(found)
        status = "${found.size} episódios encontrados para ${item.name}."; page = "Episódios"
    }
    fun loadM3u() = launchLoad {
        val parsed = withContext(Dispatchers.IO) { IptvData.loadM3u(playlistUrl) }
        channels = parsed; status = "Lista carregada: ${parsed.size} canais."; page = "TV em direto"
    }
    fun loadXtream() = launchLoad {
        val found = withContext(Dispatchers.IO) { IptvData.loadXtreamLive(server, username, password, maxLiveChannels) }
        channels = found
        movies.clear(); series.clear(); homeMovies.clear(); homeSeries.clear(); vodLoaded = false; seriesLoaded = false; homePreviewLoaded = false
        status = "Xtream Codes: ${channels.size} canais carregados. Filmes e séries serão carregados apenas quando abrires essas secções."
        page = "Início"
    }
    LaunchedEffect(page, server, username, password, channels.size, loading) {
        if (page == "Início" && channels.isNotEmpty() && server.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !homePreviewLoaded && !loading) launchLoad {
            val previews = withContext(Dispatchers.IO) { IptvData.loadXtreamHomePreview(server, username, password, 18) }
            homeMovies.clear(); homeMovies.addAll(previews.first)
            homeSeries.clear(); homeSeries.addAll(previews.second)
            homePreviewLoaded = true
        }
    }
    LaunchedEffect(page, server, username, password) {
        if (server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            if (page == "Filmes" && !vodLoaded && !loading) launchLoad {
                val found = withContext(Dispatchers.IO) { IptvData.loadVodCatalog(server, username, password) }
                movies.clear(); movies.addAll(found); vodLoaded = true
                status = "Filmes carregados: ${movies.size}."
            }
            if (page == "Séries" && !seriesLoaded && !loading) launchLoad {
                val found = withContext(Dispatchers.IO) { IptvData.loadSeriesCatalog(server, username, password) }
                series.clear(); series.addAll(found); seriesLoaded = true
                status = "Séries carregadas: ${series.size}."
            }
        }
    }
    fun loadEpg() = launchLoad {
        val result = withContext(Dispatchers.IO) { IptvData.loadXmltv(epgUrl) }
        programmes.clear(); programmes.addAll(result); status = "EPG carregado: ${result.size} programas."; page = "EPG"
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = panel, onSurface = Color.White, onBackground = Color.White)) {
        Scaffold(containerColor = bg, bottomBar = { if (!isTv && !fullscreen) {
            Surface(color = bg, tonalElevation = 0.dp, shadowElevation = 10.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).background(panel, RoundedCornerShape(28.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Início" to Icons.Default.Home, "TV" to Icons.Default.LiveTv, "Filmes" to Icons.Default.Movie, "Séries" to Icons.Default.Tv, "Mais" to Icons.Default.MoreHoriz).forEach { (label, icon) ->
                        val target = if (label == "TV") "TV em direto" else label
                        val active = page == target || (label == "Mais" && page in listOf("Mais", "EPG", "Listas", "Definições"))
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(if (active) accent.copy(alpha = .18f) else Color.Transparent).clickable { page = target }.padding(vertical = 8.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(icon, contentDescription = label, tint = if (active) accent else muted, modifier = Modifier.size(21.dp))
                            Text(label, color = if (active) Color.White else muted, fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
            }
        } }) { pad ->
            Row(Modifier.fillMaxSize().padding(if (fullscreen) PaddingValues(0.dp) else pad).background(bg)) {
                if (isTv && !fullscreen) NavigationRail(containerColor = panel, header = { Text("IPTV+", color = accent, fontWeight = FontWeight.Black, modifier = Modifier.padding(14.dp)) }) {
                    listOf("Início" to Icons.Default.Home, "TV em direto" to Icons.Default.LiveTv, "Filmes" to Icons.Default.Movie, "Séries" to Icons.Default.Tv, "Mais" to Icons.Default.MoreHoriz).forEach { (label, icon) ->
                        NavigationRailItem(selected = page == label || (label == "Mais" && page in listOf("Mais", "EPG", "Listas", "Definições")), onClick = { page = label }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 10.sp) }, alwaysShowLabel = true)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(if (fullscreen) PaddingValues(0.dp) else PaddingValues(horizontal = if (isTv) 24.dp else 16.dp, vertical = 12.dp))) {
                    if (!fullscreen) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { Text("IPTV+", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(page, fontSize = if (isTv) 28.sp else 24.sp, fontWeight = FontWeight.Bold) }
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = accent)
                    }
                    if (!fullscreen) Spacer(Modifier.height(12.dp))
                    when (page) {
                        "Início" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (channels.isEmpty()) {
                                Surface(shape = RoundedCornerShape(22.dp), color = panel, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
                                    Text("A tua televisão, à tua maneira", fontSize = if (isTv) 26.sp else 21.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp)); Text("Importa uma lista M3U ou liga-te através de Xtream Codes para veres canais, filmes e séries.", color = muted)
                                    Spacer(Modifier.height(16.dp)); Button(onClick = { page = "Listas" }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Icon(Icons.Default.AddLink, null); Spacer(Modifier.width(8.dp)); Text("Adicionar lista") }
                                } }
                            } else {
                                Surface(shape = RoundedCornerShape(22.dp), color = panel, modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text("A tua biblioteca", fontSize = if (isTv) 25.sp else 21.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(5.dp)); Text("${channels.size} canais disponíveis", color = muted); Text("Descobre filmes e séries da tua lista", color = muted, fontSize = 13.sp) }
                                    Button(onClick = { page = "Listas" }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(5.dp)); Text("Listas") }
                                } }
                                if (homeMovies.isNotEmpty()) PosterRail("Filmes em destaque", homeMovies, accent, isTv) { play(it) }
                                else if (movies.isNotEmpty()) PosterRail("Filmes", movies.take(18), accent, isTv) { play(it) }
                                else if (!homePreviewLoaded && server.isNotBlank()) Text("A preparar capas de filmes…", color = muted, fontSize = 13.sp)
                                else if (homePreviewLoaded && homeMovies.isEmpty()) Text("Não foram encontradas capas de filmes nesta lista.", color = muted, fontSize = 13.sp)
                                if (homeSeries.isNotEmpty()) PosterRail("Séries", homeSeries, accent, isTv) { openSeries(it) }
                                else if (series.isNotEmpty()) PosterRail("Séries", series.take(18), accent, isTv) { openSeries(it) }
                                else if (homePreviewLoaded && homeSeries.isEmpty()) Text("Não foram encontradas séries nesta lista.", color = muted, fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    StatCard("Canais", channels.size.toString(), Modifier.weight(1f), accent)
                                    StatCard("Filmes", if (vodLoaded) movies.size.toString() else "Ver", Modifier.weight(1f), accent)
                                    StatCard("Séries", if (seriesLoaded) series.size.toString() else "Ver", Modifier.weight(1f), accent)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(onClick = { page = "TV em direto" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Icon(Icons.Default.LiveTv, null); Spacer(Modifier.width(5.dp)); Text("Ver TV") }
                                    OutlinedButton(onClick = { page = "Filmes" }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Movie, null); Spacer(Modifier.width(5.dp)); Text("Todos os filmes") }
                                }
                            }
                            Text(status, color = muted, fontSize = 12.sp)
                        }
                        "Listas" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Adicionar origem", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = sourceType == "Xtream Codes", onClick = { sourceType = "Xtream Codes" }, label = { Text("Xtream Codes") })
                                FilterChip(selected = sourceType == "M3U", onClick = { sourceType = "M3U" }, label = { Text("M3U/M3U8") })
                            }
                            if (sourceType == "Xtream Codes") {
                                OutlinedTextField(server, { server = it }, label = { Text("Servidor (ex.: https://servidor:porta)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(username, { username = it }, label = { Text("Utilizador") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(password, { password = it }, label = { Text("Palavra-passe") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Button(onClick = { loadXtream() }, enabled = !loading && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Ligar e carregar canais") }
                            } else {
                                OutlinedTextField(playlistUrl, { playlistUrl = it }, label = { Text("URL da lista M3U/M3U8") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Button(onClick = { loadM3u() }, enabled = !loading && playlistUrl.startsWith("http", true), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Importar lista") }
                            }
                            HorizontalDivider(color = muted.copy(alpha=.2f))
                            Text("EPG / XMLTV", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(epgUrl, { epgUrl = it }, label = { Text("URL XMLTV (opcional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = { loadEpg() }, enabled = !loading && epgUrl.startsWith("http", true), modifier = Modifier.fillMaxWidth()) { Text("Carregar EPG") }
                            Text("HTTP e HTTPS são suportados. HTTP não é encriptado; evita enviar credenciais por ligações inseguras.", color = muted, fontSize = 12.sp)
                            Text(status, color = muted)
                        }
                        "TV em direto" -> Column(Modifier.fillMaxSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(filter, { filter = it }, label = { Text("Pesquisar canais") }, modifier = Modifier.weight(1f), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
                                FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Icon(Icons.Default.Favorite, null) })
                            }
                            if (channels.isEmpty()) EmptyPanel("Ainda não há canais", "Vai a Mais → Listas para importar uma lista M3U ou ligar Xtream Codes.", accent)
                            else {
                                val groupCounts = remember(channels.size) { channels.groupingBy { it.group.ifBlank { "Geral" } }.eachCount().toSortedMap() }
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = category == "Todos", onClick = { category = "Todos" }, label = { Text("Todos (${channels.size})") })
                                    groupCounts.forEach { (groupName, count) ->
                                        FilterChip(selected = category == groupName, onClick = { category = groupName }, label = { Text("$groupName ($count)", maxLines = 1) })
                                    }
                                }
                                val visibleChannels = remember(channels, filter, favoritesOnly, category, favorites.toList()) {
                                    channels.filter { it.name.contains(filter, true) && (!favoritesOnly || it.url in favorites) && (category == "Todos" || it.group.ifBlank { "Geral" } == category) }
                                }
                                if (visibleChannels.isEmpty()) EmptyPanel("Sem resultados", "Altera a categoria ou a pesquisa.", accent)
                                else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(visibleChannels, key = { it.url }) { item -> ChannelRow(item, accent, muted, panel, favorites) { play(item) } }
                                }
                                if (channels.size >= maxLiveChannels) Text("Por segurança, são apresentados no máximo $maxLiveChannels canais desta lista. Podes pesquisar e filtrar por categoria.", color = muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                            }
                        }
                        "Mais" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Biblioteca e opções", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            MoreMenuRow("Guia de programação", "Consulta o EPG/XMLTV", Icons.Default.CalendarMonth, accent, panel) { page = "EPG" }
                            MoreMenuRow("Listas e ligações", "Xtream Codes, M3U/M3U8 e EPG", Icons.Default.PlaylistPlay, accent, panel) { page = "Listas" }
                            MoreMenuRow("Definições", "Cores e preferências da aplicação", Icons.Default.Settings, accent, panel) { page = "Definições" }
                        }
                        "EPG" -> if (programmes.isEmpty()) EmptyPanel("Guia de programação vazio", "Adiciona o URL XMLTV em Listas para carregar os programas.", accent) else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(programmes) { p -> Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(12.dp)) { Text(p.channel, color = accent, fontWeight = FontWeight.Bold); Text(p.title, fontWeight = FontWeight.SemiBold); Text("${p.start} – ${p.stop}", color = muted, fontSize = 12.sp) } }
                        }
                        "Filmes" -> MediaLibraryPage("Filmes", movies, filter, { filter = it }, accent, emptyHint = "Liga uma conta Xtream Codes para carregar filmes.", onSelect = { play(it) })
                        "Séries" -> MediaLibraryPage("Séries", series, filter, { filter = it }, accent, emptyHint = "Liga uma conta Xtream Codes para carregar séries.", onSelect = { openSeries(it) })
                        "Episódios" -> Column(Modifier.fillMaxSize()) {
                            Text(selectedSeries?.name ?: "Episódios", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            if (episodes.isEmpty()) EmptyPanel("Sem episódios disponíveis", "O fornecedor não devolveu episódios para esta série.", accent)
                            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(episodes) { ep -> Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).clickable { play(ep) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PlayCircle, null, tint = accent); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(ep.name, fontWeight = FontWeight.SemiBold); Text(ep.group, color = muted, fontSize = 12.sp) }; Icon(Icons.Default.PlayArrow, null, tint = accent) } }
                            }
                        }
                        "Leitor" -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                            AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShowSubtitleButton(true) } }, modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(if (isTv) 420.dp else 240.dp).align(Alignment.Center))
                            if (fullscreen) IconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Icon(Icons.Default.FullscreenExit, "Sair de ecrã inteiro", tint = Color.White) }
                            if (!fullscreen) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(selected?.name ?: "Leitor integrado", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { exoPlayer.play() }) { Icon(Icons.Default.PlayArrow, null); Text("Reproduzir") }; OutlinedButton(onClick = { exoPlayer.pause() }) { Icon(Icons.Default.Pause, null); Text("Pausa") }; OutlinedButton(onClick = { fullscreen = true }) { Icon(Icons.Default.Fullscreen, null); Text("Ecrã inteiro") } }
                            }
                        }
                        "Definições" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Leitor de vídeo", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Tamanho do buffer", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Mais buffer pode reduzir interrupções, mas aumenta o tempo inicial de carregamento.", color = muted, fontSize = 12.sp)
                            listOf("5" to "5 segundos — ligação rápida", "10" to "10 segundos — equilibrado", "20" to "20 segundos — ligação instável", "30" to "30 segundos — buffer elevado").forEach { (value, label) ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(panel).clickable { bufferSetting = value }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.Medium); Text(if (bufferSetting == value) "Selecionado" else "", color = accent, fontSize = 11.sp) }
                                    RadioButton(selected = bufferSetting == value, onClick = { bufferSetting = value })
                                }
                            }
                            HorizontalDivider(color = muted.copy(alpha=.2f))
                            Text("Descodificação de vídeo", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("A alteração recria o leitor e aplica-se à próxima reprodução.", color = muted, fontSize = 12.sp)
                            listOf("auto" to "Automático / hardware preferido", "software" to "Software preferido").forEach { (value, label) ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(panel).clickable { decoderMode = value }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.Medium); Text(if (value == "software") "Pode usar mais CPU e bateria" else "Recomendado para a maioria dos dispositivos", color = muted, fontSize = 11.sp) }
                                    RadioButton(selected = decoderMode == value, onClick = { decoderMode = value })
                                }
                            }
                            HorizontalDivider(color = muted.copy(alpha=.2f))
                            Text("Cor de destaque", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            accents.forEachIndexed { index, color -> Row(Modifier.fillMaxWidth().clickable { accentIndex = index }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(28.dp).background(color, RoundedCornerShape(50))); Spacer(Modifier.width(12.dp)); Text(listOf("Violeta", "Ciano", "Menta", "Rosa", "Âmbar", "Azul", "Lilás", "Magenta")[index], Modifier.weight(1f)); if (accentIndex == index) Icon(Icons.Default.CheckCircle, null, tint = color) } }
                            HorizontalDivider(color = muted.copy(alpha=.2f)); Text("Sobre o IPTV+", fontWeight = FontWeight.SemiBold); Text("Versão 0.3.5 • Buffer configurável, descodificação e leitor em ecrã inteiro • Interface adaptativa para smartphone e Android TV", color = muted); Text("Compatibilidade de reprodução depende do formato do stream e das permissões do fornecedor.", color = muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(item: TvItem, accent: Color, muted: Color, panel: Color, favorites: MutableList<String>, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).clickable { onPlay() }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.LiveTv, null, tint = accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(item.group.ifBlank { "Geral" }, color = muted, fontSize = 11.sp, maxLines = 1)
        }
        IconButton(onClick = { if (item.url in favorites) favorites.remove(item.url) else favorites.add(item.url) }) { Icon(Icons.Default.Favorite, null, tint = if (item.url in favorites) accent else muted) }
        IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, null, tint = accent) }
    }
}

@Composable
private fun MoreMenuRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, panel: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(panel).clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(accent.copy(alpha = .16f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = muted, fontSize = 12.sp) }
        Icon(Icons.Default.ChevronRight, null, tint = muted)
    }
}

@Composable private fun PosterRail(title: String, items: List<TvItem>, accent: Color, isTv: Boolean, onSelect: (TvItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = if (isTv) 21.sp else 18.sp, fontWeight = FontWeight.Bold)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(items.take(24), key = { "poster_${it.kind}_${it.id}_${it.name}" }) { item ->
                Column(Modifier.width(if (isTv) 150.dp else 118.dp).clickable { onSelect(item) }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Surface(shape = RoundedCornerShape(12.dp), color = panel, modifier = Modifier.fillMaxWidth().height(if (isTv) 220.dp else 172.dp)) {
                        Box {
                            if (item.logo.isNotBlank()) AsyncImage(model = item.logo, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(if (item.kind == "series") Icons.Default.Tv else Icons.Default.Movie, null, tint = accent, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.height(8.dp)); Text(item.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
                            }
                            if (item.logo.isNotBlank()) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .68f)).padding(horizontal = 7.dp, vertical = 6.dp)) { Text(item.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2) }
                        }
                    }
                    if (item.group.isNotBlank()) Text(item.group, color = muted, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable private fun MediaLibraryPage(title:String, mediaItems:List<TvItem>, filter:String, onFilter:(String)->Unit, accent:Color, emptyHint:String, onSelect:(TvItem)->Unit) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value=filter, onValueChange=onFilter, label={ Text("Pesquisar $title") }, leadingIcon={ Icon(Icons.Default.Search, null) }, singleLine=true, modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        if (mediaItems.isEmpty()) EmptyPanel("$title ainda não carregados", emptyHint, accent)
        else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(mediaItems, key = { "${it.kind}_${it.id}" }) { item ->
                if (item.name.contains(filter, ignoreCase=true) || item.group.contains(filter, ignoreCase=true)) {
                    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).clickable { onSelect(item) }.padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
                        Icon(if (item.kind == "series") Icons.Default.Tv else Icons.Default.Movie, null, tint=accent)
                        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.name, fontWeight=FontWeight.SemiBold); Text(item.group, color=muted, fontSize=12.sp) }
                        Icon(Icons.Default.PlayArrow, null, tint=accent)
                    }
                }
            }
        }
    }
}

@Composable private fun StatCard(label:String, value:String, modifier:Modifier, accent:Color) { Surface(modifier, color=panel, shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp)) { Text(value, color=accent, fontSize=26.sp, fontWeight=FontWeight.Bold); Text(label, color=muted) } } }
@Composable private fun EmptyPanel(title:String, detail:String, accent:Color) { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Default.LiveTv, null, tint=accent, modifier=Modifier.size(48.dp)); Spacer(Modifier.height(12.dp)); Text(title, fontSize=19.sp, fontWeight=FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(detail, color=muted) } }

private object IptvData {
    private fun fetch(url: String): String {
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "O URL tem de começar por http:// ou https://" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000; conn.readTimeout = 30000; conn.setRequestProperty("User-Agent", "IPTVPlus/0.2 Android")
        return try { if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}"); conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } finally { conn.disconnect() }
    }
    fun loadM3u(url: String): List<TvItem> {
        val text = fetch(url); val out = mutableListOf<TvItem>(); var name = "Canal"; var group = "Geral"; var logo = ""
        text.lineSequence().forEach { raw -> val line = raw.trim(); when {
            line.startsWith("#EXTINF", true) -> { name = line.substringAfterLast(',').trim().ifBlank { "Canal" }; group = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1) ?: "Geral"; logo = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1) ?: "" }
            line.startsWith("http://", true) || line.startsWith("https://", true) -> { out.add(TvItem(name, line, group, logo)); name = "Canal"; group = "Geral"; logo = "" }
        } }
        return out
    }
    private data class XtreamCredentials(val base:String, val user:String, val pass:String)
    private fun credentials(server:String, user:String, pass:String): XtreamCredentials {
        val base = server.trim().trimEnd('/')
        require(base.startsWith("http://", true) || base.startsWith("https://", true)) { "Indica o servidor com http:// ou https://" }
        val u = URLEncoder.encode(user, "UTF-8"); val p = URLEncoder.encode(pass, "UTF-8")
        val auth = fetch("$base/player_api.php?username=$u&password=$p")
        require(!auth.contains("\"auth\":0") && !auth.contains("\"auth\": false")) { "Credenciais Xtream Codes inválidas" }
        return XtreamCredentials(base,u,p)
    }
    private fun openJsonReader(url: String): Pair<HttpURLConnection, JsonReader> {
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "O URL tem de começar por http:// ou https://" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "IPTVPlus/0.3.4 Android")
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (conn.responseCode !in 200..299) { val code = conn.responseCode; conn.disconnect(); error("HTTP $code ao consultar o fornecedor") }
        return conn to JsonReader(conn.inputStream.bufferedReader(Charsets.UTF_8))
    }
    private fun JsonReader.readJsonScalar(): String = when (peek()) {
        JsonToken.NULL -> { nextNull(); "" }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); "" }
    }
    private fun readCategoryMap(url: String): Map<String, String> {
        val (conn, reader) = openJsonReader(url)
        val result = HashMap<String, String>()
        try {
            reader.beginArray()
            while (reader.hasNext()) {
                var id = ""; var name = ""
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "category_id" -> id = reader.readJsonScalar()
                        "category_name" -> name = reader.readJsonScalar()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (id.isNotBlank() && name.isNotBlank()) result[id] = name
            }
            reader.endArray()
        } finally { reader.close(); conn.disconnect() }
        return result
    }
    private fun streamCatalog(url: String, kind: String, base: String, user: String, pass: String, categories: Map<String, String>, limit: Int = Int.MAX_VALUE): List<TvItem> {
        val (conn, reader) = openJsonReader(url)
        val out = ArrayList<TvItem>()
        try {
            reader.beginArray()
            while (reader.hasNext()) {
                var id = ""; var name = ""; var categoryId = ""; var categoryName = ""; var icon = ""; var extension = "mp4"
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "stream_id", "series_id" -> id = reader.readJsonScalar()
                        "name" -> name = reader.readJsonScalar()
                        "category_id" -> categoryId = reader.readJsonScalar()
                        "category_name" -> categoryName = reader.readJsonScalar()
                        "stream_icon", "cover" -> icon = reader.readJsonScalar()
                        "container_extension" -> extension = reader.readJsonScalar().ifBlank { "mp4" }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (id.isNotBlank()) {
                    val group = categoryName.ifBlank { categories[categoryId] ?: when (kind) { "live" -> "TV em direto"; "movie" -> "Filmes"; else -> "Séries" } }
                    val urlItem = when (kind) {
                        "live" -> "$base/live/$user/$pass/$id.ts"
                        "movie" -> "$base/movie/$user/$pass/$id.$extension"
                        else -> ""
                    }
                    out.add(TvItem(name.ifBlank { if (kind == "live") "Canal $id" else if (kind == "movie") "Filme $id" else "Série $id" }, urlItem, group, icon, kind, id, extension))
                }
                if (out.size >= limit) break
            }
            reader.endArray()
        } finally { reader.close(); conn.disconnect() }
        return out
    }
    private fun authenticated(server: String, user: String, pass: String): XtreamCredentials = credentials(server, user, pass)
    fun loadXtreamHomePreview(server: String, user: String, pass: String, limit: Int): Pair<List<TvItem>, List<TvItem>> {
        val c = authenticated(server, user, pass)
        val movieCats = readCategoryMap("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_vod_categories")
        val movieItems = streamCatalog("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_vod_streams", "movie", c.base, c.user, c.pass, movieCats, limit)
        val seriesCats = readCategoryMap("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_series_categories")
        val seriesItems = streamCatalog("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_series", "series", c.base, c.user, c.pass, seriesCats, limit)
        return movieItems to seriesItems
    }
    fun loadXtreamLive(server: String, user: String, pass: String, limit: Int): List<TvItem> {
        val c = authenticated(server, user, pass)
        val cats = readCategoryMap("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_live_categories")
        return streamCatalog("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_live_streams", "live", c.base, c.user, c.pass, cats, limit)
    }
    fun loadVodCatalog(server: String, user: String, pass: String): List<TvItem> {
        val c = authenticated(server, user, pass)
        val cats = readCategoryMap("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_vod_categories")
        return streamCatalog("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_vod_streams", "movie", c.base, c.user, c.pass, cats)
    }
    fun loadSeriesCatalog(server: String, user: String, pass: String): List<TvItem> {
        val c = authenticated(server, user, pass)
        val cats = readCategoryMap("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_series_categories")
        return streamCatalog("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_series", "series", c.base, c.user, c.pass, cats)
    }
    fun loadSeriesEpisodes(server:String,user:String,pass:String,seriesId:String):List<TvItem> {
        val c=credentials(server,user,pass)
        val root=org.json.JSONObject(fetch("${c.base}/player_api.php?username=${c.user}&password=${c.pass}&action=get_series_info&series_id=${URLEncoder.encode(seriesId,"UTF-8")}"))
        val episodesObject=root.optJSONObject("episodes") ?: return emptyList()
        val out=mutableListOf<TvItem>()
        val seasons=episodesObject.keys()
        while(seasons.hasNext()) {
            val season=seasons.next(); val arr=episodesObject.optJSONArray(season) ?: continue
            for(i in 0 until arr.length()) { val ep=arr.optJSONObject(i) ?: continue; val id=ep.optString("id"); if(id.isBlank()) continue
                val ext=ep.optString("container_extension","mp4").ifBlank { "mp4" }; val title=ep.optString("title",ep.optString("episode_num","Episódio ${i+1}"))
                out.add(TvItem(title,"${c.base}/series/${c.user}/${c.pass}/$id.$ext","Temporada $season","","episode",id,ext))
            }
        }
        return out
    }
    fun loadXmltv(url: String): List<Programme> {
        val xml = fetch(url); val parser = XmlPullParserFactory.newInstance().newPullParser(); parser.setInput(xml.reader()); val out = mutableListOf<Programme>()
        var event = parser.eventType; var channel = ""; var title = ""; var start = ""; var stop = ""; var inTitle = false
        while (event != XmlPullParser.END_DOCUMENT && out.size < 4000) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> { channel = parser.getAttributeValue(null,"channel") ?: ""; start = parser.getAttributeValue(null,"start")?.take(14) ?: ""; stop = parser.getAttributeValue(null,"stop")?.take(14) ?: ""; title = "" }
                    "title" -> inTitle = true
                }
                XmlPullParser.TEXT -> if (inTitle && title.isBlank()) title = parser.text.trim()
                XmlPullParser.END_TAG -> when (parser.name) { "title" -> inTitle = false; "programme" -> if (title.isNotBlank()) out.add(Programme(channel, title, formatTime(start), formatTime(stop))) }
            }
            event = parser.next()
        }
        return out
    }
    private fun formatTime(s:String):String = if (s.length >= 14) "${s.substring(6,8)}/${s.substring(4,6)} ${s.substring(8,10)}:${s.substring(10,12)}" else s
}
