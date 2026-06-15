package com.example.beautykino

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// --- Данные ---
val demoMovies = listOf(
    MovieUI("Синтел", "История о девушке по имени Синтел...", "https://upload.wikimedia.org/wikipedia/commons/thumb/8/83/Sintel_poster_v2.jpg/640px-Sintel_poster_v2.jpg", 7.8, "2010", "Фэнтези"),
    MovieUI("Большой заяц Бак", "История о гигантском кролике...", "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Big_buck_bunny_poster_big.jpg/640px-Big_buck_bunny_poster_big.jpg", 8.1, "2008", "Мультфильм"),
    MovieUI("Слезы стали", "Постапокалиптический фильм...", "https://upload.wikimedia.org/wikipedia/commons/thumb/1/18/Tears_of_Steel_poster.jpg/640px-Tears_of_Steel_poster.jpg", 6.9, "2012", "Фантастика")
)

data class MovieUI(val title: String, val description: String, val posterUrl: String, val rating: Double, val year: String, val genre: String)
data class TorrentStream(val name: String, val size: String, val seeders: Int, val url: String)

sealed class Screen {
    object MainCatalog : Screen()
    data class MovieDetails(val movie: MovieUI) : Screen()
    data class Buffering(val torrentName: String) : Screen()
    data class Player(val videoUrl: String) : Screen()
}

// --- Главный класс ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFCC00), background = Color(0xFF121212))) {
                AppNavigator()
            }
        }
    }
}

// --- Навигация ---
@Composable
fun AppNavigator() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.MainCatalog) }
    
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val screen = currentScreen) {
            is Screen.MainCatalog -> CatalogScreen(onMovieSelect = { currentScreen = Screen.MovieDetails(it) })
            is Screen.MovieDetails -> DetailsScreen(movie = screen.movie, onBack = { currentScreen = Screen.MainCatalog }, onStartPlay = { currentScreen = Screen.Buffering(it) })
            is Screen.Buffering -> BufferingScreen(torrentName = screen.torrentName, onReady = { currentScreen = Screen.Player(it) })
            is Screen.Player -> InternalPlayer(videoUrl = screen.videoUrl, onLeave = { currentScreen = Screen.MainCatalog })
        }
    }
}

// --- Экраны ---
@Composable
fun CatalogScreen(onMovieSelect: (MovieUI) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filteredMovies = demoMovies.filter { it.title.contains(query, ignoreCase = true) }
    
    Column(modifier = Modifier.padding(16.dp)) {
        Text("BeautyKino", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = query, 
            onValueChange = { query = it }, 
            placeholder = { Text("Поиск...") }, 
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp), 
            horizontalArrangement = Arrangement.spacedBy(12.dp), 
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredMovies) { movie ->
                Card(modifier = Modifier.clickable { onMovieSelect(movie) }, elevation = CardDefaults.cardElevation(4.dp)) {
                    Column {
                        Box {
                            AsyncImage(model = movie.posterUrl, contentDescription = movie.title, contentScale = ContentScale.Crop, modifier = Modifier.height(230.dp).fillMaxWidth())
                            Box(modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xE6FFCC00)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(movie.rating.toString(), color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(movie.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DetailsScreen(movie: MovieUI, onBack: () -> Unit, onStartPlay: (String) -> Unit) {
    BoxWithConstraints(modifier = Modifier.padding(16.dp)) {
        if (maxWidth > 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AsyncImage(model = movie.posterUrl, contentDescription = null, modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).height(400.dp), contentScale = ContentScale.Crop)
                Column(modifier = Modifier.weight(2f)) { MovieMetaDetails(movie, onBack, onStartPlay) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { AsyncImage(model = movie.posterUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(350.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
                item { MovieMetaDetails(movie, onBack, onStartPlay) }
            }
        }
    }
}

@Composable
fun MovieMetaDetails(movie: MovieUI, onBack: () -> Unit, onStartPlay: (String) -> Unit) {
    val mockStreams = listOf(
        TorrentStream("BDRip 1080p | Дублированный", "4.3 GB", 142, "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4")
    )
    Column {
        Button(onClick = onBack) { Text("← Назад") }
        Spacer(modifier = Modifier.height(16.dp))
        Text(movie.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Text(movie.description, style = MaterialTheme.typography.bodyLarge, color = Color.LightGray)
        Spacer(modifier = Modifier.height(24.dp))
        mockStreams.forEach { stream ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onStartPlay(stream.name) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF222222))) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(stream.name, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Text("Смотреть ▶", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun BufferingScreen(torrentName: String, onReady: (String) -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (progress < 1f) {
            delay(150)
            progress += 0.05f
        }
        onReady("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4")
    }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(progress = progress, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(80.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("Буферизация...", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun InternalPlayer(videoUrl: String, onLeave: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember { 
        ExoPlayer.Builder(context).build().apply { 
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            playWhenReady = true 
        } 
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } }, modifier = Modifier.fillMaxSize())
        Button(onClick = onLeave, modifier = Modifier.padding(16.dp).align(Alignment.TopStart)) { Text("✕ Выйти") }
    }
}
