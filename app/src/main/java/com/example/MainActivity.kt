package com.example

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.library.LibraryScreen
import com.example.ui.library.LibraryViewModel
import com.example.ui.reader.ReaderScreen
import com.example.ui.reader.ReaderViewModel
import com.example.ui.theme.FolioTheme
import java.nio.charset.StandardCharsets

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_READER = "reader/{encodedUri}"

internal object ReaderRouteCodec {
    fun encode(uriString: String): String {
        val bytes = uriString.toByteArray(StandardCharsets.UTF_8)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decode(encoded: String): String {
        if (encoded.isBlank()) return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            String(bytes, StandardCharsets.UTF_8)
        }.getOrDefault("")
    }
}

class MainActivity : ComponentActivity() {

    private var externalDocumentUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        handleIncomingIntent(intent)

        setContent {
            FolioTheme {
                FolioAppRoot(
                    externalDocumentUri = externalDocumentUri,
                    onConsumeExternalUri = { externalDocumentUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                externalDocumentUri = uri
            }
        }
    }
}

@Composable
fun FolioAppRoot(
    externalDocumentUri: Uri?,
    onConsumeExternalUri: () -> Unit
) {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = viewModel()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

    val openDocumentInReader: (Uri) -> Unit = { uri ->
        libraryViewModel.takePersistablePermission(uri)
        val encoded = ReaderRouteCodec.encode(uri.toString())
        navController.navigate("reader/$encoded") {
            popUpTo(ROUTE_LIBRARY) { inclusive = false }
            launchSingleTop = true
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { openDocumentInReader(it) }
    }

    // Launch with */* so Android SAF never grays out .fb2, .mobi, .doc, .docx, .cbz, or .md files.
    val launchUniversalFilePicker: () -> Unit = {
        documentPickerLauncher.launch(arrayOf("*/*"))
    }

    LaunchedEffect(externalDocumentUri) {
        externalDocumentUri?.let { uri ->
            openDocumentInReader(uri)
            onConsumeExternalUri()
        }
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_LIBRARY,
        enterTransition = { fadeIn(animationSpec = tween(200)) },
        exitTransition = { fadeOut(animationSpec = tween(160)) }
    ) {
        composable(route = ROUTE_LIBRARY) {
            LibraryScreen(
                uiState = libraryUiState,
                onOpenPdfPicker = launchUniversalFilePicker,
                onOpenDocument = { doc ->
                    openDocumentInReader(Uri.parse(doc.uriString))
                },
                onOpenSampleGuide = {
                    libraryViewModel.openSampleGuide { uri ->
                        openDocumentInReader(uri)
                    }
                },
                onSearchQueryChange = libraryViewModel::onSearchQueryChange,
                onOpenSearch = libraryViewModel::openSearch,
                onCloseSearch = libraryViewModel::closeSearch,
                onToggleGridView = libraryViewModel::toggleGridView,
                onFilterSelected = libraryViewModel::onFilterSelected,
                onClearFilters = libraryViewModel::clearFilters,
                onTogglePin = libraryViewModel::togglePin,
                onRemoveDocument = libraryViewModel::removeDocument,
                onUndoRemove = libraryViewModel::undoLastRemove,
                onRequestClearHistory = libraryViewModel::requestClearHistory,
                onDismissClearHistory = libraryViewModel::dismissClearHistory,
                onConfirmClearHistory = libraryViewModel::confirmClearHistory
            )
        }

        composable(
            route = ROUTE_READER,
            arguments = listOf(
                navArgument("encodedUri") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("encodedUri").orEmpty()
            val decodedUri = ReaderRouteCodec.decode(encoded)
            val readerViewModel: ReaderViewModel = viewModel()
            val readerUiState by readerViewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(decodedUri) {
                if (decodedUri.isNotBlank()) {
                    readerViewModel.openDocument(decodedUri)
                }
            }

            ReaderScreen(
                uiState = readerUiState,
                viewModel = readerViewModel,
                onBack = { navController.popBackStack() },
                onOpenAnotherFile = launchUniversalFilePicker
            )
        }
    }
}
