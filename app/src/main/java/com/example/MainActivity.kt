package com.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.PenVpnDestination
import com.example.ui.PenVpnViewModel
import com.example.ui.screens.AccountScreen
import com.example.ui.screens.AdminDashboardScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PremiumScreen
import com.example.ui.screens.ServerLocationsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.PenVpnTheme
import com.example.vpn.TunnelStatus
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vpnViewModel: PenVpnViewModel by viewModels {
        PenVpnViewModel.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vpnViewModel.settingsState.collectAsStateWithLifecycle()

            PenVpnTheme(darkTheme = settings.darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PenVpnAppRoot(viewModel = vpnViewModel)
                }
            }
        }
    }
}

private data class NavTabItem(
    val destination: PenVpnDestination,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

@Composable
fun PenVpnAppRoot(viewModel: PenVpnViewModel) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isExpandedScreen = configuration.screenWidthDp >= 600

    val destination by viewModel.currentDestination.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val filteredServers by viewModel.filteredServers.collectAsStateWithLifecycle()
    val allServers by viewModel.allServers.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val userDevices by viewModel.userDevices.collectAsStateWithLifecycle()
    val allUsers by viewModel.allUsersAdmin.collectAsStateWithLifecycle()
    val clientPubKey by viewModel.clientWireGuardPubKey.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.uiBannerMessage.collectAsStateWithLifecycle()
    val authMessage by viewModel.authMessage.collectAsStateWithLifecycle()

    // Android OS VpnService Permission Launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectVpn(context)
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    val proceedWithVpnPermissionAndConnect = {
        val prepareIntent = viewModel.prepareVpnIntent(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.connectVpn(context)
        }
    }

    // Android 13+ (API 33+) Runtime Notification Permission Launcher for Foreground Service Notification
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        proceedWithVpnPermissionAndConnect()
    }

    val triggerVpnConnectFlow = {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            settings.notificationsEnabled &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            proceedWithVpnPermissionAndConnect()
        }
    }

    val handleConnectToggle = {
        if (telemetry.status == TunnelStatus.CONNECTED || telemetry.status == TunnelStatus.CONNECTING) {
            viewModel.disconnectVpn(context)
        } else {
            triggerVpnConnectFlow()
        }
    }

    // Auto-dismiss transient toast/banner after 4 seconds
    LaunchedEffect(bannerMessage) {
        if (bannerMessage != null) {
            delay(4000)
            viewModel.clearBannerMessage()
        }
    }

    val navItems = listOf(
        NavTabItem(PenVpnDestination.HOME, "VPN", Icons.Default.Home, "nav_tab_home"),
        NavTabItem(PenVpnDestination.SERVERS, "Servers", Icons.Default.Public, "nav_tab_servers"),
        NavTabItem(PenVpnDestination.PREMIUM, "Premium", Icons.Default.WorkspacePremium, "nav_tab_premium"),
        NavTabItem(PenVpnDestination.ACCOUNT, "Account", Icons.Default.Person, "nav_tab_account"),
        NavTabItem(PenVpnDestination.ADMIN, "Admin", Icons.Default.AdminPanelSettings, "nav_tab_admin"),
        NavTabItem(PenVpnDestination.SETTINGS, "Settings", Icons.Default.Settings, "nav_tab_settings")
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!isExpandedScreen) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    navItems.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item.destination,
                            onClick = { viewModel.navigateTo(item.destination) },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag(item.testTag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isExpandedScreen) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    navItems.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item.destination,
                            onClick = { viewModel.navigateTo(item.destination) },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            modifier = Modifier.testTag(item.testTag)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (destination) {
                    PenVpnDestination.HOME -> HomeScreen(
                        telemetry = telemetry,
                        selectedServer = selectedServer,
                        settings = settings,
                        isPremiumUser = currentUser?.isPremium == true,
                        onConnectToggle = handleConnectToggle,
                        onOpenServerList = { viewModel.navigateTo(PenVpnDestination.SERVERS) },
                        onSelectFastest = { viewModel.selectFastestServer() },
                        onRetryConnection = triggerVpnConnectFlow,
                        onDismissError = { viewModel.clearVpnError() }
                    )

                    PenVpnDestination.SERVERS -> ServerLocationsScreen(
                        servers = filteredServers,
                        selectedServerId = settings.selectedServerId,
                        useFastestServer = settings.useFastestServer,
                        filterState = filterState,
                        isPremiumUser = currentUser?.isPremium == true,
                        onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                        onRegionSelect = { viewModel.updateRegionFilter(it) },
                        onSelectFastest = { viewModel.selectFastestServer() },
                        onSelectServer = { server -> viewModel.selectServer(server) },
                        onRefreshTelemetry = { viewModel.refreshLatencyAndLoad() },
                        onBack = { viewModel.navigateBack() }
                    )

                    PenVpnDestination.PREMIUM -> PremiumScreen(
                        currentUser = currentUser,
                        onActivatePlan = { planName, days -> viewModel.upgradeToPremium(planName, days) },
                        onCancelSubscription = { viewModel.cancelPremiumSubscription() },
                        onBack = { viewModel.navigateBack() }
                    )

                    PenVpnDestination.ACCOUNT -> AccountScreen(
                        currentUser = currentUser,
                        registeredDevices = userDevices,
                        clientPublicKey = clientPubKey,
                        authMessage = authMessage,
                        onLogin = { email, pass -> viewModel.login(email, pass) },
                        onSignUp = { name, email, pass -> viewModel.signUp(name, email, pass) },
                        onResetPassword = { email, newPass -> viewModel.resetPassword(email, newPass) },
                        onLogout = { viewModel.logout() },
                        onRotateKeys = { viewModel.rotateClientKeys() },
                        onRemoveDevice = { devId -> viewModel.removeDevice(devId) },
                        onOpenPremium = { viewModel.navigateTo(PenVpnDestination.PREMIUM) },
                        onOpenAdminDashboard = { viewModel.navigateTo(PenVpnDestination.ADMIN) },
                        onClearAuthMessage = { viewModel.clearAuthMessage() },
                        onBack = { viewModel.navigateBack() }
                    )

                    PenVpnDestination.ADMIN -> AdminDashboardScreen(
                        servers = allServers,
                        users = allUsers,
                        currentUser = currentUser,
                        onAddServer = { country, code, flag, city, region, isPro, isRec ->
                            viewModel.addAdminServer(country, code, flag, city, region, isPro, isRec)
                        },
                        onToggleServerStatus = { server -> viewModel.toggleServerStatus(server) },
                        onDeleteServer = { serverId -> viewModel.deleteAdminServer(serverId) },
                        onToggleUserPremium = { user -> viewModel.toggleUserPremiumStatusAdmin(user) },
                        onRefreshTelemetry = { viewModel.refreshLatencyAndLoad() },
                        onNavigateToAccount = { viewModel.navigateTo(PenVpnDestination.ACCOUNT) },
                        onBack = { viewModel.navigateBack() }
                    )

                    PenVpnDestination.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onAutoConnectChange = { viewModel.setAutoConnect(it) },
                        onKillSwitchChange = { viewModel.setKillSwitch(it) },
                        onDnsLeakChange = { viewModel.setDnsLeakProtection(it) },
                        onIpv6LeakChange = { viewModel.setIpv6LeakProtection(it) },
                        onAutoReconnectChange = { viewModel.setAutoReconnect(it) },
                        onFastestServerChange = { viewModel.setUseFastestServer(it) },
                        onProtocolSelect = { viewModel.setProtocol(it) },
                        onConnectOnWifiChange = { viewModel.setConnectOnWifi(it) },
                        onConnectOnMobileDataChange = { viewModel.setConnectOnMobileData(it) },
                        onNotificationsChange = { viewModel.setNotificationsEnabled(it) },
                        onLanguageSelect = { viewModel.setLanguage(it) },
                        onDarkModeChange = { viewModel.setDarkMode(it) },
                        onBack = { viewModel.navigateBack() }
                    )
                }

                // Floating Status Feedback Banner
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    AnimatedVisibility(visible = bannerMessage != null) {
                        bannerMessage?.let { msg ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { viewModel.clearBannerMessage() }) {
                                    Text("OK", color = ElectricCyan)
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}
