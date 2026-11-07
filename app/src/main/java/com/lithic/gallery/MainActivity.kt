package com.lithic.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.lithic.gallery.ui.GalleryScreen
import com.lithic.gallery.ui.GalleryViewModel
import com.lithic.gallery.ui.ModelsScreen
import com.lithic.gallery.ui.SettingsScreen
import com.lithic.gallery.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    private val vm: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark always (a gallery): light status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            GalleryTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(vm)
                }
            }
        }
    }
}

@Composable
private fun GalleryTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(context) else darkColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Whether the app may read all photos and videos (not only a hand-picked few). */
enum class Access { FULL, PARTIAL, NONE }

private fun mediaAccess(context: android.content.Context): Access {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(Manifest.permission.READ_MEDIA_VIDEO) -> Access.FULL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        else -> Access.NONE
    }
}

private val mediaPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.POST_NOTIFICATIONS,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private sealed interface Route {
    data object Gallery : Route
    data class Viewer(val index: Int) : Route
    data object Settings : Route
    data object Models : Route
}

@Composable
private fun AppRoot(vm: GalleryViewModel) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(mediaAccess(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = mediaAccess(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = mediaAccess(context)
        vm.onResume()
    }
    if (access != Access.FULL) {
        PermissionScreen(access, onRequest = { launcher.launch(mediaPermissions) }, onOpenSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        })
        return
    }
    LaunchedEffect(Unit) { vm.start() }

    var routeName by rememberSaveable { mutableStateOf("gallery") }
    var viewerIndex by rememberSaveable { mutableIntStateOf(0) }
    val route: Route = when (routeName) {
        "viewer" -> if (vm.viewerList.isEmpty()) Route.Gallery else Route.Viewer(viewerIndex)
        "settings" -> Route.Settings
        "models" -> Route.Models
        else -> Route.Gallery
    }
    when (route) {
        Route.Gallery -> GalleryScreen(
            vm,
            onOpen = { i -> viewerIndex = i; routeName = "viewer" },
            onOpenSearchHit = { i -> viewerIndex = i; routeName = "viewer" },
            onSettings = { routeName = "settings" },
            onModels = { routeName = "models" },
        )
        is Route.Viewer -> ViewerScreen(vm, route.index, onBack = { routeName = "gallery" })
        Route.Settings -> SettingsScreen(vm, onBack = { routeName = "gallery" }, onModels = { routeName = "models" })
        Route.Models -> ModelsScreen(vm, onBack = { routeName = "gallery" })
    }
}

@Composable
private fun PermissionScreen(access: Access, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Lithic Gallery", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            if (access == Access.PARTIAL)
                "You allowed only some photos. To show your whole library, allow access to all photos and videos."
            else
                "To show your photos and videos, allow access to them. Everything stays on this phone.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text(if (access == Access.PARTIAL) "Allow all photos" else "Allow access") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
    }
}
