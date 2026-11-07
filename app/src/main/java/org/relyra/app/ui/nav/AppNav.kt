package org.relyra.app.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.ui.components.rememberMessageHost
import org.relyra.app.ui.screens.AddContactScreen
import org.relyra.app.ui.screens.ChatScreen
import org.relyra.app.ui.screens.ChatsScreen
import org.relyra.app.ui.screens.CreateGroupScreen
import org.relyra.app.ui.screens.DiagnosticsScreen
import org.relyra.app.ui.screens.ShareScreen
import org.relyra.app.ui.screens.ToolsScreen
import org.relyra.app.ui.screens.GroupChatScreen
import org.relyra.app.ui.screens.GroupInfoScreen
import org.relyra.app.ui.screens.ContactsScreen
import org.relyra.app.ui.screens.HomeScreen
import org.relyra.app.ui.screens.IdentityScreen
import org.relyra.app.ui.screens.LogScreen
import org.relyra.app.ui.screens.NetworkScreen
import org.relyra.app.ui.screens.RecoveryScreen
import org.relyra.app.ui.screens.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val CHATS = "chats"
    const val CHAT = "chat/{id}"
    const val CONTACTS = "contacts"
    const val IDENTITY = "identity"
    const val SETTINGS = "settings"
    const val ADD = "contacts/add"
    const val RECOVERY = "identity/recovery"
    const val LOG = "settings/log"
    const val NETWORK = "settings/network"
    const val GROUP = "group/{id}"
    const val GROUP_INFO = "group/{id}/info"
    const val GROUP_NEW = "groups/new"
    const val TOOLS = "settings/tools"
    const val DIAG = "settings/diagnostics"
    const val SHARE = "share"
}

private class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Default.Home),
    Tab(Routes.CHATS, R.string.nav_chats, Icons.Default.Email),
    Tab(Routes.CONTACTS, R.string.nav_contacts, Icons.Default.Person),
    Tab(Routes.IDENTITY, R.string.nav_identity, Icons.Default.AccountCircle),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings),
)

@StringRes
private fun titleFor(route: String): Int = when (route) {
    Routes.HOME -> R.string.nav_home
    Routes.CHATS, Routes.CHAT -> R.string.nav_chats
    Routes.GROUP_NEW -> R.string.groups_new_title
    Routes.GROUP_INFO -> R.string.groups_info
    Routes.CONTACTS -> R.string.nav_contacts
    Routes.IDENTITY -> R.string.nav_identity
    Routes.SETTINGS -> R.string.nav_settings
    Routes.ADD -> R.string.contacts_add
    Routes.RECOVERY -> R.string.identity_recovery
    Routes.LOG -> R.string.settings_log
    Routes.NETWORK -> R.string.net_title
    Routes.TOOLS -> R.string.tools_title
    Routes.DIAG -> R.string.diag_title
    Routes.SHARE -> R.string.share_title
    else -> R.string.app_name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(
    vm: MainViewModel,
    authenticate: (title: String, allowIfUnavailable: Boolean, onSuccess: () -> Unit) -> Unit,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.HOME
    val isTab = tabs.any { it.route == route }
    val snackbar = rememberMessageHost(vm)
    val allContacts by vm.contacts.collectAsStateWithLifecycle()
    val allGroups by vm.groups.collectAsStateWithLifecycle()
    val chatName = when (route) {
        Routes.CHAT -> {
            val id = backStack?.arguments?.getString("id")
            allContacts.find { it.publicId == id }?.displayName
        }
        Routes.GROUP -> {
            val id = backStack?.arguments?.getString("id")
            allGroups.find { it.groupId == id }?.name
        }
        else -> null
    }

    val showRecovery by vm.showRecoveryNext.collectAsStateWithLifecycle()
    LaunchedEffect(showRecovery) {
        if (showRecovery) {
            vm.showRecoveryNext.value = false
            nav.navigate(Routes.RECOVERY)
        }
    }
    val incoming by vm.incomingLink.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        if (incoming != null) nav.navigate(Routes.ADD) { launchSingleTop = true }
    }

    val share by vm.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(share) {
        if (share != null) nav.navigate(Routes.SHARE) { launchSingleTop = true }
    }
    val openReq by vm.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openReq) {
        val o = openReq
        if (o != null) {
            vm.openRequest.value = null
            nav.navigate(if (o.group) "group/${o.id}" else "chat/${o.id}") { launchSingleTop = true }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chatName ?: stringResource(titleFor(route))) },
                navigationIcon = {
                    if (!isTab) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (route == Routes.GROUP) {
                        val gid = backStack?.arguments?.getString("id")
                        IconButton(onClick = { nav.navigate("group/$gid/info") }) {
                            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.groups_info))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isTab) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    vm,
                    onOpenNetwork = { nav.navigate(Routes.NETWORK) },
                    onAddContact = { nav.navigate(Routes.ADD) },
                    onOpenTools = { nav.navigate(Routes.TOOLS) },
                    onOpenDiagnostics = { nav.navigate(Routes.DIAG) },
                )
            }
            composable(Routes.CHATS) {
                ChatsScreen(
                    vm,
                    onOpen = { nav.navigate("chat/$it") },
                    onOpenGroup = { nav.navigate("group/$it") },
                    onCreateGroup = { nav.navigate(Routes.GROUP_NEW) },
                    onAddContact = { nav.navigate(Routes.ADD) },
                )
            }
            composable(Routes.GROUP) { entry -> GroupChatScreen(vm, entry.arguments?.getString("id") ?: "") }
            composable(Routes.GROUP_INFO) { entry ->
                GroupInfoScreen(vm, entry.arguments?.getString("id") ?: "", onGone = {
                    nav.popBackStack(Routes.CHATS, inclusive = false)
                })
            }
            composable(Routes.GROUP_NEW) {
                CreateGroupScreen(vm, onCreated = { id ->
                    nav.popBackStack()
                    nav.navigate("group/$id")
                }, onAddContact = { nav.navigate(Routes.ADD) })
            }
            composable(Routes.CHAT) { entry -> ChatScreen(vm, entry.arguments?.getString("id") ?: "") }
            composable(Routes.CONTACTS) { ContactsScreen(vm, onAdd = { nav.navigate(Routes.ADD) }) }
            composable(Routes.IDENTITY) { IdentityScreen(vm, onRecovery = { nav.navigate(Routes.RECOVERY) }) }
            composable(Routes.SETTINGS) { SettingsScreen(vm, authenticate, onOpenLog = { nav.navigate(Routes.LOG) }, onOpenNetwork = { nav.navigate(Routes.NETWORK) }, onOpenTools = { nav.navigate(Routes.TOOLS) }, onOpenDiagnostics = { nav.navigate(Routes.DIAG) }) }
            composable(Routes.ADD) { AddContactScreen(vm, onDone = { nav.popBackStack() }) }
            composable(Routes.RECOVERY) { RecoveryScreen(vm, authenticate, onDone = { nav.popBackStack() }) }
            composable(Routes.LOG) { LogScreen(vm) }
            composable(Routes.NETWORK) { NetworkScreen(vm) }
            composable(Routes.TOOLS) { ToolsScreen(vm) }
            composable(Routes.DIAG) { DiagnosticsScreen(vm) }
            composable(Routes.SHARE) {
                ShareScreen(vm, onDone = { id ->
                    nav.popBackStack()
                    if (id != null) nav.navigate("chat/$id")
                })
            }
        }
    }
}
