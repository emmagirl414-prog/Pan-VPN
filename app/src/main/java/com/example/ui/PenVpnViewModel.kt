package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.ConnectionLogEntity
import com.example.data.local.PenVpnDatabase
import com.example.data.local.RegisteredDeviceEntity
import com.example.data.local.UserAccountEntity
import com.example.data.local.VpnServerEntity
import com.example.data.preferences.PenVpnPreferences
import com.example.data.preferences.VpnSettingsState
import com.example.data.repository.PenVpnRepository
import com.example.data.repository.VpnDomainException
import com.example.security.CryptoKeyManager
import com.example.vpn.PenVpnService
import com.example.vpn.TunnelStatus
import com.example.vpn.VpnConnectionTelemetry
import com.example.vpn.VpnErrorType
import com.example.vpn.WireGuardVpnServiceWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PenVpnDestination {
    HOME,
    SERVERS,
    PREMIUM,
    ACCOUNT,
    ADMIN,
    SETTINGS
}

data class ServerFilterState(
    val searchQuery: String = "",
    val selectedRegion: String = "All",
    val sortByLatency: Boolean = true
)

class PenVpnViewModel(
    application: Application,
    val repository: PenVpnRepository,
    val preferences: PenVpnPreferences,
    val vpnWrapper: WireGuardVpnServiceWrapper = WireGuardVpnServiceWrapper(application)
) : AndroidViewModel(application) {

    private val _currentDestination = MutableStateFlow(PenVpnDestination.HOME)
    val currentDestination: StateFlow<PenVpnDestination> = _currentDestination.asStateFlow()

    private val _navigationStack = MutableStateFlow(listOf(PenVpnDestination.HOME))

    private val _filterState = MutableStateFlow(ServerFilterState())
    val filterState: StateFlow<ServerFilterState> = _filterState.asStateFlow()

    private val _uiBannerMessage = MutableStateFlow<String?>(null)
    val uiBannerMessage: StateFlow<String?> = _uiBannerMessage.asStateFlow()

    private val _authMessage = MutableStateFlow<String?>(null)
    val authMessage: StateFlow<String?> = _authMessage.asStateFlow()

    private val _clientWireGuardPubKey = MutableStateFlow("")
    val clientWireGuardPubKey: StateFlow<String> = _clientWireGuardPubKey.asStateFlow()

    val settingsState: StateFlow<VpnSettingsState> = preferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VpnSettingsState())

    val allServers: StateFlow<List<VpnServerEntity>> = repository.allServersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredServers: StateFlow<List<VpnServerEntity>> = combine(
        allServers,
        _filterState
    ) { servers, filter ->
        servers.filter { server ->
            val matchesQuery = filter.searchQuery.isBlank() ||
                server.countryName.contains(filter.searchQuery, ignoreCase = true) ||
                server.city.contains(filter.searchQuery, ignoreCase = true) ||
                server.countryCode.contains(filter.searchQuery, ignoreCase = true)

            val matchesRegion = when (filter.selectedRegion) {
                "All" -> true
                "Recommended" -> server.isRecommended
                "Free" -> !server.isPremiumOnly
                else -> server.region.equals(filter.selectedRegion, ignoreCase = true)
            }
            matchesQuery && matchesRegion
        }.let { list ->
            if (filter.sortByLatency) {
                list.sortedWith(compareBy<VpnServerEntity> { !it.isOnline }.thenBy { it.pingMs })
            } else {
                list.sortedBy { it.countryName }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentUser: StateFlow<UserAccountEntity?> = settingsState
        .flatMapLatest { settings ->
            if (settings.loggedInUserId > 0L) {
                repository.observeUserById(settings.loggedInUserId)
            } else {
                flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val userDevices: StateFlow<List<RegisteredDeviceEntity>> = currentUser
        .flatMapLatest { user ->
            if (user != null) {
                repository.observeDevicesForUser(user.email)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allUsersAdmin: StateFlow<List<UserAccountEntity>> = repository.allUsersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentLogs: StateFlow<List<ConnectionLogEntity>> = repository.recentLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedServer: StateFlow<VpnServerEntity?> = combine(
        allServers,
        settingsState,
        currentUser
    ) { servers, settings, user ->
        if (servers.isEmpty()) return@combine null
        if (settings.useFastestServer) {
            val isPremium = user?.isPremium == true
            servers.filter { it.isOnline && (isPremium || !it.isPremiumOnly) }
                .minByOrNull { (it.pingMs * 0.65) + (it.loadPercent * 0.35) }
                ?: servers.firstOrNull()
        } else {
            servers.find { it.id == settings.selectedServerId } ?: servers.firstOrNull()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val telemetryState: StateFlow<VpnConnectionTelemetry> = vpnWrapper.telemetryState

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
            val (pubKey, _) = CryptoKeyManager.generateWireGuardKeyPair(getApplication())
            _clientWireGuardPubKey.value = pubKey
        }
    }

    fun navigateTo(destination: PenVpnDestination) {
        if (_currentDestination.value != destination) {
            _currentDestination.value = destination
            _navigationStack.update { (it + destination).takeLast(6) }
        }
    }

    fun navigateBack(): Boolean {
        val stack = _navigationStack.value
        return if (stack.size > 1) {
            val newStack = stack.dropLast(1)
            _navigationStack.value = newStack
            _currentDestination.value = newStack.last()
            true
        } else if (_currentDestination.value != PenVpnDestination.HOME) {
            _currentDestination.value = PenVpnDestination.HOME
            _navigationStack.value = listOf(PenVpnDestination.HOME)
            true
        } else {
            false
        }
    }

    fun updateSearchQuery(query: String) {
        _filterState.update { it.copy(searchQuery = query) }
    }

    fun updateRegionFilter(region: String) {
        _filterState.update { it.copy(selectedRegion = region) }
    }

    fun selectServer(server: VpnServerEntity, onTriggerConnectPermission: (() -> Unit)? = null) {
        viewModelScope.launch {
            val isPremium = currentUser.value?.isPremium == true
            if (server.isPremiumOnly && !isPremium) {
                _uiBannerMessage.value = "${server.countryName} (${server.city}) is a Premium location. Upgrade to Premium or select a Free location."
                return@launch
            }
            preferences.setSelectedServer(server.id, isFastest = false)
            _uiBannerMessage.value = "Selected ${server.flagEmoji} ${server.countryName} — ${server.city}"
            navigateTo(PenVpnDestination.HOME)
            onTriggerConnectPermission?.invoke()
        }
    }

    fun selectFastestServer() {
        viewModelScope.launch {
            preferences.setUseFastestServer(true)
            val best = repository.findFastestServer(currentUser.value?.isPremium == true)
            if (best != null) {
                preferences.setSelectedServer(best.id, isFastest = true)
                _uiBannerMessage.value = "Smart Selection: ${best.flagEmoji} ${best.countryName} (${best.city} • ${best.pingMs} ms)"
            }
            navigateTo(PenVpnDestination.HOME)
        }
    }

    fun refreshLatencyAndLoad() {
        viewModelScope.launch {
            repository.refreshServerTelemetry()
            _uiBannerMessage.value = "Updated ping latency & load telemetry for all servers"
        }
    }

    fun prepareVpnIntent(context: Context): Intent? {
        return vpnWrapper.prepareVpnPermissionIntent(context)
    }

    fun onVpnPermissionDenied() {
        vpnWrapper.onVpnPermissionDenied()
    }

    fun clearVpnError() {
        vpnWrapper.clearError()
    }

    fun clearBannerMessage() {
        _uiBannerMessage.value = null
    }

    fun clearAuthMessage() {
        _authMessage.value = null
    }

    fun connectVpn(context: Context, forceHeadlessFallback: Boolean = false) {
        viewModelScope.launch {
            val currentSettings = settingsState.first()
            val isPremium = currentUser.value?.isPremium == true
            vpnWrapper.setAutoReconnectEnabled(currentSettings.autoReconnect)

            val configResult = repository.requestWireGuardConfig(
                serverId = currentSettings.selectedServerId,
                useFastest = currentSettings.useFastestServer,
                isPremiumUser = isPremium,
                protocol = currentSettings.selectedProtocol,
                dnsLeakProtection = currentSettings.dnsLeakProtection,
                ipv6LeakProtection = currentSettings.ipv6LeakProtection,
                killSwitch = currentSettings.killSwitch
            )

            configResult.onFailure { throwable ->
                val errorType = (throwable as? VpnDomainException)?.vpnError
                    ?: VpnErrorType.SERVER_UNAVAILABLE
                PenVpnService.updateErrorState(errorType)
                return@launch
            }

            val (config, server) = configResult.getOrNull() ?: return@launch

            vpnWrapper.startTunnel(
                config = config,
                serverLoad = server.loadPercent,
                serverPing = server.pingMs,
                forceHeadlessFallback = forceHeadlessFallback
            )
        }
    }

    fun disconnectVpn(context: Context) {
        viewModelScope.launch {
            val currentTelemetry = telemetryState.value
            val cfg = currentTelemetry.activeConfig
            if (cfg != null && currentTelemetry.status == TunnelStatus.CONNECTED) {
                repository.logCompletedConnection(
                    serverId = cfg.serverId,
                    countryName = cfg.countryName,
                    city = cfg.city,
                    protocol = cfg.protocol,
                    durationSeconds = currentTelemetry.elapsedSeconds,
                    bytesDownloaded = currentTelemetry.totalDownloadedBytes,
                    bytesUploaded = currentTelemetry.totalUploadedBytes
                )
            }
            vpnWrapper.stopTunnel()
        }
    }

    fun rotateClientKeys() {
        viewModelScope.launch {
            val (newPub, _) = CryptoKeyManager.rotateWireGuardKeyPair(getApplication())
            _clientWireGuardPubKey.value = newPub
            _uiBannerMessage.value = "Rotated WireGuard Curve25519 keypair in AndroidKeyStore"
        }
    }

    fun signUp(fullName: String, email: String, password: String) {
        viewModelScope.launch {
            val res = repository.signUp(fullName, email, password)
            res.onSuccess { user ->
                preferences.setLoggedInUserId(user.id)
                _authMessage.value = "Account created! Signed in as ${user.email}"
            }.onFailure { err ->
                _authMessage.value = err.message ?: "Sign up failed"
            }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            val res = repository.login(email, password)
            res.onSuccess { user ->
                preferences.setLoggedInUserId(user.id)
                _authMessage.value = "Welcome back, ${user.fullName}!"
            }.onFailure { err ->
                _authMessage.value = err.message ?: "Login failed"
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            preferences.setLoggedInUserId(-1L)
            _authMessage.value = "Signed out safely. Local session tokens cleared."
        }
    }

    fun resetPassword(email: String, newPassword: String) {
        viewModelScope.launch {
            val res = repository.resetPassword(email, newPassword)
            res.onSuccess {
                _authMessage.value = "Password updated with PBKDF2-HMAC-SHA256 encryption. You may now sign in."
            }.onFailure { err ->
                _authMessage.value = err.message ?: "Password reset failed"
            }
        }
    }

    fun upgradeToPremium(planName: String, durationDays: Int) {
        viewModelScope.launch {
            val user = currentUser.value
            if (user == null) {
                _uiBannerMessage.value = "Please sign in or create an account first to link your Premium subscription."
                navigateTo(PenVpnDestination.ACCOUNT)
                return@launch
            }
            val res = repository.upgradeSubscription(user.id, planName, durationDays)
            res.onSuccess {
                _uiBannerMessage.value = "Pen VPN $planName activated! All 25+ global servers unlocked."
            }.onFailure { err ->
                _uiBannerMessage.value = err.message
            }
        }
    }

    fun cancelPremiumSubscription() {
        viewModelScope.launch {
            val user = currentUser.value ?: return@launch
            repository.downgradeToFree(user.id)
            _uiBannerMessage.value = "Subscription switched to Free plan."
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            repository.removeRegisteredDevice(deviceId)
            _uiBannerMessage.value = "Device revoked from WireGuard key registry."
        }
    }

    fun addAdminServer(
        countryName: String,
        countryCode: String,
        flagEmoji: String,
        city: String,
        region: String,
        isPremiumOnly: Boolean,
        isRecommended: Boolean
    ) {
        viewModelScope.launch {
            if (countryName.isBlank() || city.isBlank()) {
                _uiBannerMessage.value = "Country name and city are required."
                return@launch
            }
            repository.addAdminServer(
                countryName = countryName,
                countryCode = countryCode.ifBlank { countryName.take(2).uppercase() },
                flagEmoji = flagEmoji.ifBlank { "🌐" },
                city = city,
                region = region,
                isPremiumOnly = isPremiumOnly,
                isRecommended = isRecommended
            )
            _uiBannerMessage.value = "Added $countryName ($city) WireGuard node to server cluster."
        }
    }

    fun toggleServerStatus(server: VpnServerEntity) {
        viewModelScope.launch {
            repository.toggleServerOnlineStatus(server)
        }
    }

    fun deleteAdminServer(serverId: String) {
        viewModelScope.launch {
            repository.deleteServer(serverId)
            _uiBannerMessage.value = "Removed server $serverId from cluster."
        }
    }

    fun toggleUserPremiumStatusAdmin(user: UserAccountEntity) {
        viewModelScope.launch {
            repository.toggleUserPremiumAdmin(user)
        }
    }

    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch { preferences.setAutoConnect(enabled) }
    fun setKillSwitch(enabled: Boolean) = viewModelScope.launch { preferences.setKillSwitch(enabled) }
    fun setDnsLeakProtection(enabled: Boolean) = viewModelScope.launch { preferences.setDnsLeakProtection(enabled) }
    fun setIpv6LeakProtection(enabled: Boolean) = viewModelScope.launch { preferences.setIpv6LeakProtection(enabled) }
    fun setAutoReconnect(enabled: Boolean) = viewModelScope.launch {
        preferences.setAutoReconnect(enabled)
        vpnWrapper.setAutoReconnectEnabled(enabled)
    }
    fun setUseFastestServer(enabled: Boolean) = viewModelScope.launch { preferences.setUseFastestServer(enabled) }
    fun setProtocol(protocol: String) = viewModelScope.launch { preferences.setProtocol(protocol) }
    fun setConnectOnWifi(enabled: Boolean) = viewModelScope.launch { preferences.setConnectOnWifi(enabled) }
    fun setConnectOnMobileData(enabled: Boolean) = viewModelScope.launch { preferences.setConnectOnMobileData(enabled) }
    fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setNotificationsEnabled(enabled) }
    fun setLanguage(language: String) = viewModelScope.launch { preferences.setLanguage(language) }
    fun setDarkMode(darkMode: Boolean) = viewModelScope.launch { preferences.setDarkMode(darkMode) }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = PenVpnDatabase.getInstance(application)
            val repo = PenVpnRepository(application, db.penVpnDao())
            val prefs = PenVpnPreferences(application)
            val wrapper = WireGuardVpnServiceWrapper(application)
            return PenVpnViewModel(application, repo, prefs, wrapper) as T
        }
    }
}
