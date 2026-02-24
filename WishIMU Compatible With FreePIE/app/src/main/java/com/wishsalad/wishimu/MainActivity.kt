package com.wishsalad.wishimu

import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

private data class SampleRateOption(val sensorDelayId: Int, val label: String)

private val SAMPLE_RATES = listOf(
    SampleRateOption(SensorManager.SENSOR_DELAY_NORMAL,   "Slowest – 5 FPS"),
    SampleRateOption(SensorManager.SENSOR_DELAY_UI,       "Average – 16 FPS"),
    SampleRateOption(SensorManager.SENSOR_DELAY_GAME,     "Fast – 50 FPS"),
    SampleRateOption(SensorManager.SENSOR_DELAY_FASTEST,  "Fastest – no delay")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getPreferences(MODE_PRIVATE)
        setContent {
            WishImuTheme {
                WishImuApp(
                    prefs = prefs,
                    onStart = { ip, port, index, sendOrientation, sendRaw, sampleRateId ->
                        startForegroundService(
                            Intent(this, UdpSenderService::class.java).apply {
                                putExtra("toIp", ip)
                                putExtra("port", port)
                                putExtra("deviceIndex", index.toByte())
                                putExtra("sendOrientation", sendOrientation)
                                putExtra("sendRaw", sendRaw)
                                putExtra("sampleRate", sampleRateId)
                            }
                        )
                    },
                    onStop = { stopService(Intent(this, UdpSenderService::class.java)) }
                )
            }
        }
    }
}

@Composable
fun WishImuTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishImuApp(
    prefs: SharedPreferences,
    onStart: (ip: String, port: Int, index: Int, sendOrientation: Boolean, sendRaw: Boolean, sampleRateId: Int) -> Unit,
    onStop: () -> Unit
) {
    val savedSampleRateId = prefs.getInt("sample_rate", SensorManager.SENSOR_DELAY_FASTEST)
    val initialSampleRateIdx = SAMPLE_RATES.indexOfFirst { it.sensorDelayId == savedSampleRateId }.coerceAtLeast(0)

    var ip by remember { mutableStateOf(prefs.getString("ip", "192.168.1.1")!!) }
    var port by remember { mutableStateOf(prefs.getString("port", "5555")!!) }
    var selectedIndex by remember { mutableIntStateOf(prefs.getInt("index", 0)) }
    var sendOrientation by remember { mutableStateOf(prefs.getBoolean("send_orientation", true)) }
    var sendRaw by remember { mutableStateOf(prefs.getBoolean("send_raw", true)) }
    var selectedSampleRateIdx by remember { mutableIntStateOf(initialSampleRateIdx) }
    var isRunning by remember { mutableStateOf(UdpSenderService.started) }
    var showDebug by remember { mutableStateOf(false) }

    var accStr by remember { mutableStateOf("") }
    var gyrStr by remember { mutableStateOf("") }
    var magStr by remember { mutableStateOf("") }
    var imuStr by remember { mutableStateOf("") }

    var indexExpanded by remember { mutableStateOf(false) }
    var sampleRateExpanded by remember { mutableStateOf(false) }

    // Sync running state on initial composition (survives rotation since UdpSenderService.started is static)
    LaunchedEffect(Unit) { isRunning = UdpSenderService.started }

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
        topBar = { TopAppBar(title = { Text("WishIMU") }) }
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
                onValueChange = { ip = it },
                label = { Text("IP Address") },
                enabled = !isRunning,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
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
                        val portInt = port.toIntOrNull() ?: 5555
                        val sampleRateId = SAMPLE_RATES[selectedSampleRateIdx].sensorDelayId
                        prefs.edit {
                            putString("ip", ip)
                            putString("port", port)
                            putInt("index", selectedIndex)
                            putBoolean("send_orientation", sendOrientation)
                            putBoolean("send_raw", sendRaw)
                            putInt("sample_rate", sampleRateId)
                        }
                        onStart(ip, portInt, selectedIndex, sendOrientation, sendRaw, sampleRateId)
                        isRunning = true
                    } else {
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
}
