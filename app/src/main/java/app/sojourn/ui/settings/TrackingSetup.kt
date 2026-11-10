package app.sojourn.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.sojourn.data.TrackingMode
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.components.ShapeIcon

enum class LocationAccess { NONE, WHILE_IN_USE, ALWAYS }

fun Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.locationAccess(): LocationAccess = when {
    !granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.NONE
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> LocationAccess.WHILE_IN_USE
    else -> LocationAccess.ALWAYS
}

/** Location access, refreshed whenever the screen comes back from the permission settings. */
@Composable
fun rememberLocationAccess(): Pair<LocationAccess, () -> Unit> {
    val context = LocalContext.current
    var access by remember { mutableStateOf(context.locationAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = context.locationAccess() }
    return access to { access = context.locationAccess() }
}

/**
 * The three ways to track, from most private to most automatic, and the permissions the
 * chosen one still needs. Asks for background location only after the foreground grant, as
 * Android requires.
 */
@Composable
fun TrackingSetup(mode: TrackingMode, onMode: (TrackingMode) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (access, refresh) = rememberLocationAccess()
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh()
        if (ok && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    val options = listOf(
        Triple(TrackingMode.LOCATION, "Automatic", "A coarse location every few hours, never GPS. Falls back to the mobile network."),
        Triple(TrackingMode.NETWORK, "Mobile network only", "Reads which country your SIM is connected in. No location permission at all."),
        Triple(TrackingMode.OFF, "Manual", "Nothing runs in the background. Add days yourself or from photos."),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        options.forEachIndexed { index, (value, title, body) ->
            SegmentedListItem(
                colors = segmentedColors(),
                verticalAlignment = Alignment.CenterVertically,
                selected = mode == value,
                onClick = {
                    onMode(value)
                    if (value == TrackingMode.LOCATION && access == LocationAccess.NONE) foreground.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                },
                shapes = ListItemDefaults.segmentedShapes(index, options.size),
                leadingContent = {
                    ShapeIcon(
                        when (value) {
                            TrackingMode.LOCATION -> Icons.Rounded.MyLocation
                            TrackingMode.NETWORK -> Icons.Rounded.CellTower
                            TrackingMode.OFF -> Icons.Rounded.PauseCircle
                        },
                    )
                },
                trailingContent = { RadioButton(selected = mode == value, onClick = null) },
                supportingContent = { Text(body) },
            ) { Text(title, style = MaterialTheme.typography.titleMedium) }
        }
    }

    if (mode == TrackingMode.LOCATION && access != LocationAccess.ALWAYS) {
        Spacer(Modifier.height(12.dp))
        val (text, action) = when (access) {
            LocationAccess.NONE -> "Sojourn needs approximate location to know the country." to "Allow location"
            else -> "Allow location “all the time” so checks also run when the app is closed. Until then, Sojourn checks when you open it and uses the mobile network in between." to "Allow all the time"
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(start = 12.dp))
                }
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        when {
                            access == LocationAccess.NONE -> foreground.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(action) }
                if (access == LocationAccess.WHILE_IN_USE) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Open app settings") }
                }
            }
        }
    } else if (mode == TrackingMode.LOCATION) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                "Location allowed all the time",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
