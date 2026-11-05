package com.infoagent.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.infoagent.capture.NotificationAccess
import com.infoagent.data.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * 首启向导（§5）：欢迎 → 通知使用权 → API 设置 → 完成。
 * 完成状态记 DataStore，之后不再出现。
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    var step by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    var baseUrl by remember { mutableStateOf("https://api.deepseek.com/v1") }
    var model by remember { mutableStateOf("deepseek-chat") }
    var apiKey by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("信息小agent", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("第 ${step + 1} / 4 步", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        when (step) {
            0 -> StepWelcome()
            1 -> StepPermission(granted) { NotificationAccess.openSettings(context) }
            2 -> StepApi(
                baseUrl = baseUrl,
                model = model,
                apiKey = apiKey,
                error = saveError,
                onBaseUrl = { baseUrl = it },
                onModel = { model = it },
                onKey = { apiKey = it },
                onSave = {
                    scope.launch {
                        saveError = settings.saveApi(baseUrl, "/chat/completions", model, 0.3, 2048, apiKey)
                        if (saveError == null) step = 3
                    }
                },
            )
            else -> StepDone()
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("上一步") }
            }
            when (step) {
                1 -> Button(onClick = {
                    granted = NotificationAccess.isGranted(context)
                    step = if (granted) 2 else 1
                }, modifier = Modifier.weight(1f)) { Text(if (granted) "已开启，下一步" else "下一步") }
                3 -> Button(onClick = {
                    scope.launch {
                        settings.markOnboarded()
                        onDone()
                    }
                }, modifier = Modifier.weight(1f)) { Text("开始使用") }
                else -> Button(onClick = { step++ }, modifier = Modifier.weight(1f)) { Text("下一步") }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (step == 1 && !granted) {
            TextButton(onClick = { granted = NotificationAccess.isGranted(context) }) { Text("我已开启，刷新状态") }
            Text(
                "没有通知使用权也能用：消息页可手动导入聊天记录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "数据只存在本机；调用你配置的 API 时才会发送必要正文。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepWelcome() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.height(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            "把微信里散落的消息、你自己的每日必做与临时事件，\n交给 Agent 整理成早/午/晚三段可执行简报。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "· 消息静默入库，不打扰你\n· AI 自动把「有事情」的消息整理成待办\n· 关键时间准点提醒，尽量进系统灵动岛",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StepPermission(granted: Boolean, onOpen: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.NotificationsActive, contentDescription = null, modifier = Modifier.height(40.dp))
            Spacer(Modifier.height(10.dp))
            Text("开启通知使用权", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "在系统设置里找到「信息小agent」并允许读取通知。\n我们只读取微信/QQ 的通知文本，用于入库与整理。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("已开启", color = MaterialTheme.colorScheme.primary)
                }
            } else {
                OutlinedButton(onClick = onOpen) { Text("去系统设置开启") }
            }
        }
    }
}

@Composable
private fun StepApi(
    baseUrl: String,
    model: String,
    apiKey: String,
    error: String?,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onKey: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("接入 Agent 的 API（OpenAI 兼容）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Key 用系统 Keystore 加密保存在本机，不会上传。", style = MaterialTheme.typography.labelSmall)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrl,
            label = { Text("接口地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        OutlinedTextField(
            value = model,
            onValueChange = onModel,
            label = { Text("模型名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onKey,
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("保存并继续") }
        Text(
            "也可以先跳过，稍后在 Agent 页「设置」里填。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun StepDone() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.height(40.dp))
        Spacer(Modifier.height(10.dp))
        Text("就绪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "默认每天 10:00 / 13:30 / 22:00 自动生成简报；\n消息页可手动导入、备忘页可随时记一笔。",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}