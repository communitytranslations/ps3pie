package com.wishsalad.wishimu

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.net.NetworkInterface
import java.util.Locale

/**
 * Returns true if VolumeKeyService is enabled in Accessibility Settings.
 * Checked on every Activity resume so the settings UI stays in sync.
 */
private fun isVolumeKeyServiceEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any {
            it.resolveInfo.serviceInfo.packageName == ctx.packageName &&
            it.resolveInfo.serviceInfo.name == VolumeKeyService::class.java.name
        }
}

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

private data class SampleRateOption(val sensorDelayId: Int, val label: String, val shortLabel: String)

private val SAMPLE_RATES = listOf(
    SampleRateOption(SensorManager.SENSOR_DELAY_NORMAL,   "Slowest – 5 FPS",    "Slowest"),
    SampleRateOption(SensorManager.SENSOR_DELAY_UI,       "Average – 16 FPS",   "Average"),
    SampleRateOption(SensorManager.SENSOR_DELAY_GAME,     "Fast – 50 FPS",      "Fast"),
    SampleRateOption(SensorManager.SENSOR_DELAY_FASTEST,  "Fastest – no delay", "Fastest")
)

class MainActivity : ComponentActivity() {

    companion object {
        /** True while volume buttons should route to buttonState instead of system volume. */
        @Volatile var volumeButtonsActive = false
        /**
         * True while this Activity is resumed (foreground or shown over lock screen).
         * Read by VolumeKeyService to skip AccessibilityService interception when the
         * Activity can already handle key events via onKeyDown/onKeyUp directly.
         */
        @Volatile var isInForeground = false
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
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
                    UdpSenderService.buttonState.updateAndGet { it or 0x01 }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    UdpSenderService.buttonState.updateAndGet { it or 0x02 }
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
                    UdpSenderService.buttonState.updateAndGet { it and 0x01.inv() }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    UdpSenderService.buttonState.updateAndGet { it and 0x02.inv() }
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

@Suppress("UNUSED_VALUE") // showSettings = false inside ModalBottomSheet triggers a false positive
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishImuApp(
    prefs: SharedPreferences,
    onStart: (ip: String, port: Int, index: Int, sendOrientation: Boolean, sendRaw: Boolean, sampleRateId: Int, volumeButtons: Boolean) -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
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
    // isConnecting is only true during the ~5s ACK window after a fresh Start press
    var isConnecting by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(isVolumeKeyServiceEnabled(context)) }
    var showDebug by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var ipError by remember { mutableStateOf<String?>(null) }
    var errorStr by remember { mutableStateOf<String?>(null) }

    var accStr by remember { mutableStateOf("") }
    var gyrStr by remember { mutableStateOf("") }
    var magStr by remember { mutableStateOf("") }
    var imuStr by remember { mutableStateOf("") }

    var indexExpanded by remember { mutableStateOf(false) }

    val activity = context as? Activity

    // Animated color for the Start/Stop button
    val buttonColor by animateColorAsState(
        targetValue = if (isRunning) MaterialTheme.colorScheme.error
                      else MaterialTheme.colorScheme.primary,
        label = "startStopColor"
    )

    // Interaction sources for mouse buttons — kept outside AnimatedVisibility
    // so press/release state is always tracked and cleaned up properly
    val leftSource = remember { MutableInteractionSource() }
    val leftPressed by leftSource.collectIsPressedAsState()
    val rightSource = remember { MutableInteractionSource() }
    val rightPressed by rightSource.collectIsPressedAsState()

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

    // Sync running state and accessibility status on every resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isRunning = UdpSenderService.started
                // Re-check in case the user just enabled/disabled the AccessibilityService
                accessibilityEnabled = isVolumeKeyServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Route volume keys to buttonState instead of system volume when the feature is active.
    // When volumeButtons=true the MediaSession VolumeProvider intercepts volume keys at the
    // AudioService level — both screen-ON and screen-OFF — before the event reaches onKeyDown.
    // onKeyDown/onKeyUp in MainActivity are therefore fallback handlers for devices or ROM
    // versions where the VolumeProvider does not intercept reliably with screen on.
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

    // Clear connecting indicator if service stops (e.g. from notification "Stop" action)
    LaunchedEffect(isRunning) {
        if (!isRunning) isConnecting = false
    }

    // Auto-dismiss connecting indicator after ACK timeout (~5s) + safety buffer
    LaunchedEffect(isConnecting) {
        if (!isConnecting) return@LaunchedEffect
        delay(5500)
        isConnecting = false
    }

    // Polls service state at 1 Hz while running.
    // Also detects external stops (notification "Stop" button): pulling down the notification
    // shade does not trigger onPause/onResume on all devices, so the DisposableEffect above
    // may never fire. This loop catches the stop within ~1 s regardless.
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (true) {
            delay(1000)
            if (!UdpSenderService.started) {
                isRunning = false
                isConnecting = false
                return@LaunchedEffect
            }
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

    // buttonState is updated via pointerInput(PointerEventPass.Initial) on each Button below.
    // See the buttons themselves for the rationale.

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WishIMU") },
                actions = {
                    // Spinner while waiting for first ACK after Start
                    AnimatedVisibility(visible = isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(24.dp)
                                .semantics { contentDescription = "Connecting to host" },
                            strokeWidth = 2.dp
                        )
                    }
                    // Live badge once connection is established
                    AnimatedVisibility(visible = isRunning && !isConnecting) {
                        AssistChip(
                            onClick = { },
                            label = { Text("Live") },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            },
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .semantics { contentDescription = "Service is running" }
                        )
                    }
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

            // ImeAction.Next moves focus to Port field on keyboard "Next" tap
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it; ipError = null },
                label = { Text("IP Address") },
                isError = ipError != null,
                supportingText = ipError?.let { msg -> { Text(msg) } },
                enabled = !isRunning,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "This device: $localIp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // ImeAction.Done closes the keyboard when the user finishes entering the port
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                enabled = !isRunning,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(
                expanded = indexExpanded,
                onExpandedChange = { if (!isRunning) indexExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedIndex.toString(),
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Device index") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = indexExpanded) },
                    enabled = !isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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

            // FilterChips — more compact and touch-friendly than checkboxes;
            // FlowRow wraps chips onto a second line on very narrow screens
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = sendOrientation,
                    onClick = { if (!isRunning) sendOrientation = !sendOrientation },
                    label = { Text("Orientation") },
                    enabled = !isRunning
                )
                FilterChip(
                    selected = sendRaw,
                    onClick = { if (!isRunning) sendRaw = !sendRaw },
                    label = { Text("Raw Data") },
                    enabled = !isRunning
                )
                FilterChip(
                    selected = mouseButtons,
                    onClick = { if (!isRunning) mouseButtons = !mouseButtons },
                    label = { Text("Mouse Buttons") },
                    enabled = !isRunning
                )
            }

            // SegmentedButton replaces the sample rate dropdown
            Text(
                text = "Sample Rate",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SAMPLE_RATES.forEachIndexed { idx, option ->
                    SegmentedButton(
                        selected = selectedSampleRateIdx == idx,
                        onClick = { if (!isRunning) selectedSampleRateIdx = idx },
                        shape = SegmentedButtonDefaults.itemShape(idx, SAMPLE_RATES.size),
                        enabled = !isRunning
                    ) {
                        Text(option.shortLabel)
                    }
                }
            }

            // Start/Stop button — icon + animated color transition via expressive spring
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
                            isConnecting = true  // Triggers progress spinner in topbar
                        }
                    } else {
                        UdpSenderService.buttonState.set(0)
                        onStop()
                        isRunning = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
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

            // On-screen mouse click buttons — visible when mouse buttons mode is enabled and running.
            //
            // buttonState is updated via pointerInput(PointerEventPass.Initial), which fires
            // on the RAW pointer event BEFORE Compose does any gesture detection or recomposition.
            // This bypasses the ~16 ms vsync frame delay that plagued LaunchedEffect(pressed) and
            // interactions.collect — both still go through Compose's frame cycle before running.
            //
            // PointerEventPass.Initial → outer-to-inner: our modifier runs first, updates
            // buttonState synchronously, then returns without consuming the event so the Button's
            // own click/ripple handling still works normally for visual feedback.
            //
            // collectIsPressedAsState() + buttonColors still provide the color-change feedback
            // one frame later — that delayed visual is acceptable; the data path is instant.
            AnimatedVisibility(visible = mouseButtons && isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { },
                        interactionSource = leftSource,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (leftPressed) MaterialTheme.colorScheme.primary
                                             else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (leftPressed) MaterialTheme.colorScheme.onPrimary
                                           else MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.any { it.pressed })
                                            UdpSenderService.buttonState.updateAndGet { it or  0x01 }
                                        else
                                            UdpSenderService.buttonState.updateAndGet { it and 0x01.inv() }
                                    }
                                }
                            }
                            .semantics { contentDescription = "Left click — touch and hold to press" }
                    ) {
                        Text("Left Click", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { },
                        interactionSource = rightSource,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (rightPressed) MaterialTheme.colorScheme.secondary
                                             else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (rightPressed) MaterialTheme.colorScheme.onSecondary
                                           else MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.any { it.pressed })
                                            UdpSenderService.buttonState.updateAndGet { it or  0x02 }
                                        else
                                            UdpSenderService.buttonState.updateAndGet { it and 0x02.inv() }
                                    }
                                }
                            }
                            .semantics { contentDescription = "Right click — touch and hold to press" }
                    ) {
                        Text("Right Click", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Debug toggle — entire Row is the touch target (48dp+ height via padding),
            // following M3 accessibility guidelines for switch list items
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = showDebug,
                        onValueChange = { showDebug = it },
                        role = Role.Switch
                    )
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Debug", style = MaterialTheme.typography.bodyMedium)
                // onCheckedChange = null: interaction handled by the Row's toggleable modifier
                Switch(checked = showDebug, onCheckedChange = null)
            }

            // Debug panel in a Card with monospace font for aligned sensor columns
            AnimatedVisibility(visible = showDebug) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Acc: $accStr", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text("Gyr: $gyrStr", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text("Mag: $magStr", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text("IMU: $imuStr", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
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

                // Volume buttons row — entire Row is the touch target for M3 accessibility
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = volumeButtons,
                            onValueChange = { newValue ->
                                volumeButtons = newValue
                                prefs.edit { putBoolean("volume_buttons", newValue) }
                                UdpSenderService.volumeButtonsEnabled = newValue && isRunning
                            },
                            role = Role.Switch
                        )
                        .padding(vertical = 8.dp),
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
                    }
                    // onCheckedChange = null: interaction handled by the Row's toggleable modifier
                    Switch(checked = volumeButtons, onCheckedChange = null)
                }

                // Accessibility service status — shown only when Volume buttons is on.
                // VolumeKeyService provides true KEY_DOWN + KEY_UP events even when WishIMU
                // is in the background (screen unlocked, game in foreground).  Without it,
                // VolumeProvider is used as a fallback but rapid clicks may merge.
                AnimatedVisibility(visible = volumeButtons) {
                    if (accessibilityEnabled) {
                        Text(
                            text = "Key capture active — rapid clicks work in background",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "For fast clicks with screen unlocked: enable WishIMU in Accessibility Settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    )
                                }
                            ) {
                                Text("Open Accessibility Settings")
                            }
                        }
                    }
                }
            }
        }
    }
}
