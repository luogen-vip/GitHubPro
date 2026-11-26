package com.luogen.ghpro.ui

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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

/** 自定义输入框：完全按 GitHub 深浅色规范绘制，避免默认 Material 样式和应用内部不搭。 */
@Composable
private fun GhField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leading: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    singleLine: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
        cursorBrush = SolidColor(GH.blue),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    ) { inner ->
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, if (focused) GH.blue else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                Icon(leading, null, Modifier.size(17.dp), tint = if (focused) GH.blue else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            }
            if (onTogglePassword != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(CircleShape).clickable { onTogglePassword() }.padding(4.dp)
                ) {
                    Icon(
                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        "显示或隐藏", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FeaturePill(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) GH.blue else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (enabled) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)))
            .clickable(enabled = enabled && !busy) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
        else Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LoginScreen(onLoggedIn: (User) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
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
                status = ""
                onLoggedIn(u)
            } catch (e: Exception) {
                err = e.message
                status = ""
            } finally { busy = false }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0D12), MaterialTheme.colorScheme.background, Color(0xFF0A0F16))))
    ) {
        // 右上角淡淡的品牌光晕，让整页不那么“死板”
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(GH.blue.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.86f, size.height * 0.04f),
                    radius = size.width * 0.9f
                ),
                radius = size.width * 0.9f,
                center = Offset(size.width * 0.86f, size.height * 0.04f)
            )
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
        ) {
            Spacer(Modifier.height(58.dp))

            // 品牌区
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(58.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(GH.blue, GH.purple)))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Code, null, Modifier.size(30.dp), tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("GitHub Pro", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text("罗根版 · 客户端", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(18.dp))
            Row {
                FeaturePill("仓库与代码")
                Spacer(Modifier.width(8.dp))
                FeaturePill("工作流")
                Spacer(Modifier.width(8.dp))
                FeaturePill("议题与 PR")
            }
            Spacer(Modifier.height(8.dp))
            Row {
                FeaturePill("发行版")
                Spacer(Modifier.width(8.dp))
                FeaturePill("讨论")
                Spacer(Modifier.width(8.dp))
                FeaturePill("通知")
            }

            Spacer(Modifier.height(26.dp))

            // 分段切换
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(4.dp)
            ) {
                listOf("访问令牌", "网页授权").forEachIndexed { i, label ->
                    val on = tab == i
                    Box(
                        Modifier.weight(1f).height(38.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (on) GH.blue else Color.Transparent)
                            .clickable {
                                tab = i
                                err = null
                                if (i == 1) status = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 内容卡片
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                if (tab == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Key, null, Modifier.size(15.dp), tint = GH.blue)
                        Spacer(Modifier.width(6.dp))
                        Text("个人访问令牌登录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text("推荐", fontSize = 10.sp, color = GH.green)
                    }
                    Spacer(Modifier.height(12.dp))
                    GhField(
                        value = token,
                        onValueChange = { token = it.trim() },
                        placeholder = "ghp_… 或 github_pat_…",
                        leading = Icons.Filled.Lock,
                        keyboardType = KeyboardType.Password,
                        password = true,
                        showPassword = showPwd,
                        onTogglePassword = { showPwd = !showPwd }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已预置 repo / workflow / notifications / user / delete_repo / write:discussion 权限",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    openUrl(ctx, "https://github.com/settings/tokens/new?scopes=repo,workflow,notifications,user,delete_repo,write:discussion&description=GitHub%20Pro")
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.OpenInNew, null, Modifier.size(13.dp), tint = GH.blue)
                                Spacer(Modifier.width(5.dp))
                                Text("去 GitHub 生成令牌", fontSize = 12.sp, color = GH.blue)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (token.isNotBlank()) {
                            Icon(Icons.Filled.Check, null, Modifier.size(13.dp), tint = GH.green)
                            Spacer(Modifier.width(4.dp))
                            Text("已填写", fontSize = 11.sp, color = GH.green)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("登录", token.isNotBlank(), busy) { finish(token) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Key, null, Modifier.size(15.dp), tint = GH.purple)
                        Spacer(Modifier.width(6.dp))
                        Text("网页授权登录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "在 GitHub 创建一个 OAuth App 并开启 Enable Device Flow，把 Client ID 填进来即可（不需要 Client Secret）。适合不想手动复制令牌的情况。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    GhField(
                        value = cid,
                        onValueChange = { cid = it.trim(); Cfg.oauthClientId = it.trim() },
                        placeholder = "OAuth App Client ID",
                        keyboardType = KeyboardType.Text
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier.fillMaxWidth().height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (cid.isNotBlank() && !polling && !busy) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .border(1.dp, if (cid.isNotBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable(enabled = cid.isNotBlank() && !polling && !busy) {
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
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (polling) "等待网页授权…" else "获取设备码并打开授权页",
                            fontSize = 13.sp,
                            color = if (cid.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    device?.let { d ->
                        Spacer(Modifier.height(14.dp))
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Text("在网页里输入这个代码", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    d.userCode, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace, color = GH.blue, letterSpacing = 3.sp
                                )
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = {
                                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    cm.setPrimaryClip(ClipData.newPlainText("code", d.userCode))
                                }) { Icon(Icons.Filled.ContentCopy, "复制", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "打开授权页", fontSize = 12.sp, color = GH.blue,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                        .clickable { openUrl(ctx, d.uri) }
                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "取消", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                        .clickable { polling = false; device = null }
                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                )
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
                }
            }

            // 状态 / 错误
            if (status.isNotEmpty() || err != null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (err != null) GH.red.copy(alpha = 0.10f) else GH.green.copy(alpha = 0.10f))
                        .border(1.dp, if (err != null) GH.red.copy(alpha = 0.45f) else GH.green.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        err ?: status, fontSize = 12.sp,
                        color = if (err != null) GH.red else GH.green, lineHeight = 17.sp, modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    "令牌仅保存在本机应用私有目录，不会上传；加速镜像只用于下载文件。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("v1.0.2 · 让 GitHub 尽在掌握", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(36.dp))
        }
    }
}
