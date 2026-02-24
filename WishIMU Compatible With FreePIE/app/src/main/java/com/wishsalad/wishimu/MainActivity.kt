package com.wishsalad.wishimu

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import android.hardware.SensorManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.net.NetworkInterface
import java.util.Locale

private fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "—"
        for (iface in interfaces.asSequence()) {
            if (iface.isLoopback || !iface.isUp) continue
            for (addr in iface.inetAddresses.asSequence()) {
                val host = addr.hostAddress ?: continue
                if (!addr.isLoopbackAddress && !host.contains(':')) return host
            }
        }
    } catch (_: Exception) {}
    return "—"
}

private data class SampleRateOption(val sensorDelayId: Int, val label: String)

private val SAMPLE_RATES = listOf(
    SampleRateOption(SensorManager.SENSOR_DELAY_NORMAL,   "Slowest – 5 FPS"),
    SampleRateOption(SensorManager.SENSOR_DELAY_UI,       "Average – 16 FPS"),
    SampleRateOption(SensorManager.SENSOR_DELAY_GAME,     "Fast – 50 FPS"),
    SampleRateOption(SensorManager.SENSOR_DELAY_FASTEST,  "Fastest – no delay")
)

class MainActivity : ComponentActivity() {

    companion object {
        /** True while volume buttons should route to buttonState instead of system volume. */
        @Volatile var volumeButtonsActive = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getPreferences(MODE_PRIVATE)
        setContent {
            WishImuTheme {
                WishImuApp(
                    prefs = prefs,
                    onStart = { ip, port, index, sendOrientation, sendRaw, sampleRateId, volButtons ->
                        startForegroundService(
                            Intent(this, UdpSenderService::class.java).apply {
                                putExtra("toIp", ip)
                                putExtra("port", port)
                                putExtra("deviceIndex", index.toByte())
                                putExtra("sendOrientation", sendOrientation)
                                putExtra("sendRaw", sendRaw)
                                putExtra("sampleRate", sampleRateId)
                                putExtra("volumeButtons", volButtons)
                            }
                        )
                    },
                    onStop = { stopService(Intent(this, UdpSenderService::class.java)) }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeButtonsActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    UdpSenderService.buttonState =
                        (UdpSenderService.buttonState.toInt() or 0x01).toByte()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    UdpSenderService.buttonState =
                        (UdpSenderService.buttonState.toInt() or 0x02).toByte()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeButtonsActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    UdpSenderService.buttonState =
                        (UdpSenderService.buttonState.toInt() and 0x01.inv()).toByte()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    UdpSenderService.buttonState =
                        (UdpSenderService.buttonState.toInt() and 0x02.inv()).toByte()
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}

@Composable
fun WishImuTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (darkTheme) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishImuApp(
    prefs: SharedPreferences,
    onStart: (ip: String, port: Int, index: Int, sendOrientation: Boolean, sendRaw: Boolean, sampleRateId: Int, volumeButtons: Boolean) -> Unit,
    onStop: () -> Unit
) {
    val savedSampleRateId = prefs.getInt("sample_rate", SensorManager.SENSOR_DELAY_FASTEST)
    val initialSampleRateIdx = SAMPLE_RATES.indexOfFirst { it.sensorDelayId == savedSampleRateId }.coerceAtLeast(0)

    val localIp = remember { getLocalIpAddress() }
    var ip by remember { mutableStateOf(prefs.getString("ip", "192.168.1.1")!!) }
    var port by remember { mutableStateOf(prefs.getString("port", "5555")!!) }
    var selectedIndex by remember { mutableIntStateOf(prefs.getInt("index", 0)) }
    var sendOrientation by remember { mutableStateOf(prefs.getBoolean("send_orientation", true)) }
    var sendRaw by remember { mutableStateOf(prefs.getBoolean("send_raw", true)) }
    var mouseButtons by remember { mutableStateOf(prefs.getBoolean("mouse_buttons", false)) }
    var volumeButtons by remember { mutableStateOf(prefs.getBoolean("volume_buttons", false)) }
    var selectedSampleRateIdx by remember { mutableIntStateOf(initialSampleRateIdx) }
    var isRunning by remember { mutableStateOf(UdpSenderService.started) }
    var showDebug by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var ipError  by remember { mutableStateOf<String?>(null) }
    var errorStr by remember { mutableStateOf<String?>(null) }

    var accStr by remember { mutableStateOf("") }
    var gyrStr by remember { mutableStateOf("") }
    var magStr by remember { mutableStateOf("") }
    var imuStr by remember { mutableStateOf("") }

    var indexExpanded by remember { mutableStateOf(false) }
    var sampleRateExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? Activity

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* No-op: service works even if user denies */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Sync running state on every resume — picks up stops triggered from the notification
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isRunning = UdpSenderService.started
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Route volume keys to buttonState instead of system volume when the feature is active.
    // MainActivity.volumeButtonsActive covers the screen-ON case (onKeyDown intercepts keys).
    // UdpSenderService.volumeButtonsEnabled covers the screen-OFF/locked case (MediaSession
    // VolumeProvider intercepts keys at the audio routing level, before any Activity focus).
    SideEffect {
        MainActivity.volumeButtonsActive = volumeButtons && isRunning
        UdpSenderService.volumeButtonsEnabled = volumeButtons && isRunning
    }

    // Show app over lock screen and keep screen on while volume buttons mode is active
    LaunchedEffect(volumeButtons, isRunning) {
        activity ?: return@LaunchedEffect
        if (volumeButtons && isRunning) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.setShowWhenLocked(false)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Polls UdpSenderService.debugError at 1 Hz while the service is running
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (true) {
            delay(1000)
            val err = UdpSenderService.debugError
            if (err != null) errorStr = err
            else if (errorStr != null) errorStr = null   // Reconnected successfully
        }
    }

    // Debug polling coroutine — canceled and restarted whenever isRunning or showDebug changes
    LaunchedEffect(isRunning, showDebug) {
        if (!isRunning || !showDebug) return@LaunchedEffect
        while (true) {
            delay(100)
            accStr = String.format(
                Locale.ROOT, "%.2f  %.2f  %.2f",
                UdpSenderService.debugAcc[0], UdpSenderService.debugAcc[1], UdpSenderService.debugAcc[2]
            )
            gyrStr = String.format(
                Locale.ROOT, "%.2f  %.2f  %.2f",
                UdpSenderService.debugGyr[0], UdpSenderService.debugGyr[1], UdpSenderService.debugGyr[2]
            )
            magStr = String.format(
                Locale.ROOT, "%.2f  %.2f  %.2f",
                UdpSenderService.debugMag[0], UdpSenderService.debugMag[1], UdpSenderService.debugMag[2]
            )
            imuStr = String.format(
                Locale.ROOT, "%.2f  %.2f  %.2f",
                UdpSenderService.debugImu[0], UdpSenderService.debugImu[1], UdpSenderService.debugImu[2]
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WishIMU") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it; ipError = null },
                label = { Text("IP Address") },
                isError = ipError != null,
                supportingText = ipError?.let { msg -> { Text(msg) } },
                enabled = !isRunning,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "This device: $localIp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                enabled = !isRunning,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(
                expanded = indexExpanded,
                onExpandedChange = { if (!isRunning) indexExpanded = it }
            ) {
                OutlinedTextField(
                    value = "Device index $selectedIndex",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Device index") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = indexExpanded) },
                    enabled = !isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = indexExpanded,
                    onDismissRequest = { indexExpanded = false }
                ) {
                    for (i in 0..15) {
                        DropdownMenuItem(
                            text = { Text("Device index $i") },
                            onClick = {
                                selectedIndex = i
                                indexExpanded = false
                            }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = sendOrientation,
                    onCheckedChange = { sendOrientation = it },
                    enabled = !isRunning
                )
                Text("Send Orientation")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = sendRaw,
                    onCheckedChange = { sendRaw = it },
                    enabled = !isRunning
                )
                Text("Send Raw")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = mouseButtons,
                    onCheckedChange = { mouseButtons = it },
                    enabled = !isRunning
                )
                Text("Mouse buttons")
            }

            ExposedDropdownMenuBox(
                expanded = sampleRateExpanded,
                onExpandedChange = { if (!isRunning) sampleRateExpanded = it }
            ) {
                OutlinedTextField(
                    value = SAMPLE_RATES[selectedSampleRateIdx].label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Sample rate") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sampleRateExpanded) },
                    enabled = !isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = sampleRateExpanded,
                    onDismissRequest = { sampleRateExpanded = false }
                ) {
                    SAMPLE_RATES.forEachIndexed { idx, option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                selectedSampleRateIdx = idx
                                sampleRateExpanded = false
                            }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (!isRunning) {
                        val trimmedIp = ip.trim()
                        val isValidIp = trimmedIp.isNotEmpty() &&
                            Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(trimmedIp)
                        if (!isValidIp) {
                            ipError = "Enter a valid IPv4 address"
                        } else {
                            ipError = null
                            errorStr = null
                            UdpSenderService.debugError = null
                            val portInt = port.toIntOrNull() ?: 5555
                            val sampleRateId = SAMPLE_RATES[selectedSampleRateIdx].sensorDelayId
                            prefs.edit {
                                putString("ip", trimmedIp)
                                putString("port", port)
                                putInt("index", selectedIndex)
                                putBoolean("send_orientation", sendOrientation)
                                putBoolean("send_raw", sendRaw)
                                putBoolean("mouse_buttons", mouseButtons)
                                putInt("sample_rate", sampleRateId)
                            }
                            onStart(trimmedIp, portInt, selectedIndex, sendOrientation, sendRaw, sampleRateId, volumeButtons)
                            isRunning = true
                        }
                    } else {
                        UdpSenderService.buttonState = 0
                        onStop()
                        isRunning = false
                    }
                },
                colors = if (isRunning)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRunning) "Stop" else "Start")
            }

            errorStr?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // On-screen mouse click buttons — visible when mouse buttons mode is enabled and running
            AnimatedVisibility(visible = mouseButtons && isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    UdpSenderService.buttonState =
                                        (UdpSenderService.buttonState.toInt() or 0x01).toByte()
                                    tryAwaitRelease()
                                    UdpSenderService.buttonState =
                                        (UdpSenderService.buttonState.toInt() and 0x01.inv()).toByte()
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Left Click", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.secondary)
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    UdpSenderService.buttonState =
                                        (UdpSenderService.buttonState.toInt() or 0x02).toByte()
                                    tryAwaitRelease()
                                    UdpSenderService.buttonState =
                                        (UdpSenderService.buttonState.toInt() and 0x02.inv()).toByte()
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Right Click", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = showDebug,
                    onCheckedChange = { showDebug = it }
                )
                Text("Debug")
            }

            AnimatedVisibility(visible = showDebug) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Acc: $accStr")
                    Text("Gyr: $gyrStr")
                    Text("Mag: $magStr")
                    Text("IMU: $imuStr")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // Settings bottom sheet
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Settings", style = MaterialTheme.typography.titleLarge)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Volume buttons", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Vol Up = Left Click · Vol Down = Right Click",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "App stays visible over lock screen while running",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = volumeButtons,
                        onCheckedChange = {
                            volumeButtons = it
                            prefs.edit { putBoolean("volume_buttons", it) }
                            UdpSenderService.volumeButtonsEnabled = it && isRunning
                        }
                    )
                }
            }
        }
    }
}
