package com.luogen.ghpro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.navigation.NavController
import com.luogen.ghpro.data.Favorites
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 收藏夹首页 / 单个收藏夹详情。
 * 顶部“新建”按钮建夹；详情里可改名、改描述、删夹、移除仓库、改单条备注。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(nav: NavController) {
    val ctx = LocalContext.current
    var folders by remember { mutableStateOf(Favorites.all()) }
    // 用 rememberSaveable 保存“当前打开了哪个收藏夹”，从仓库详情返回时不会掉回首页。
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var noteTarget by remember { mutableStateOf<String?>(null) }
    // 从收藏夹里的仓库返回时，保留原来滑到的位置，而不是重新回到顶部。
    val detailListState = rememberLazyListState()

    fun refresh() { folders = Favorites.all() }

    val selected = folders.firstOrNull { it.id == selectedId }

    // 系统返回键：先退出收藏夹详情、回到收藏夹总列表；只有总列表再按返回才离开收藏页。
    BackHandler(enabled = selectedId != null) { selectedId = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(selected?.name ?: "收藏夹", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (selected != null) {
                            Text("${selected.entries.size} 个仓库", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton({ if (selected != null) selectedId = null else nav.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (selected == null) {
                        IconButton({ showCreate = true }) { Icon(Icons.Filled.Add, "新建收藏夹") }
                    } else {
                        IconButton({ showEdit = true }) { Icon(Icons.Filled.Edit, "编辑收藏夹") }
                        IconButton({ showDelete = true }) { Icon(Icons.Filled.Delete, "删除收藏夹") }
                    }
                }
            )
        }
    ) { pad ->
        if (selected == null) {
            if (folders.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Folder, null, Modifier.size(46.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text("还没有收藏夹", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text("点右上角 + 建一个，再把喜欢的仓库放进来", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Button({ showCreate = true }) { Text("新建收藏夹") }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp)) {
                    items(folders, key = { it.id }) { f ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                .clickable { selectedId = f.id },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Folder, null, Modifier.size(26.dp), tint = GH.blue)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (f.note.isNotBlank()) {
                                        Text(f.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("${f.entries.size} 个仓库", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton({ Favorites.deleteFolder(f.id); refresh() }) {
                                    Icon(Icons.Filled.Delete, "删除收藏夹", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val s = selected
            LazyColumn(Modifier.fillMaxSize().padding(pad), state = detailListState, contentPadding = PaddingValues(12.dp)) {
                item {
                    if (s.note.isNotBlank()) {
                        Text(s.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                    }
                }
                if (s.entries.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 80.dp), contentAlignment = Alignment.Center) {
                            Text("这个收藏夹还是空的，去仓库页点书签收藏进来", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(s.entries, key = { it.fullName }) { e ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable {
                            val owner = e.fullName.substringBefore("/")
                            val repo = e.fullName.substringAfter("/", "")
                            if (owner.isNotBlank() && repo.isNotBlank()) nav.navigate("repo/$owner/$repo")
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bookmark, null, Modifier.size(20.dp), tint = GH.yellow)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.fullName, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (e.note.isNotBlank()) {
                                    Text(e.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                val day = if (e.addedAt > 0) SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(e.addedAt))
                                else ""
                                Text("加入于 $day", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ noteTarget = e.fullName }) {
                                Icon(Icons.Filled.Edit, "改备注", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp))
                            }
                            IconButton({ Favorites.removeFrom(s.id, e.fullName); refresh() }) {
                                Icon(Icons.Filled.Delete, "从收藏夹移除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        FavFolderEditDialog(
            title = "新建收藏夹",
            name0 = "",
            note0 = "",
            onDismiss = { showCreate = false },
            onSave = { name, note ->
                Favorites.create(name, note)
                showCreate = false
                refresh()
                Toast.makeText(ctx, "已创建收藏夹：$name", Toast.LENGTH_SHORT).show()
            }
        )
    }
    if (showEdit && selected != null) {
        FavFolderEditDialog(
            title = "编辑收藏夹",
            name0 = selected.name,
            note0 = selected.note,
            onDismiss = { showEdit = false },
            onSave = { name, note ->
                Favorites.updateFolder(selected.id, name, note)
                showEdit = false
                refresh()
            }
        )
    }
    if (showDelete && selected != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除收藏夹") },
            text = { Text("确定删除“${selected.name}”？里面的仓库不会从 GitHub 删除，只是不再收藏。") },
            confirmButton = {
                TextButton({
                    Favorites.deleteFolder(selected.id)
                    showDelete = false
                    selectedId = null
                    refresh()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ showDelete = false }) { Text("取消") } }
        )
    }
    noteTarget?.let { full ->
        val s = selected ?: return@let
        FavNoteDialog(
            fullName = full,
            note0 = s.entries.firstOrNull { it.fullName.equals(full, true) }?.note ?: "",
            onDismiss = { noteTarget = null },
            onSave = { n ->
                Favorites.setEntryNote(s.id, full, n)
                noteTarget = null
                refresh()
            }
        )
    }
}

@Composable
private fun FavFolderEditDialog(
    title: String,
    name0: String,
    note0: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(name0) }
    var note by remember { mutableStateOf(note0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("收藏夹名称") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("描述（可选）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton({
                if (name.isNotBlank()) onSave(name.trim(), note.trim())
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FavNoteDialog(
    fullName: String,
    note0: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var note by remember { mutableStateOf(note0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给 $fullName 加备注", fontSize = 15.sp) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton({ onSave(note) }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } }
    )
}

/**
 * 仓库页点“收藏”时弹出的选择器：可以直接选已有收藏夹，也可以在这里现场新建，
 * 建完立即把仓库加进去，不用先跳去收藏夹页面。
 */
@Composable
fun FavPickerDialog(
    fullName: String,
    onDismiss: () -> Unit,
    onAdded: (String) -> Unit
) {
    var note by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf(Favorites.all()) }
    var showNew by remember { mutableStateOf(folders.isEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏到收藏夹", fontSize = 16.sp) },
        text = {
            Column(Modifier.heightIn(max = 430.dp)) {
                Text(fullName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("这个仓库的备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (folders.isNotEmpty()) {
                    Text("选择收藏夹", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
                        folders.forEach { f ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    Favorites.add(f.id, fullName, note)
                                    onAdded(f.name)
                                }.padding(vertical = 10.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Folder, null, Modifier.size(20.dp), tint = GH.blue)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (f.note.isNotBlank()) Text(f.note, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${f.entries.size}", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (!showNew) {
                    TextButton({ showNew = true }) { Text("+ 新建收藏夹") }
                } else {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("新收藏夹名称") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton({
                            if (newName.isNotBlank()) {
                                val f = Favorites.create(newName.trim(), "")
                                Favorites.add(f.id, fullName, note)
                                folders = Favorites.all()
                                onAdded(f.name)
                            }
                        }) { Text("新建并收藏") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("取消") } }
    )
}
