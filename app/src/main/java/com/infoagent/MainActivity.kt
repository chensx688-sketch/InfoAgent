package com.infoagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.animation.togetherWith
import androidx.lifecycle.lifecycleScope
import com.infoagent.ui.theme.InfoAgentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** B7：通知深链目标页（0 消息 / 1 备忘 / 2 Agent） */
    private val deepLinkPage = androidx.compose.runtime.mutableIntStateOf(0)

    private fun pageOf(intent: Intent?): Int = when (intent?.getStringExtra("open")) {
        "agent" -> 2
        "memo" -> 1
        "settings" -> 3
        "messages" -> 0
        else -> 0
    }

    /** 简报通知带 slice_id → Agent 页滚到该片段 */
    private fun captureSliceDeepLink(intent: Intent?) {
        if (intent?.getStringExtra("open") != "agent") return
        val sliceId = intent.getLongExtra("entity_id", 0L)
        if (sliceId > 0) com.infoagent.ui.agent.AgentDeepLink.sliceId = sliceId
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkPage.intValue = pageOf(intent)
        captureSliceDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        // A9：回前台通知备忘页重算日期（跨 0 点场景）
        com.infoagent.data.util.DayTicker.bump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkPage.intValue = pageOf(intent)
        captureSliceDeepLink(intent)
        // 联调入口（仅 DEBUG）：发一条带岛装饰的通知，验证不会出现第二条
        if (com.infoagent.BuildConfig.DEBUG && intent?.getBooleanExtra("island_test", false) == true) {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = com.infoagent.data.db.InfoAgentDatabase.get(applicationContext)
                val factory = com.infoagent.island.NotificationFactory(applicationContext, db.scheduleDao())
                val (id, builder) = factory.buildReminder(0L, "系统岛测试：由系统通知展示", "测试")
                com.infoagent.island.IslandRouter.decorate(
                    applicationContext,
                    builder,
                    com.infoagent.island.IslandPayload(
                        key = "island-test",
                        title = "☀ 信息小agent",
                        content = "系统岛测试：由系统通知展示",
                        progress = 40,
                        deepLink = com.infoagent.island.IslandIntents.openApp(applicationContext, "agent"),
                    ),
                )
                androidx.core.app.NotificationManagerCompat.from(applicationContext)
                    .notify(id, builder.build())
            }
        }
        setContent {
            InfoAgentTheme {
                val context = LocalContext.current
                val settings = androidx.compose.runtime.remember {
                    com.infoagent.data.settings.SettingsRepository(context)
                }
                var onboarded by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<Boolean?>(null)
                }
                LaunchedEffect(Unit) { onboarded = settings.onboarded() }
                when (onboarded) {
                    null -> Box(Modifier.fillMaxSize()) {}
                    false -> com.infoagent.ui.onboarding.OnboardingScreen(onDone = { onboarded = true })
                    else -> {
                        RequestNotificationPermission()
                        InfoAgentAppUi(initialPage = deepLinkPage.intValue)
                    }
                }
            }
        }
    }
}

/** Android 13+ 通知权限：首屏请求一次 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private data class NavItem(val titleRes: Int, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoAgentAppUi(initialPage: Int = 0) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableIntStateOf(initialPage) }
    // B7：点通知带的 open=agent/memo/messages 落到对应页
    androidx.compose.runtime.LaunchedEffect(initialPage) {
        if (initialPage in 0..2) selected = initialPage
    }

    val navItems = listOf(
        NavItem(R.string.nav_messages, Icons.Outlined.ChatBubbleOutline),
        NavItem(R.string.nav_memos, Icons.Outlined.EditNote),
        NavItem(R.string.nav_agent, Icons.Outlined.AutoAwesome),
        NavItem(R.string.nav_settings, Icons.Outlined.Settings),
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                navItems.forEachIndexed { index, item ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(item.titleRes)) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        selected = selected == index,
                        onClick = {
                            selected = index
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(navItems[selected].titleRes)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Outlined.Menu,
                                contentDescription = stringResource(R.string.menu),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                androidx.compose.animation.AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                            androidx.compose.animation.slideInHorizontally(
                                animationSpec = androidx.compose.animation.core.tween(260),
                                initialOffsetX = { it / 14 },
                            )) togetherWith
                            (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) +
                                androidx.compose.animation.slideOutHorizontally(
                                    animationSpec = androidx.compose.animation.core.tween(200),
                                    targetOffsetX = { -it / 20 },
                                ))
                    },
                    label = "page",
                ) { page ->
                    when (page) {
                        0 -> com.infoagent.ui.messages.MessagesScreen()
                        1 -> com.infoagent.ui.memos.MemosScreen()
                        2 -> com.infoagent.ui.agent.AgentScreen(
                            onOpenSettings = { selected = 3 },
                        )
                        3 -> com.infoagent.ui.agent.SettingsScreen()
                        else -> Box(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "「${stringResource(navItems[page].titleRes)}」页面 — 占位",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}