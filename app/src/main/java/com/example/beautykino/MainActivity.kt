package com.example.beautykino

import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.URLEncoder

// --- 1. НАСТРОЙКИ СЕРВЕРА JACKETT ---
const val JACKETT_URL = "http://192.168.1.100:9117/" 
const val JACKETT_KEY = "YOUR_API_KEY" // Вставьте сюда ваш ключ английскими буквами/цифрами

// --- 2. СЕТЕВЫЕ МОДЕЛИ RETROFIT ---
interface JackettApi {
    @GET("api/v2.0/indexers/all/results")
    suspend fun searchTorrents(
        @Query("apikey") apiKey: String = JACKETT_KEY,
        @Query("Query") searchQuery: String
    ): JackettResponse
}

data class JackettResponse(val Results: List<JackettResult>?)
data class JackettResult(val Title: String?, val Size: Long?, val Seeders: Int?, val MagnetUri: String?, val Tracker: String?)

// Безопасная инициализация Retrofit
object NetworkManager {
    private val retrofit = Retrofit.Builder()
        .baseUrl(JACKETT_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    val api: JackettApi = retrofit.create(JackettApi::class.java)
}

// --- 3. UI МОДЕЛИ ---
data class MovieUI(val title: String, val description: String, val posterUrl: String, val rating: Int, val tracker: String, val magnetUrl: String?)

sealed class Screen {
    object MainCatalog : Screen()
    data class MovieDetails(val movie: MovieUI) : Screen()
    data class Buffering(val torrentName: String) : Screen()
    data class Player(val videoUrl: String) : Screen()
}

// --- 4. ГЛАВНЫЙ КЛАСС ПРИЛОЖЕНИЯ ---
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

// --- 5. ЭКРАН КАТАЛОГА И ПОИСКА ---
@Composable
fun CatalogScreen(onMovieSelect: (MovieUI) -> Unit) {
    var query by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<MovieUI>>(emptyList()) }
    
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val defaultMovies = remember {
        listOf(
            MovieUI("Матрица", "Нажмите на поиск выше", "https://dummyimage.com/600x900/222/fc0&text=Matrix", 9, "Rutor", null),
            MovieUI("Интерстеллар", "Чтобы найти фильмы", "https://dummyimage.com/600x900/222/fc0&text=Space", 8, "Kinozal", null)
        )
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("BeautyKino", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = query, 
            onValueChange = { query = it }, 
            placeholder = { Text("Поиск фильмов...") }, 
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    if (query.isNotBlank()) {
                        isLoading = true
                        coroutineScope.launch {
                            try {
                                val response = NetworkManager.api.searchTorrents(searchQuery = query)
                                val results = response.Results ?: emptyList()
                                
                                searchResults = results
                                    .filter { it.Title != null }
                                    .sortedByDescending { it.Seeders ?: 0 }
                                    .map { jackettItem ->
                                        val trackerName = jackettItem.Tracker ?: "Unknown"
                                        val encodedText = try {
                                            URLEncoder.encode(trackerName, "UTF-8")
                                        } catch (e: Exception) { "Tracker" }
                                        
                                        val sizeBytes = jackettItem.Size ?: 0L
                                        val sizeGb = String.format("%.1f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
                                        
                                        MovieUI(
                                            title = jackettItem.Title ?: "Без названия",
                                            description = "Размер: $sizeGb | Трекер: $trackerName",
                                            posterUrl = "https://dummyimage.com/600x900/1e1e1e/FFCC00&text=$encodedText",
                                            rating = jackettItem.Seeders ?: 0,
                                            tracker = trackerName,
                                            magnetUrl = jackettItem.MagnetUri
                                        )
                                    }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ошибка сети", Toast.LENGTH_SHORT).show()
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            val displayList = if (searchResults.isEmpty() && query.isBlank()) defaultMovies else searchResults
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp), 
                horizontalArrangement = Arrangement.spacedBy(12.dp), 
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(displayList) { movie ->
                    Card(modifier = Modifier.clickable { onMovieSelect(movie) }, elevation = CardDefaults.cardElevation(4.dp)) {
                        Column {
                            Box {
                                AsyncImage(model = movie.posterUrl, contentDescription = movie.title, contentScale = ContentScale.Crop, modifier = Modifier.height(230.dp).fillMaxWidth())
                                Box(modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xE6FFCC00)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text("Сиды: ${movie.rating}", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text(movie.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(movie.description, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

// --- 6. ЭКРАН ОПИСАНИЯ ---
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
    Column {
        Button(onClick = onBack) { Text("← Назад") }
        Spacer(modifier = Modifier.height(16.dp))
        Text(movie.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Text(movie.description, style = MaterialTheme.typography.bodyLarge, color = Color.LightGray)
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { 
            onStartPlay(movie.title) 
        }, colors = CardDefaults.cardColors(containerColor = Color(0xFF222222))) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Запустить онлайн просмотр", fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Через локальный торрент-стрим", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Text("▶ PLAY", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// --- 7. ЭКРАН ПЛЕЕРА ---
@Composable
fun BufferingScreen(torrentName: String, onReady: (String) -> Unit) {
    var progress by remember { mutableStateOf(0f) }
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
        Text("Подключение к пирам...", style = MaterialTheme.typography.titleMedium)
        Text(torrentName, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
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
