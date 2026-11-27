package com.luogen.ghpro.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luogen.ghpro.data.NetChoice
import com.luogen.ghpro.data.NetPrompt
import com.luogen.ghpro.data.NetPromptKind
import com.luogen.ghpro.data.NetPrompts

@Composable
fun NetworkPromptHost(onCancelDownload: (String) -> Unit) {
    val prompt by NetPrompts.current.collectAsState()
    prompt?.let { p ->
        var dontAsk by remember(p.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { /* 必须明确选一个，避免下载一直悬着 */ },
            title = {
                Text(
                    when (p.kind) {
                        NetPromptKind.DOWNLOAD_MOBILE -> "正在使用移动数据下载"
                        NetPromptKind.BROWSE_MOBILE -> "正在使用移动数据"
                        NetPromptKind.NO_NETWORK -> "暂时不能下载"
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    when (p.kind) {
                        NetPromptKind.DOWNLOAD_MOBILE -> {
                            Text(p.fileName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (p.bytes > 0) "这个文件约 ${humanSize(p.bytes)}。继续下载会消耗移动数据流量；断点续传按实际剩余大小计算。"
                                else "继续下载会消耗移动数据流量，具体按实际下载大小计算。",
                                fontSize = 11.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (p.slow) Text("当前移动网络较慢，下载可能耗时较长。", fontSize = 11.sp, color = GH.yellow)
                        }
                        NetPromptKind.BROWSE_MOBILE -> {
                            Text("浏览仓库、加载头像和图片、搜索与刷新都会消耗移动数据。", fontSize = 11.sp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        NetPromptKind.NO_NETWORK -> {
                            Text(p.message, fontSize = 11.sp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("下载已暂停，已下载的部分会保留；检测到可用网络后会自动开始。", fontSize = 11.sp,
                                color = GH.green)
                        }
                    }
                    if (p.kind != NetPromptKind.NO_NETWORK) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                            Text("以后不再提醒这类流量提示", fontSize = 10.sp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (p.kind) {
                        NetPromptKind.DOWNLOAD_MOBILE -> NetPrompts.answer(NetChoice.CONTINUE, dontAsk)
                        NetPromptKind.BROWSE_MOBILE -> NetPrompts.answer(NetChoice.CONTINUE, dontAsk)
                        NetPromptKind.NO_NETWORK -> NetPrompts.dismissNoNetwork()
                    }
                }) {
                    Text(
                        when (p.kind) {
                            NetPromptKind.NO_NETWORK -> "等联网自动开始"
                            else -> "继续"
                        }
                    )
                }
            },
            dismissButton = {
                Row {
                    if (p.kind == NetPromptKind.DOWNLOAD_MOBILE) {
                        TextButton(onClick = { NetPrompts.answer(NetChoice.WAIT_WIFI, dontAsk) }) { Text("等 WiFi", fontSize = 12.sp) }
                    }
                    TextButton(onClick = {
                        when (p.kind) {
                            NetPromptKind.NO_NETWORK -> {
                                onCancelDownload(p.fileName)
                                NetPrompts.answer(NetChoice.CANCEL)
                            }
                            else -> NetPrompts.answer(NetChoice.CANCEL, dontAsk)
                        }
                    }) {
                        Text(if (p.kind == NetPromptKind.NO_NETWORK) "取消下载" else "取消", fontSize = 12.sp, color = GH.red)
                    }
                }
            }
        )
    }
}
