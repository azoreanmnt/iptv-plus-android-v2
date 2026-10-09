package pt.iptvplus.app

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
private data class TvItem(val name:String, val url:String, val group:String="Geral", val logo:String="")
private data class Programme(val channel:String, val title:String, val start:String, val stop:String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { IPTVPlusApp() } }
}

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
    val channels = remember { mutableStateListOf<TvItem>() }
    val programmes = remember { mutableStateListOf<Programme>() }
    var selected by remember { mutableStateOf<TvItem?>(null) }
    var filter by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Todos") }
    var favoritesOnly by remember { mutableStateOf(false) }
    val favorites = remember { mutableStateListOf<String>() }
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    val accent = accents[accentIndex]

    fun play(item: TvItem) { selected = item; exoPlayer.setMediaItem(MediaItem.fromUri(item.url)); exoPlayer.prepare(); exoPlayer.playWhenReady = true; page = "Leitor" }
    fun launchLoad(block: suspend () -> Unit) {
        loading = true
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            try { block() } catch (e: Exception) { status = "Erro: ${e.message ?: "não foi possível carregar a lista"}" } finally { loading = false }
        } ?: run { loading = false }
    }
    fun loadM3u() = launchLoad {
        val parsed = withContext(Dispatchers.IO) { IptvData.loadM3u(playlistUrl) }
        channels.clear(); channels.addAll(parsed); status = "Lista carregada: ${parsed.size} canais."; page = "TV em direto"
    }
    fun loadXtream() = launchLoad {
        val result = withContext(Dispatchers.IO) { IptvData.loadXtream(server, username, password) }
        channels.clear(); channels.addAll(result); status = "Xtream Codes: ${result.size} canais carregados."; page = "TV em direto"
    }
    fun loadEpg() = launchLoad {
        val result = withContext(Dispatchers.IO) { IptvData.loadXmltv(epgUrl) }
        programmes.clear(); programmes.addAll(result); status = "EPG carregado: ${result.size} programas."; page = "EPG"
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = panel, onSurface = Color.White, onBackground = Color.White)) {
        Scaffold(containerColor = bg, bottomBar = { if (!isTv) NavigationBar(containerColor = panel) {
            listOf("Início" to Icons.Default.Home, "TV em direto" to Icons.Default.LiveTv, "EPG" to Icons.Default.CalendarMonth, "Listas" to Icons.Default.PlaylistPlay, "Definições" to Icons.Default.Settings).forEach { (label, icon) ->
                NavigationBarItem(selected = page == label, onClick = { page = label }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = accent, indicatorColor = accent.copy(alpha=.16f)) )
            }
        } }) { pad ->
            Row(Modifier.fillMaxSize().padding(pad).background(bg)) {
                if (isTv) NavigationRail(containerColor = panel, header = { Text("IPTV+", color = accent, fontWeight = FontWeight.Black, modifier = Modifier.padding(14.dp)) }) {
                    listOf("Início" to Icons.Default.Home, "TV em direto" to Icons.Default.LiveTv, "EPG" to Icons.Default.CalendarMonth, "Listas" to Icons.Default.PlaylistPlay, "Definições" to Icons.Default.Settings).forEach { (label, icon) ->
                        NavigationRailItem(selected = page == label, onClick = { page = label }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 10.sp) }, alwaysShowLabel = true)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (isTv) 24.dp else 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { Text("IPTV+", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(page, fontSize = if (isTv) 28.sp else 24.sp, fontWeight = FontWeight.Bold) }
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = accent)
                    }
                    Spacer(Modifier.height(12.dp))
                    when (page) {
                        "Início" -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = RoundedCornerShape(22.dp), color = panel, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
                                Text("A tua televisão, à tua maneira", fontSize = if (isTv) 26.sp else 21.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp)); Text("Importa uma lista M3U ou liga-te através de Xtream Codes.", color = muted)
                                Spacer(Modifier.height(16.dp)); Button(onClick = { page = "Listas" }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Icon(Icons.Default.AddLink, null); Spacer(Modifier.width(8.dp)); Text("Adicionar lista") }
                            } }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                StatCard("Canais", channels.size.toString(), Modifier.weight(1f), accent)
                                StatCard("EPG", programmes.size.toString(), Modifier.weight(1f), accent)
                            }
                            Text(status, color = muted, fontSize = 13.sp)
                            if (selected != null) Button(onClick = { page = "Leitor" }) { Icon(Icons.Default.PlayArrow, null); Text("Continuar: ${selected!!.name}") }
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
                            if (channels.isEmpty()) EmptyPanel("Ainda não há canais", "Vai a Listas para importar uma lista M3U ou ligar Xtream Codes.", accent)
                            else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(channels.filter { it.name.contains(filter, true) && (!favoritesOnly || it.url in favorites) }) { item ->
                                    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).clickable { play(item) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.LiveTv, null, tint = accent); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold); Text(item.group, color = muted, fontSize = 12.sp) }
                                        IconButton(onClick = { if (item.url in favorites) favorites.remove(item.url) else favorites.add(item.url) }) { Icon(Icons.Default.Favorite, null, tint = if (item.url in favorites) accent else muted) }
                                        IconButton(onClick = { play(item) }) { Icon(Icons.Default.PlayArrow, null, tint = accent) }
                                    }
                                }
                            }
                        }
                        "EPG" -> if (programmes.isEmpty()) EmptyPanel("Guia de programação vazio", "Adiciona o URL XMLTV em Listas para carregar os programas.", accent) else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(programmes) { p -> Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(12.dp)) { Text(p.channel, color = accent, fontWeight = FontWeight.Bold); Text(p.title, fontWeight = FontWeight.SemiBold); Text("${p.start} – ${p.stop}", color = muted, fontSize = 12.sp) } }
                        }
                        "Leitor" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(selected?.name ?: "Leitor integrado", fontWeight = FontWeight.SemiBold)
                            AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } }, modifier = Modifier.fillMaxWidth().height(if (isTv) 360.dp else 220.dp))
                            Text(if (selected == null) "Seleciona um canal em TV em direto ou importa um URL de stream na lista." else selected!!.url, color = muted, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { exoPlayer.play() }) { Icon(Icons.Default.PlayArrow, null); Text("Reproduzir") }; OutlinedButton(onClick = { exoPlayer.pause() }) { Icon(Icons.Default.Pause, null); Text("Pausa") }; OutlinedButton(onClick = { exoPlayer.stop() }) { Icon(Icons.Default.Stop, null); Text("Parar") } }
                        }
                        "Definições" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Cor de destaque", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            accents.forEachIndexed { index, color -> Row(Modifier.fillMaxWidth().clickable { accentIndex = index }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(28.dp).background(color, RoundedCornerShape(50))); Spacer(Modifier.width(12.dp)); Text(listOf("Violeta", "Ciano", "Menta", "Rosa", "Âmbar", "Azul", "Lilás", "Magenta")[index], Modifier.weight(1f)); if (accentIndex == index) Icon(Icons.Default.CheckCircle, null, tint = color) } }
                            HorizontalDivider(color = muted.copy(alpha=.2f)); Text("Sobre o IPTV+", fontWeight = FontWeight.SemiBold); Text("Versão 0.2.0 • Interface adaptativa para smartphone e Android TV", color = muted); Text("Compatibilidade de reprodução depende do formato do stream e das permissões do fornecedor.", color = muted, fontSize = 12.sp)
                        }
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
    fun loadXtream(server: String, user: String, pass: String): List<TvItem> {
        val base = server.trim().trimEnd('/')
        require(base.startsWith("http://", true) || base.startsWith("https://", true)) { "Indica o servidor com http:// ou https://" }
        val u = URLEncoder.encode(user, "UTF-8"); val p = URLEncoder.encode(pass, "UTF-8")
        val auth = fetch("$base/player_api.php?username=$u&password=$p")
        require(!auth.contains("\"auth\":0") && !auth.contains("\"auth\": false")) { "Credenciais Xtream Codes inválidas" }
        val raw = fetch("$base/player_api.php?username=$u&password=$p&action=get_live_streams")
        val arr = org.json.JSONArray(raw); val out = mutableListOf<TvItem>()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val id = o.optString("stream_id"); val nm = o.optString("name", "Canal $id"); val cat = o.optString("category_name", "TV em direto"); if (id.isNotBlank()) out.add(TvItem(nm, "$base/live/$u/$p/$id.ts", cat, o.optString("stream_icon"))) }
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
