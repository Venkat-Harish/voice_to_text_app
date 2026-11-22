package app.voice.dictate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.voice.dictate.asr.ModelKind
import app.voice.dictate.asr.ModelStatus
import app.voice.dictate.ui.history.HistoryScreen
import app.voice.dictate.ui.home.HomeScreen
import app.voice.dictate.ui.models.ModelsScreen
import app.voice.dictate.ui.settings.SettingsScreen
import app.voice.dictate.ui.theme.Voice
import app.voice.dictate.ui.theme.VoiceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        tabRequest.value = tabFrom(intent)
        setContent {
            val settings by VoiceApp.instance.settings.flow.collectAsStateWithLifecycle()
            VoiceTheme(settings.theme) { VoiceAppUi(tabRequest) }
        }
    }

    /** The app is singleTask, so a request to open a tab may arrive while it is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabFrom(intent)?.let { tabRequest.value = it }
    }

    private val tabRequest = mutableStateOf<Tab?>(null)

    private fun tabFrom(intent: Intent?): Tab? = if (intent?.getStringExtra(EXTRA_TAB) == "models") Tab.MODELS else null

    companion object {
        /** Opens a tab directly ("models") when the app is started with this extra. */
        const val EXTRA_TAB = "tab"
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    TALK("Talk", Icons.Outlined.Mic, Icons.Filled.Mic),
    MODELS("Models", Icons.Outlined.Download, Icons.Outlined.Download),
    HISTORY("History", Icons.Outlined.History, Icons.Filled.History),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

@Composable
private fun VoiceAppUi(tabRequest: MutableState<Tab?>) {
    var tab by rememberSaveable { mutableStateOf(Tab.TALK) }
    // Which models the Models tab lists (voice to text, or text to voice); kept while you switch tabs.
    var modelsKind by rememberSaveable { mutableStateOf(ModelKind.SPEECH) }
    val requested by tabRequest
    LaunchedEffect(requested) { requested?.let { tab = it; tabRequest.value = null } }
    val states by VoiceApp.instance.models.states.collectAsStateWithLifecycle()
    val downloading = states.values.any { it.status == ModelStatus.DOWNLOADING }
    val c = Voice.colors

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.TALK -> HomeScreen(goModels = { modelsKind = ModelKind.SPEECH; tab = Tab.MODELS })
                Tab.MODELS -> ModelsScreen(modelsKind) { modelsKind = it }
                Tab.HISTORY -> HistoryScreen()
                Tab.SETTINGS -> SettingsScreen(goModels = { kind -> modelsKind = kind; tab = Tab.MODELS })
            }
        }
        HorizontalDivider(color = c.line)
        NavigationBar(containerColor = c.card, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = {
                        BadgedBox(badge = { if (t == Tab.MODELS && downloading) Badge(containerColor = c.turmeric) }) {
                            Icon(if (tab == t) t.selectedIcon else t.icon, contentDescription = t.label)
                        }
                    },
                    label = { Text(t.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.ink, selectedTextColor = c.ink,
                        indicatorColor = c.turmericSoft,
                        unselectedIconColor = c.ink3, unselectedTextColor = c.ink3,
                    ),
                )
            }
        }
    }
}
