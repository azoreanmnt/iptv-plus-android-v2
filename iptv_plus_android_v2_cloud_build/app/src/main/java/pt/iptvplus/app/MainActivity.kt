package pt.iptvplus.app

import android.content.res.Configuration
import android.util.JsonReader
import android.util.JsonToken
import android.net.Uri
import android.os.Bundle
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
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
import androidx.media3.common.MimeTypes
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
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
private data class OnlineSubtitle(val fileId: Long, val language: String, val release: String, val downloads: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IPTVPlusApp() }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val prefs = getSharedPreferences("iptv_plus_settings", MODE_PRIVATE)
        val pipEnabled = prefs.getBoolean("pip_enabled", true)
        val playerActive = prefs.getBoolean("player_active", false)
        if (pipEnabled && playerActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            } catch (_: IllegalArgumentException) {
                // The system may reject PiP if the current window cannot enter it.
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun IPTVPlusApp() {
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val isTv = cfg.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION || cfg.screenWidthDp >= 800
    val prefs = remember { context.getSharedPreferences("iptv_plus_settings", android.content.Context.MODE_PRIVATE) }
    val catalogDb = remember { CatalogDatabase(context.applicationContext) }
    var page by remember { mutableStateOf(prefs.getString("start_page", "Início") ?: "Início") }
    var accentIndex by remember { mutableIntStateOf(prefs.getInt("accent_index", 0).coerceIn(0, accents.lastIndex)) }
    var sourceType by remember { mutableStateOf("Xtream Codes") }
    var server by remember { mutableStateOf(prefs.getString("xtream_server", "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("xtream_username", "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString("xtream_password", "") ?: "") }
    var playlistUrl by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Adiciona uma lista IPTV autorizada para começar.") }
    var loading by remember { mutableStateOf(false) }
    var vodLoaded by remember { mutableStateOf(false) }
    var seriesLoaded by remember { mutableStateOf(false) }
    var homePreviewLoaded by remember { mutableStateOf(false) }
    val maxLiveChannels = Int.MAX_VALUE // sem limite artificial; o JSON é lido em streaming
    val homeMovies = remember { mutableStateListOf<TvItem>() }
    val homeSeries = remember { mutableStateListOf<TvItem>() }
    var channels by remember { mutableStateOf<List<TvItem>>(emptyList()) }
    val programmes = remember { mutableStateListOf<Programme>() }
    var selected by remember { mutableStateOf<TvItem?>(null) }
    var filter by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Todos") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var bufferSetting by remember { mutableStateOf(prefs.getString("buffer_setting", "10") ?: "10") }
    var decoderMode by remember { mutableStateOf(prefs.getString("decoder_mode", "auto") ?: "auto") }
    var pipEnabled by remember { mutableStateOf(prefs.getBoolean("pip_enabled", true)) }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", true)) }
    var startPage by remember { mutableStateOf(prefs.getString("start_page", "Início") ?: "Início") }
    var autoFullscreen by remember { mutableStateOf(prefs.getBoolean("auto_fullscreen", false)) }
    var autoPlayNext by remember { mutableStateOf(prefs.getBoolean("auto_play_next", true)) }
    var openSubtitleSearch by remember { mutableStateOf(false) }
    var subtitleQuery by remember { mutableStateOf("") }
    var subtitleLanguage by remember { mutableStateOf("pt,en") }
    var subtitleLoading by remember { mutableStateOf(false) }
    var subtitleStatus by remember { mutableStateOf("") }
    var subtitleResults by remember { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var openSubtitleApiKey by remember { mutableStateOf(prefs.getString("opensubtitles_api_key", "") ?: "") }
    var openSubtitleUser by remember { mutableStateOf(prefs.getString("opensubtitles_user", "") ?: "") }
    var openSubtitlePassword by remember { mutableStateOf(prefs.getString("opensubtitles_password", "") ?: "") }
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
            .setMediaCodecSelector(MediaCodecSelector.DEFAULT)
        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    val accent = accents[accentIndex]
    LaunchedEffect(bufferSetting, decoderMode, pipEnabled, keepScreenOn, startPage, autoFullscreen, autoPlayNext) {
        prefs.edit()
            .putString("buffer_setting", bufferSetting)
            .putString("decoder_mode", decoderMode)
            .putBoolean("pip_enabled", pipEnabled)
            .putBoolean("keep_screen_on", keepScreenOn)
            .putString("start_page", startPage)
            .putBoolean("auto_fullscreen", autoFullscreen)
            .putBoolean("auto_play_next", autoPlayNext)
            .putBoolean("player_active", page == "Leitor")
            .putInt("accent_index", accentIndex)
            .putString("opensubtitles_api_key", openSubtitleApiKey)
            .putString("opensubtitles_user", openSubtitleUser)
            .putString("opensubtitles_password", openSubtitlePassword)
            .apply()
    }
    LaunchedEffect(page, keepScreenOn) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        if (page == "Leitor" && keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(fullscreen) {
        val activity = context as? Activity
        activity?.window?.decorView?.systemUiVisibility = if (fullscreen) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else 0
    }

    fun play(item: TvItem) { selected = item; exoPlayer.setMediaItem(MediaItem.fromUri(item.url)); exoPlayer.prepare(); exoPlayer.playWhenReady = true; page = "Leitor"; fullscreen = autoFullscreen }
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
        withContext(Dispatchers.IO) { catalogDb.replaceKind("live", parsed) }
        channels = parsed; status = "Lista carregada: ${parsed.size} canais (guardados no telefone)."; page = "TV em direto"
    }
    fun loadXtream() = launchLoad {
        val serverToSave = server.trim()
        val usernameToSave = username.trim()
        val passwordToSave = password
        val found = withContext(Dispatchers.IO) { IptvData.loadXtreamLive(serverToSave, usernameToSave, passwordToSave, maxLiveChannels) }
        // Save the account only after a successful connection; Android keeps these preferences
        // in the app's private storage so they survive closing/restarting the app.
        prefs.edit()
            .putString("xtream_server", serverToSave)
            .putString("xtream_username", usernameToSave)
            .putString("xtream_password", passwordToSave)
            .apply()
        server = serverToSave
        username = usernameToSave
        withContext(Dispatchers.IO) { catalogDb.replaceKind("live", found) }
        channels = found
        movies.clear(); series.clear(); homeMovies.clear(); homeSeries.clear(); vodLoaded = false; seriesLoaded = false; homePreviewLoaded = false
        status = "Xtream Codes: ${channels.size} canais carregados e guardados neste telefone. Conta guardada."
        page = "Início"
    }

    // Restore the last saved catalog from the phone first, then refresh Xtream in the background.
    LaunchedEffect(Unit) {
        try {
            val cached = withContext(Dispatchers.IO) {
                Triple(catalogDb.getItems("live"), catalogDb.getItems("movie"), catalogDb.getItems("series"))
            }
            if (channels.isEmpty() && cached.first.isNotEmpty()) channels = cached.first
            if (movies.isEmpty() && cached.second.isNotEmpty()) { movies.addAll(cached.second); vodLoaded = true }
            if (series.isEmpty() && cached.third.isNotEmpty()) { series.addAll(cached.third); seriesLoaded = true }
            if (cached.first.isNotEmpty()) status = "Catálogo restaurado do telefone: ${cached.first.size} canais."
        } catch (e: Exception) { status = "Não foi possível ler o catálogo local: ${e.message ?: "erro de base de dados"}" }
    }

    // Reconnect automatically on the next launch when a previously saved Xtream account exists.
    LaunchedEffect(Unit) {
        if (server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            status = "A restaurar a lista Xtream Codes guardada…"
            loadXtream()
        }
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
                withContext(Dispatchers.IO) { catalogDb.replaceKind("movie", found) }
                movies.clear(); movies.addAll(found); vodLoaded = true
                status = "Filmes carregados e guardados: ${movies.size}."
            }
            if (page == "Séries" && !seriesLoaded && !loading) launchLoad {
                val found = withContext(Dispatchers.IO) { IptvData.loadSeriesCatalog(server, username, password) }
                withContext(Dispatchers.IO) { catalogDb.replaceKind("series", found) }
                series.clear(); series.addAll(found); seriesLoaded = true
                status = "Séries carregadas e guardadas: ${series.size}."
            }
        }
    }
    fun loadEpg() = launchLoad {
        val result = withContext(Dispatchers.IO) { IptvData.loadXmltv(epgUrl) }
        programmes.clear(); programmes.addAll(result); status = "EPG carregado: ${result.size} programas."; page = "EPG"
    }

    fun searchOnlineSubtitles() {
        if (openSubtitleApiKey.isBlank()) { subtitleStatus = "Introduz a API key do OpenSubtitles em Definições."; return }
        if (subtitleQuery.isBlank()) { subtitleStatus = "Indica o título do filme ou episódio."; return }
        subtitleLoading = true; subtitleStatus = "A pesquisar legendas…"; subtitleResults = emptyList()
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            try {
                val result = withContext(Dispatchers.IO) { SubtitleService.search(openSubtitleApiKey.trim(), subtitleQuery.trim(), subtitleLanguage) }
                subtitleResults = result
                subtitleStatus = if (result.isEmpty()) "Não foram encontradas legendas. Experimenta outro título ou idioma." else "${result.size} resultados encontrados."
            } catch (e: Exception) { subtitleStatus = "Falha na pesquisa: ${e.message ?: "verifica a API key e a ligação"}" }
            finally { subtitleLoading = false }
        }
    }
    fun downloadOnlineSubtitle(subtitle: OnlineSubtitle) {
        if (openSubtitleApiKey.isBlank() || openSubtitleUser.isBlank() || openSubtitlePassword.isBlank()) {
            subtitleStatus = "Para descarregar, configura API key, utilizador e palavra-passe do OpenSubtitles em Definições."; return
        }
        subtitleLoading = true; subtitleStatus = "A descarregar legenda…"
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            try {
                val file = withContext(Dispatchers.IO) { SubtitleService.download(context.cacheDir, openSubtitleApiKey.trim(), openSubtitleUser.trim(), openSubtitlePassword, subtitle.fileId) }
                val item = selected
                if (item == null) error("Não há vídeo selecionado")
                val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(subtitle.language)
                    .setLabel("OpenSubtitles · ${subtitle.language}")
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build()
                exoPlayer.setMediaItem(MediaItem.Builder().setUri(item.url).setSubtitleConfigurations(listOf(subtitleConfig)).build())
                exoPlayer.prepare(); exoPlayer.playWhenReady = true
                subtitleStatus = "Legenda aplicada. Se não aparecer, abre o seletor de faixas do player."
            } catch (e: Exception) { subtitleStatus = "Não foi possível descarregar/aplicar: ${e.message ?: "erro desconhecido"}" }
            finally { subtitleLoading = false }
        }
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
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { Button(onClick = { exoPlayer.play() }) { Icon(Icons.Default.PlayArrow, null); Text("Reproduzir") }; OutlinedButton(onClick = { exoPlayer.pause() }) { Icon(Icons.Default.Pause, null); Text("Pausa") }; OutlinedButton(onClick = { fullscreen = true }) { Icon(Icons.Default.Fullscreen, null); Text("Ecrã inteiro") } }
                                OutlinedButton(onClick = { subtitleQuery = selected?.name ?: ""; subtitleResults = emptyList(); subtitleStatus = ""; openSubtitleSearch = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Subtitles, null); Spacer(Modifier.width(8.dp)); Text("Procurar legendas online") }
                            }
                        }
                        "Definições" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Reprodução", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Tamanho do buffer", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Um buffer maior pode reduzir interrupções, mas aumenta o tempo inicial de carregamento.", color = muted, fontSize = 12.sp)
                            listOf("5" to "5 s · ligação rápida", "10" to "10 s · equilibrado", "20" to "20 s · ligação instável", "30" to "30 s · buffer elevado").forEach { (value, label) ->
                                SettingChoice(label, bufferSetting == value, accent) { bufferSetting = value }
                            }
                            HorizontalDivider(color = muted.copy(alpha=.2f))
                            Text("Descodificação", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            listOf("auto" to "Automática (recomendada)", "software" to "Preferência de software (experimental)").forEach { (value, label) ->
                                SettingChoice(label, decoderMode == value, accent) { decoderMode = value }
                            }
                            Text("A seleção por software depende do suporte do dispositivo e do formato. Se não funcionar, usa Automática.", color = muted, fontSize = 12.sp)
                            HorizontalDivider(color = muted.copy(alpha=.2f))
                            Text("Comportamento do player", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            SettingToggle("Minimizar ao sair (PiP)", "Continua a ver o vídeo numa janela flutuante quando o Android permite.", pipEnabled, accent) { pipEnabled = it }
                            SettingToggle("Manter ecrã ligado durante a reprodução", "Evita que o ecrã se desligue enquanto o player está aberto.", keepScreenOn, accent) { keepScreenOn = it }
                            SettingToggle("Abrir player em ecrã inteiro", "Ativa o ecrã inteiro ao iniciar um canal ou vídeo.", autoFullscreen, accent) { autoFullscreen = it }
                            SettingToggle("Ativar reprodução automática seguinte", "Preferência guardada; a passagem automática entre episódios será ligada numa etapa posterior.", autoPlayNext, accent) { autoPlayNext = it }
                            HorizontalDivider(color = muted.copy(alpha=.2f))
                            Text("Ao iniciar a aplicação", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            listOf("Início" to "Início", "TV em direto" to "TV em direto", "Filmes" to "Filmes", "Séries" to "Séries").forEach { (value, label) ->
                                SettingChoice(label, startPage == value, accent) { startPage = value }
                            }
                            Text("Esta escolha abre o separador selecionado quando iniciares a aplicação.", color = muted, fontSize = 12.sp)
                            HorizontalDivider(color = muted.copy(alpha=.2f))
                            Text("Aparência", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            accents.forEachIndexed { index, color ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(panel).clickable { accentIndex = index }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(28.dp).background(color, RoundedCornerShape(50)))
                                    Spacer(Modifier.width(12.dp))
                                    Text(listOf("Violeta", "Ciano", "Menta", "Rosa", "Âmbar", "Azul", "Lilás", "Magenta")[index], Modifier.weight(1f))
                                    if (accentIndex == index) Icon(Icons.Default.CheckCircle, null, tint = color)
                                }
                            }
                            HorizontalDivider(color = muted.copy(alpha=.2f))
                            Text("Legendas online · OpenSubtitles", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Cria uma API key no teu perfil OpenSubtitles. Para descarregar ficheiros, a API também exige autenticação da conta. As credenciais ficam guardadas localmente neste dispositivo.", color = muted, fontSize = 12.sp)
                            OutlinedTextField(value = openSubtitleApiKey, onValueChange = { openSubtitleApiKey = it }, label = { Text("API key OpenSubtitles") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(value = openSubtitleUser, onValueChange = { openSubtitleUser = it }, label = { Text("Utilizador OpenSubtitles") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(value = openSubtitlePassword, onValueChange = { openSubtitlePassword = it }, label = { Text("Palavra-passe OpenSubtitles") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Text("Para uma futura publicação pública, é preferível transferir a autenticação para um serviço intermediário protegido em vez de guardar credenciais diretamente no dispositivo.", color = muted, fontSize = 12.sp)
                            HorizontalDivider(color = muted.copy(alpha=.2f))
                            Text("Sobre o IPTV+", fontWeight = FontWeight.SemiBold)
                            Text("Versão 0.3.8 · Pesquisa e transferência de legendas online, além das preferências do player.", color = muted)
                            Text("O funcionamento de PiP depende do Android e do dispositivo. A opção de descodificação por software é experimental.", color = muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    if (openSubtitleSearch) {
        AlertDialog(
            onDismissRequest = { if (!subtitleLoading) openSubtitleSearch = false },
            title = { Text("Legendas online") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = subtitleQuery, onValueChange = { subtitleQuery = it }, label = { Text("Filme ou episódio") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("Idioma", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = subtitleLanguage == "pt", onClick = { subtitleLanguage = "pt" }, label = { Text("Português") })
                        FilterChip(selected = subtitleLanguage == "en", onClick = { subtitleLanguage = "en" }, label = { Text("English") })
                        FilterChip(selected = subtitleLanguage == "pt,en", onClick = { subtitleLanguage = "pt,en" }, label = { Text("Ambos") })
                    }
                    Button(onClick = { searchOnlineSubtitles() }, enabled = !subtitleLoading && subtitleQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (subtitleLoading) "A processar…" else "Pesquisar") }
                    if (subtitleStatus.isNotBlank()) Text(subtitleStatus, color = muted, fontSize = 12.sp)
                    subtitleResults.forEach { result ->
                        Surface(color = panel, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${result.language.uppercase()} · ${result.release.ifBlank { "Lançamento não indicado" }}", fontWeight = FontWeight.SemiBold)
                                    Text("${result.downloads} downloads", color = muted, fontSize = 12.sp)
                                }
                                Button(onClick = { downloadOnlineSubtitle(result) }, enabled = !subtitleLoading) { Text("Usar") }
                            }
                        }
                    }
                    if (openSubtitleApiKey.isBlank()) Text("Configura primeiro a API key em Mais → Definições.", color = muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { openSubtitleSearch = false }) { Text("Fechar") } }
        )
    }
}

@Composable
private fun SettingChoice(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(panel).clickable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = accent))
    }
}

@Composable
private fun SettingToggle(title: String, detail: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = accent))
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
    var selectedCategory by remember(title) { mutableStateOf("Todas") }
    val categoryCounts = remember(mediaItems) {
        mediaItems.groupingBy { it.group.ifBlank { "Geral" } }.eachCount().toSortedMap()
    }
    val visibleItems = remember(mediaItems, filter, selectedCategory) {
        mediaItems.filter { item ->
            (selectedCategory == "Todas" || item.group.ifBlank { "Geral" } == selectedCategory) &&
                (item.name.contains(filter, ignoreCase=true) || item.group.contains(filter, ignoreCase=true))
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value=filter, onValueChange=onFilter, label={ Text("Pesquisar $title") }, leadingIcon={ Icon(Icons.Default.Search, null) }, singleLine=true, modifier=Modifier.fillMaxWidth())
        if (mediaItems.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical=10.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=selectedCategory=="Todas", onClick={ selectedCategory="Todas" }, label={ Text("Todas (${mediaItems.size})") })
                categoryCounts.forEach { (groupName, count) ->
                    FilterChip(selected=selectedCategory==groupName, onClick={ selectedCategory=groupName }, label={ Text("$groupName ($count)", maxLines=1) })
                }
            }
        }
        if (mediaItems.isEmpty()) EmptyPanel("$title ainda não carregados", emptyHint, accent)
        else if (visibleItems.isEmpty()) EmptyPanel("Sem resultados", "Altera a pesquisa ou a categoria.", accent)
        else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(visibleItems, key = { "${it.kind}_${it.id}" }) { item ->
                Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).clickable { onSelect(item) }.padding(10.dp), verticalAlignment=Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(8.dp), color = bg, modifier = Modifier.width(88.dp).height(124.dp)) {
                        Box {
                            if (item.logo.isNotBlank()) AsyncImage(model = item.logo, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(if (item.kind == "series") Icons.Default.Tv else Icons.Default.Movie, null, tint = accent, modifier = Modifier.size(30.dp)) }
                        }
                    }
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(item.name, fontWeight=FontWeight.SemiBold, maxLines=2); Text(item.group, color=muted, fontSize=12.sp, maxLines=1) }
                    Icon(Icons.Default.PlayArrow, null, tint=accent)
                }
            }
        }
    }
}

@Composable private fun StatCard(label:String, value:String, modifier:Modifier, accent:Color) { Surface(modifier, color=panel, shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp)) { Text(value, color=accent, fontSize=26.sp, fontWeight=FontWeight.Bold); Text(label, color=muted) } } }
@Composable private fun EmptyPanel(title:String, detail:String, accent:Color) { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Default.LiveTv, null, tint=accent, modifier=Modifier.size(48.dp)); Spacer(Modifier.height(12.dp)); Text(title, fontSize=19.sp, fontWeight=FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(detail, color=muted) } }


private object SubtitleService {
    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private const val USER_AGENT = "IPTVPlus v0.3.8"
    private fun request(url: String, method: String, apiKey: String, token: String? = null, body: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 15000; conn.readTimeout = 30000
        conn.setRequestProperty("Api-Key", apiKey); conn.setRequestProperty("User-Agent", USER_AGENT); conn.setRequestProperty("Accept", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) error("OpenSubtitles HTTP $code: ${runCatching { JSONObject(text).optString("message") }.getOrDefault(text.take(180))}")
            text
        } finally { conn.disconnect() }
    }
    fun search(apiKey: String, query: String, languages: String): List<OnlineSubtitle> {
        val url = "$BASE/subtitles?query=${URLEncoder.encode(query, "UTF-8")}&languages=${URLEncoder.encode(languages, "UTF-8")}&order_by=download_count&order_direction=desc"
        val json = JSONObject(request(url, "GET", apiKey))
        val data = json.optJSONArray("data") ?: JSONArray()
        val out = mutableListOf<OnlineSubtitle>()
        for (i in 0 until minOf(data.length(), 20)) {
            val attrs = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val files = attrs.optJSONArray("files") ?: JSONArray()
            if (files.length() == 0) continue
            val fileId = files.optJSONObject(0)?.optLong("file_id", -1L) ?: -1L
            if (fileId <= 0L) continue
            out.add(OnlineSubtitle(fileId, attrs.optString("language", "und"), attrs.optString("release", ""), attrs.optInt("download_count", 0)))
        }
        return out
    }
    fun download(cacheDir: File, apiKey: String, username: String, password: String, fileId: Long): File {
        val loginBody = JSONObject().put("username", username).put("password", password).toString()
        val login = JSONObject(request("$BASE/login", "POST", apiKey, body = loginBody))
        val token = login.optString("token")
        if (token.isBlank()) error("Autenticação OpenSubtitles não devolveu token")
        val body = JSONObject().put("file_id", fileId).put("sub_format", "srt").toString()
        val response = JSONObject(request("$BASE/download", "POST", apiKey, token, body))
        val link = response.optString("link")
        if (!link.startsWith("https://")) error("OpenSubtitles não devolveu uma ligação segura para a legenda")
        val conn = URL(link).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        return try {
            if (conn.responseCode !in 200..299) error("Falha ao descarregar a legenda (HTTP ${conn.responseCode})")
            val file = File(cacheDir, "online_subtitle_${System.currentTimeMillis()}.srt")
            conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            if (file.length() == 0L) { file.delete(); error("O ficheiro de legenda está vazio") }
            file
        } finally { conn.disconnect() }
    }
}

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
                        "stream_icon", "cover", "cover_big", "movie_image", "movie_cover" -> {
                            val candidate = reader.readJsonScalar()
                            if (candidate.isNotBlank() && (icon.isBlank() || candidate.contains("http", true))) icon = candidate
                        }
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
                // Drain the rest of the JSON array after reaching the result limit.
                // Breaking without consuming it makes reader.endArray() throw and
                // the Xtream loading appears to stop early.
                if (out.size >= limit) {
                    while (reader.hasNext()) reader.skipValue()
                    break
                }
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
