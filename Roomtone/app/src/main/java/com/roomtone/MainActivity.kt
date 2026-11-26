package com.roomtone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var monitorService: MonitorService? = null
    private var bound = false
    private var snapshotObserver: ((MonitorSnapshot) -> Unit)? = null
    private var onServiceSnapshot: ((MonitorSnapshot) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            monitorService = (service as? MonitorService.LocalBinder)?.service()
            bound = monitorService != null
            monitorService?.setObserver(snapshotObserver)
            monitorService?.currentSnapshot()?.let { onServiceSnapshot?.invoke(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            monitorService = null
            bound = false
            onServiceSnapshot?.invoke(MonitorSnapshot())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val initialSettings = MonitorSettings(
            thresholdDbfs = preferences.getInt(KEY_THRESHOLD, -25).coerceIn(-60, -5),
            windowSeconds = preferences.getInt(KEY_WINDOW, 5).coerceIn(1, 60),
            alertMode = runCatching { AlertMode.valueOf(preferences.getString(KEY_ALERT_MODE, AlertMode.BOTH.name)!!) }
                .getOrDefault(AlertMode.BOTH),
        )
        setContent {
            RoomtoneScreen(
                initialSettings = initialSettings,
                onStart = { settings -> startMonitoring(settings) },
                onStop = { stopMonitoring() },
                onSettingsChanged = { settings ->
                    preferences.edit()
                        .putInt(KEY_THRESHOLD, settings.thresholdDbfs)
                        .putInt(KEY_WINDOW, settings.windowSeconds)
                        .putString(KEY_ALERT_MODE, settings.alertMode.name)
                        .apply()
                    monitorService?.configure(settings)
                },
                bindObserver = { observer, callback ->
                    snapshotObserver = observer
                    onServiceSnapshot = callback
                    monitorService?.setObserver(observer)
                    monitorService?.currentSnapshot()?.let(callback)
                },
                isMicrophoneGranted = {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, MonitorService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        monitorService?.setObserver(null)
        snapshotObserver = null
        onServiceSnapshot = null
        if (bound) unbindService(serviceConnection)
        bound = false
        monitorService = null
        super.onStop()
    }

    private fun startMonitoring(settings: MonitorSettings) {
        val intent = Intent(this, MonitorService::class.java)
            .setAction(MonitorService.ACTION_START)
            .putExtra(MonitorService.EXTRA_THRESHOLD, settings.thresholdDbfs)
            .putExtra(MonitorService.EXTRA_WINDOW, settings.windowSeconds)
            .putExtra(MonitorService.EXTRA_ALERT_MODE, settings.alertMode.name)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopMonitoring() {
        startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
    }

    companion object {
        private const val PREFERENCES = "roomtone_settings"
        private const val KEY_THRESHOLD = "threshold_dbfs"
        private const val KEY_WINDOW = "window_seconds"
        private const val KEY_ALERT_MODE = "alert_mode"
    }
}

@Composable
private fun RoomtoneScreen(
    initialSettings: MonitorSettings,
    onStart: (MonitorSettings) -> Unit,
    onStop: () -> Unit,
    onSettingsChanged: (MonitorSettings) -> Unit,
    bindObserver: (setObserver: ((MonitorSnapshot) -> Unit)?, onSnapshot: (MonitorSnapshot) -> Unit) -> Unit,
    isMicrophoneGranted: () -> Boolean,
) {
    var threshold by remember { mutableStateOf(initialSettings.thresholdDbfs) }
    var windowSeconds by remember { mutableStateOf(initialSettings.windowSeconds) }
    var alertMode by remember { mutableStateOf(initialSettings.alertMode) }
    var snapshot by remember { mutableStateOf(MonitorSnapshot(thresholdDbfs = threshold, windowSeconds = windowSeconds)) }
    var pendingStart by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val settings = MonitorSettings(threshold, windowSeconds, alertMode)
    val context = LocalContext.current
    val permissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val microphoneOkay = result[Manifest.permission.RECORD_AUDIO] == true || isMicrophoneGranted()
        val notificationOkay = Build.VERSION.SDK_INT < 33 || result[Manifest.permission.POST_NOTIFICATIONS] == true
        if (pendingStart && microphoneOkay && notificationOkay) {
            pendingStart = false
            permissionMessage = null
            onStart(settings)
        } else if (pendingStart) {
            pendingStart = false
            permissionMessage = if (!microphoneOkay) {
                "Microphone permission is needed to monitor sound."
            } else {
                "Allow notifications so the ongoing monitor has a visible Stop control."
            }
        }
    }

    DisposableEffect(Unit) {
        bindObserver({ next -> snapshot = next }, { next -> snapshot = next })
        onDispose { bindObserver(null, {}) }
    }
    LaunchedEffect(settings) { onSettingsChanged(settings) }

    val ink = Color(0xFF202C2D)
    val muted = Color(0xFF6D7B78)
    val paper = Color(0xFFF4F6F2)
    val teal = Color(0xFF167C80)
    val amber = Color(0xFFF0B74B)

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = teal,
            secondary = Color(0xFFB36A39),
            tertiary = amber,
            surface = paper,
            background = paper,
        ),
    ) {
        Surface(color = paper, contentColor = ink, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(teal),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("≈", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Roomtone", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.semantics { contentDescription = if (snapshot.active) "Monitoring active" else "Monitoring stopped" },
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (snapshot.active) teal else muted.copy(alpha = 0.45f)))
                        Text(if (snapshot.active) "ACTIVE" else "STOPPED", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Text("ROOM LEVEL", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (!snapshot.ready) "Warming up" else "Rolling average",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    snapshot.averageDbfs?.let(::formatDbfs) ?: "--.-",
                    modifier = Modifier.padding(top = 3.dp),
                    fontSize = 62.sp,
                    lineHeight = 68.sp,
                    fontWeight = FontWeight.Light,
                )
                Text("dBFS  ·  relative input level", color = muted, fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))

                LevelMeter(
                    currentDbfs = snapshot.currentDbfs,
                    thresholdDbfs = threshold.toDouble(),
                    ready = snapshot.ready,
                    alerting = snapshot.alerting,
                    teal = teal,
                    amber = amber,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("CURRENT  ${formatDbfs(snapshot.currentDbfs)} dBFS", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text("THRESHOLD  ${threshold} dBFS", color = ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("MONITORING TIME", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(formatElapsed(snapshot.elapsedMillis), modifier = Modifier.padding(top = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("WINDOW", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("${windowSeconds}s average", modifier = Modifier.padding(top = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(25.dp))
                SettingLabel("ALERT THRESHOLD", "${threshold} dBFS", ink, muted)
                Slider(
                    value = threshold.toFloat(),
                    onValueChange = { threshold = it.toInt().coerceIn(-60, -5) },
                    valueRange = -60f..-5f,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Quieter  −60", color = muted, fontSize = 11.sp)
                    Text("−5  Louder", color = muted, fontSize = 11.sp)
                }

                Spacer(Modifier.height(18.dp))
                SettingLabel("AVERAGING WINDOW", "${windowSeconds} sec", ink, muted)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallControl("−", enabled = windowSeconds > 1, onClick = { windowSeconds = (windowSeconds - 1).coerceAtLeast(1) })
                    Text("${windowSeconds} s", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    SmallControl("+", enabled = windowSeconds < 60, onClick = { windowSeconds = (windowSeconds + 1).coerceAtMost(60) })
                    Slider(
                        value = windowSeconds.toFloat(),
                        onValueChange = { windowSeconds = it.toInt().coerceIn(1, 60) },
                        valueRange = 1f..60f,
                        modifier = Modifier.weight(2.4f),
                    )
                }

                Spacer(Modifier.height(18.dp))
                SettingLabel("ALERT MODE", null, ink, muted)
                Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlertMode.entries.forEach { mode ->
                        val selected = alertMode == mode
                        Button(
                            onClick = { alertMode = mode },
                            modifier = Modifier.weight(1f).height(46.dp).semantics {
                                contentDescription = "${mode.label} alert mode"
                                stateDescription = if (selected) "Selected" else "Not selected"
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) teal else Color.White,
                                contentColor = if (selected) Color.White else ink,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(mode.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(Modifier.height(25.dp))
                if (permissionMessage != null || snapshot.error != null) {
                    Text(
                        permissionMessage ?: snapshot.error.orEmpty(),
                        color = Color(0xFF9B3D2F),
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    )
                }
                Button(
                    onClick = {
                        permissionMessage = null
                        val required = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        val missing = required.any {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (!missing) onStart(settings) else {
                            pendingStart = true
                            permissionsLauncher.launch(required.toTypedArray())
                        }
                    },
                    enabled = !snapshot.active,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = teal, disabledContainerColor = teal.copy(alpha = 0.42f)),
                ) {
                    Text(if (snapshot.active) "Monitoring" else "Start monitoring", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                if (snapshot.active) {
                    TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Stop monitoring", color = Color(0xFF9B3D2F), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(17.dp))
                Text(
                    "dBFS is a relative digital input level, not calibrated sound-pressure dB. Audio stays on this device and is not recorded or uploaded.",
                    color = muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 26.dp),
                )
            }
        }
    }
}

@Composable
private fun LevelMeter(
    currentDbfs: Double,
    thresholdDbfs: Double,
    ready: Boolean,
    alerting: Boolean,
    teal: Color,
    amber: Color,
) {
    val meterDescription = if (ready) {
        "Input level ${formatDbfs(currentDbfs)} dBFS; alert threshold ${thresholdDbfs.toInt()} dBFS"
    } else {
        "Input level ${formatDbfs(currentDbfs)} dBFS; averaging window is filling"
    }
    Canvas(
        modifier = Modifier.fillMaxWidth().height(30.dp).semantics { contentDescription = meterDescription },
    ) {
        val left = 2.dp.toPx()
        val barHeight = 12.dp.toPx()
        val top = (size.height - barHeight) / 2f
        val width = size.width - 4.dp.toPx()
        val thresholdX = left + ((thresholdDbfs + 60.0) / 60.0).toFloat().coerceIn(0f, 1f) * width
        val currentX = left + ((currentDbfs + 60.0) / 60.0).toFloat().coerceIn(0f, 1f) * width
        drawLine(Color(0xFFDCE3DE), androidx.compose.ui.geometry.Offset(left, size.height / 2), androidx.compose.ui.geometry.Offset(left + width, size.height / 2), barHeight, cap = StrokeCap.Round)
        if (currentX > left) {
            drawLine(if (alerting) amber else teal, androidx.compose.ui.geometry.Offset(left, size.height / 2), androidx.compose.ui.geometry.Offset(currentX, size.height / 2), barHeight, cap = StrokeCap.Round)
        }
        drawLine(Color(0xFF202C2D), androidx.compose.ui.geometry.Offset(thresholdX, top - 4.dp.toPx()), androidx.compose.ui.geometry.Offset(thresholdX, top + barHeight + 4.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun SettingLabel(title: String, value: String?, ink: Color, muted: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (value != null) Text(value, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallControl(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatDbfs(value: Double): String = String.format(Locale.getDefault(), "%.1f", value.coerceIn(-120.0, 0.0))

private fun formatElapsed(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3_600, (seconds / 60) % 60, seconds % 60)
}