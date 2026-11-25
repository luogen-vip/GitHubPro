package com.luogen.ghpro.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.User
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private class DeviceState(
    val deviceCode: String,
    val userCode: String,
    val uri: String,
    val interval: Int
)

@Composable
fun LoginScreen(onLoggedIn: (User) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var cid by remember { mutableStateOf(Cfg.oauthClientId ?: "") }
    var device by remember { mutableStateOf<DeviceState?>(null) }
    var polling by remember { mutableStateOf(false) }

    fun finish(t: String) {
        scope.launch {
            busy = true; err = null; status = "正在校验令牌…"
            try {
                val u = Api.checkToken(t)
                Api.token = t
                Cfg.token = t
                Cfg.login = u.login
                Cfg.userName = u.name
                Cfg.avatar = u.avatar_url
                onLoggedIn(u)
            } catch (e: Exception) {
                err = e.message
                status = ""
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Text("GitHub Pro", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            "全功能 GitHub 客户端 · 仓库 / 代码 / 议题 / 拉取请求 / 工作流 / 发行版 / 讨论",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))

        Text("方式一：访问令牌登录（推荐）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Personal Access Token") },
            placeholder = { Text("ghp_… 或 github_pat_…") },
            singleLine = true,
            visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            leadingIcon = { Icon(Icons.Filled.Lock, null) },
            trailingIcon = {
                IconButton(onClick = { showPwd = !showPwd }) {
                    Icon(if (showPwd) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                }
            }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "需要勾选 repo、workflow、notifications、user、delete_repo、write:discussion 权限",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { finish(token) },
            enabled = !busy && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("登录")
        }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, fontSize = 12.sp, color = GH.muted)
        }
        err?.let {
            Spacer(Modifier.height(8.dp))
            Text("失败：$it", fontSize = 12.sp, color = GH.red)
        }

        Spacer(Modifier.height(26.dp))
        Divider1()
        Spacer(Modifier.height(16.dp))

        Text("方式二：网页授权登录（OAuth Device Flow）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "在 GitHub → Settings → Developer settings → OAuth Apps 新建一个应用并勾选 Enable Device Flow，把它的 Client ID 填在下面（不需要 Client Secret）。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = cid,
            onValueChange = { cid = it.trim(); Cfg.oauthClientId = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OAuth App Client ID") },
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    err = null
                    try {
                        val j = Api.startDeviceFlow(cid)
                        if (j.has("error")) { err = j.get("error").asString; return@launch }
                        val d = DeviceState(
                            deviceCode = j.get("device_code").asString,
                            userCode = j.get("user_code").asString,
                            uri = j.get("verification_uri").asString,
                            interval = j.get("interval")?.asInt ?: 5
                        )
                        device = d
                        polling = true
                        openUrl(ctx, d.uri)
                    } catch (e: Exception) { err = e.message }
                }
            },
            enabled = cid.isNotBlank() && !polling && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (polling) "等待网页授权…" else "获取设备码并打开授权页") }

        device?.let { d ->
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Text("在网页里输入这个代码：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.userCode, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, color = GH.green, letterSpacing = 2.sp)
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("code", d.userCode))
                    }) { Icon(Icons.Filled.ContentCopy, null, tint = GH.blue) }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { openUrl(ctx, d.uri) }) {
                        Icon(Icons.Filled.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("打开 ${d.uri}", fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { polling = false; device = null }) { Text("取消") }
                }
            }
        }

        if (polling && device != null) {
            LaunchedEffect(device) {
                var interval = device!!.interval
                while (isActive && polling) {
                    delay(interval * 1000L)
                    if (!polling) break
                    try {
                        val j: JsonObject = Api.pollDeviceFlow(cid, device!!.deviceCode)
                        when {
                            j.has("access_token") -> {
                                polling = false
                                finish(j.get("access_token").asString)
                                break
                            }
                            j.get("error")?.asString == "slow_down" -> interval += 5
                            j.get("error")?.asString == "authorization_pending" -> { }
                            else -> {
                                err = j.get("error_description")?.asString ?: j.get("error")?.asString
                                polling = false
                            }
                        }
                    } catch (e: Exception) { err = e.message }
                }
            }
        }

        Spacer(Modifier.height(30.dp))
        Text(
            "提示：令牌只保存在本机（应用私有目录），不会上传到任何服务器；加速镜像只用于下载文件，不会接触你的令牌。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
