package com.alphahostingtv.tv.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alphahostingtv.tv.data.config.AlphaProviderDefaults
import com.alphahostingtv.tv.data.config.DeviceMac
import com.alphahostingtv.tv.data.prefs.UserPreferencesStore
import com.alphahostingtv.tv.data.repo.ProviderRepository
import com.alphahostingtv.tv.ui.common.FormFactor
import com.alphahostingtv.tv.ui.common.rememberFormFactor
import com.alphahostingtv.tv.ui.settings.FormField
import com.alphahostingtv.tv.ui.theme.UltraFonts
import com.alphahostingtv.tv.ui.theme.UltraTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: UserPreferencesStore,
    private val provider: ProviderRepository,
    private val deviceMac: DeviceMac,
) : ViewModel() {

    val mac: String = deviceMac.mac

    val show: StateFlow<Boolean> = combine(
        prefs.flow, provider.observeProviders(),
    ) { p, ps -> !p.hasSeenOnboarding || ps.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _completed = MutableStateFlow(false)
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    fun addAlphaLogin(username: String, password: String) {
        val cleanUser = username.trim()
        if (cleanUser.isBlank() || password.isBlank()) {
            _message.value = "Enter your username and password."
            return
        }
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            _message.value = "Adding Alpha Hosting TV login..."
            try {
                val id = provider.addXtream(
                    name = AlphaProviderDefaults.NAME,
                    baseUrl = AlphaProviderDefaults.XTREAM_SERVER_URL,
                    username = cleanUser,
                    password = password,
                )
                _message.value = "Syncing channels..."
                val n = provider.syncAll(id) { _message.value = it }
                if (n <= 0) {
                    _message.value = "Login works, but no channels or library items were returned. Ask Alpha Hosting TV to confirm your package has active channels."
                    return@launch
                }
                provider.setDefault(id)
                _message.value = "Done - $n channels"
                prefs.markOnboardingSeen()
                _completed.value = true
            } catch (t: Throwable) {
                _message.value = when {
                    t is SecurityException -> "Login failed: ${t.message ?: \"Invalid username or password.\"}"
                    t is SocketTimeoutException -> "Login timed out while reaching Alpha Hosting TV. Try again on a faster network."
                    t is java.io.IOException -> "Could not reach Alpha Hosting TV server. Check internet or server URL."
                    else -> "Could not add login: ${t.message ?: t.javaClass.simpleName}"
                }
            } finally {
                _syncing.value = false
            }
        }
    }

    fun dismiss() {
        viewModelScope.launch { prefs.markOnboardingSeen() }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun OnboardingWizard(
    onLoginComplete: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val show by vm.show.collectAsState()
    if (!show) return

    val compact = rememberFormFactor() == FormFactor.Compact
    val syncing by vm.syncing.collectAsState()
    val message by vm.message.collectAsState()
    val completed by vm.completed.collectAsState()
    val S = com.alphahostingtv.tv.i18n.LocalStrings.current
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val canSubmit = !syncing

    androidx.compose.runtime.LaunchedEffect(completed) {
        if (completed) onLoginComplete()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(Color(0x553A0A26), Color.Transparent),
                    center = Offset(1632f, 324f),
                    radius = 900f,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(Color(0x442A1A55), Color.Transparent),
                    center = Offset(288f, 864f),
                    radius = 800f,
                )
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (compact) 20.dp else 80.dp,
                    vertical = if (compact) 28.dp else 60.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(if (compact) 38.dp else 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(UltraTokens.Accent, UltraTokens.Accent2))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "▶",
                        color = Color.Black,
                        fontSize = if (compact) 19.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "ALPHA",
                        fontSize = if (compact) 20.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = UltraTokens.Fg,
                    )
                    Text("HOSTING TV", fontSize = 9.sp, letterSpacing = 1.2.sp, color = UltraTokens.Fg3)
                }
            }

            Spacer(Modifier.height(if (compact) 26.dp else 72.dp))

            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp),
            ) {
                Text(
                    "SIGN IN",
                    color = UltraTokens.Accent,
                    fontSize = 11.sp,
                    letterSpacing = 2.3.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Alpha Hosting TV",
                    fontFamily = UltraFonts.Serif,
                    fontSize = if (compact) 38.sp else 58.sp,
                    lineHeight = if (compact) 40.sp else 58.sp,
                    color = UltraTokens.Fg,
                )
                Text(
                    "Enter the username and password you were given. The server is already configured.",
                    color = UltraTokens.Fg2,
                    fontSize = if (compact) 15.sp else 18.sp,
                    lineHeight = if (compact) 21.sp else 25.sp,
                )
                FormField(S.fieldUsername, user, { user = it }, autoFocus = !compact)
                FormField(S.fieldPassword, pass, { pass = it }, password = true)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (canSubmit) UltraTokens.CtaBg else UltraTokens.SurfaceStrong)
                        .clickable(enabled = canSubmit) { vm.addAlphaLogin(user, pass) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (syncing) "Logging in..." else "Log in and load channels",
                        color = if (canSubmit) UltraTokens.CtaFgOnCta else UltraTokens.Fg3,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                message?.let {
                    Text(
                        it,
                        color = if (
                            it.startsWith("Could not") ||
                                it.startsWith("Login failed") ||
                                it.startsWith("Could not reach") ||
                                it.startsWith("Login timed out")
                        ) {
                            UltraTokens.Warn
                        } else {
                            UltraTokens.Fg3
                        },
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}
