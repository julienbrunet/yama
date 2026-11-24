package fr.jbrunet.yama.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.jbrunet.yama.R
import fr.jbrunet.yama.data.location.LocationProvider
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.WeatherAlertHorizon
import fr.jbrunet.yama.domain.WeatherAlertRule
import fr.jbrunet.yama.domain.WeatherAlertType
import fr.jbrunet.yama.ui.amountRes
import fr.jbrunet.yama.ui.components.RoundIconButton
import fr.jbrunet.yama.ui.components.TileShape
import fr.jbrunet.yama.ui.currentLocale
import fr.jbrunet.yama.ui.labelRes
import fr.jbrunet.yama.ui.theme.Slate
import fr.jbrunet.yama.ui.theme.screenBackground
import fr.jbrunet.yama.work.Notifications
import java.text.NumberFormat
import java.util.UUID

@Composable
fun WeatherAlertsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val preferences by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var canNotify by remember { mutableStateOf(Notifications.canPostWeather(context)) }
    var canLocate by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canNotify = Notifications.canPostWeather(context)
        canLocate = viewModel.hasLocationPermission()
        viewModel.refreshAlerts()
    }
    LifecycleResumeEffect(Unit) {
        canNotify = Notifications.canPostWeather(context)
        canLocate = viewModel.hasLocationPermission()
        onPauseOrDispose {}
    }

    fun notificationPermissionNeeded() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED

    WeatherAlertsContent(
        preferences.weatherAlerts, preferences.savedPlaces, onBack,
        onSave = { rule ->
            viewModel.saveAlert(rule)
            if (rule.enabled) {
                val needed = buildList {
                    if (notificationPermissionNeeded()) add(Manifest.permission.POST_NOTIFICATIONS)
                    if (rule.place == null && !canLocate) addAll(LocationProvider.PERMISSIONS)
                }
                if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
            }
        },
        onDelete = viewModel::removeAlert,
        requestNotifications = if (canNotify || preferences.weatherAlerts.none { it.enabled }) null else {
            {
                if (notificationPermissionNeeded()) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        },
        requestLocation = if (canLocate || preferences.weatherAlerts.none { it.enabled && it.place == null }) null else {
            { permissions.launch(LocationProvider.PERMISSIONS) }
        },
    )
}

@Composable
internal fun WeatherAlertsContent(
    rules: List<WeatherAlertRule>,
    places: List<Place>,
    onBack: () -> Unit = {},
    onSave: (WeatherAlertRule) -> Unit = {},
    onDelete: (String) -> Unit = {},
    requestNotifications: (() -> Unit)? = null,
    requestLocation: (() -> Unit)? = null,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().screenBackground().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), onBack)
            Text(stringResource(R.string.weather_alerts), style = MaterialTheme.typography.headlineLarge, color = Slate.Primary)
        }
        requestNotifications?.let {
            Text(stringResource(R.string.alert_permissions), color = Slate.Secondary)
            OutlinedButton(onClick = it) { Text(stringResource(R.string.alert_allow_notifications)) }
        }
        requestLocation?.let {
            Text(stringResource(R.string.alert_location_permission), color = Slate.Secondary)
            OutlinedButton(onClick = it) { Text(stringResource(R.string.alert_allow_location)) }
        }
        if (rules.isEmpty()) Text(stringResource(R.string.alert_empty), color = Slate.Secondary)
        rules.forEach { rule ->
            val type = stringResource(rule.type.labelRes)
            val threshold = NumberFormat.getNumberInstance(currentLocale()).apply { maximumFractionDigits = 3 }.format(rule.threshold)
            val amount = stringResource(rule.type.amountRes, threshold)
            val condition = stringResource(if (rule.type == WeatherAlertType.COLD) R.string.alert_condition_below else R.string.alert_condition_above, amount)
            val toggleLabel = stringResource(R.string.alert_toggle, type)
            Row(
                Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.alert_edit)) { editing = rule.id }
                        .padding(vertical = 12.dp, horizontal = 2.dp),
                ) {
                    Text(type, color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
                    Text(condition, color = Slate.Secondary)
                    Text(stringResource(rule.horizon.labelRes), color = Slate.Secondary)
                    Text(rule.place?.name ?: stringResource(R.string.alert_current_place), color = Slate.Muted)
                }
                Switch(
                    rule.enabled, { onSave(rule.copy(enabled = it)) },
                    modifier = Modifier.semantics { contentDescription = toggleLabel },
                )
                IconButton(onClick = { deleting = rule.id }) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.alert_delete), tint = Slate.Secondary)
                }
            }
        }
        Button(onClick = { editing = "" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.alert_add)) }
    }

    editing?.let { id ->
        val selected = rules.firstOrNull { it.id == id }
        if (id.isEmpty() || selected != null) {
            AlertEditor(selected, places, onClose = { editing = null }) {
                onSave(it)
                editing = null
            }
        }
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.alert_delete_prompt)) },
            confirmButton = { TextButton(onClick = { onDelete(id); deleting = null }) { Text(stringResource(R.string.alert_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.alert_cancel)) } },
        )
    }
}

@Composable
private fun AlertEditor(rule: WeatherAlertRule?, places: List<Place>, onClose: () -> Unit, onSave: (WeatherAlertRule) -> Unit) {
    val targets = listOf<Place?>(null) + (places + listOfNotNull(rule?.place)).distinctBy { it.latitude to it.longitude }
    val position = stringResource(R.string.alert_current_place)
    var type by rememberSaveable { mutableStateOf(rule?.type ?: WeatherAlertType.RAIN) }
    var threshold by rememberSaveable { mutableStateOf((rule?.threshold ?: type.defaultThreshold).toString().removeSuffix(".0")) }
    var horizon by rememberSaveable { mutableStateOf(rule?.horizon ?: WeatherAlertHorizon.NEXT_HOUR) }
    var targetKey by rememberSaveable { mutableStateOf(rule?.place?.let { "${it.latitude},${it.longitude}" }) }
    val target = targets.firstOrNull { it != null && "${it.latitude},${it.longitude}" == targetKey }
    val value = threshold.trim().replace(',', '.').toDoubleOrNull()?.takeIf {
        it.isFinite() && (type == WeatherAlertType.COLD || type == WeatherAlertType.HEAT || it >= 0)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (rule == null) R.string.alert_add else R.string.alert_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AlertChoice(stringResource(R.string.alert_type), type, WeatherAlertType.entries.map { it to stringResource(it.labelRes) }) {
                    type = it
                    threshold = it.defaultThreshold.toString().removeSuffix(".0")
                }
                val unit = stringResource(type.amountRes, "").trim()
                OutlinedTextField(
                    value = threshold, onValueChange = { threshold = it },
                    label = { Text(stringResource(if (type == WeatherAlertType.COLD) R.string.alert_threshold_below else R.string.alert_threshold_above, unit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (type == WeatherAlertType.COLD || type == WeatherAlertType.HEAT) KeyboardType.Text else KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                AlertChoice(stringResource(R.string.alert_horizon), horizon, WeatherAlertHorizon.entries.map { it to stringResource(it.labelRes) }) { horizon = it }
                AlertChoice(stringResource(R.string.alert_place), target, targets.map { it to (it?.name ?: position) }) {
                    targetKey = it?.let { p -> "${p.latitude},${p.longitude}" }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = {
                value?.let { onSave(WeatherAlertRule(rule?.id ?: UUID.randomUUID().toString(), type, it, horizon, target, rule?.enabled ?: true)) }
            }) { Text(stringResource(R.string.alert_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.alert_cancel)) } },
    )
}

@Composable
private fun <T> AlertChoice(label: String, selected: T, choices: List<Pair<T, String>>, onChoose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(choices.firstOrNull { it.first == selected }?.second.orEmpty())
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onChoose(value); expanded = false })
                }
            }
        }
    }
}
