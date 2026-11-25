# GitHub Pro（罗根版）

一个功能完整的原生 Android GitHub 客户端（Kotlin + Jetpack Compose），直接调用 GitHub REST API v3 与 GraphQL API v4，
覆盖网页端与 GitHub Mobile 的主要开放能力，并额外做了国内网络加速、实时工作流监听与镜像下载。

作者：**luogen-vip**（罗根）

---

## 登录方式

1. **访问令牌登录（推荐）**：粘贴 Personal Access Token，需勾选
   `repo`、`workflow`、`notifications`、`user`、`delete_repo`、`write:discussion`。
2. **网页授权登录（OAuth Device Flow）**：
   在 GitHub → Settings → Developer settings → OAuth Apps 新建应用并勾选 *Enable Device Flow*，
   把 **Client ID** 填进 App（不需要 Client Secret），App 会显示设备码并打开授权页，授权完成后自动登录。

令牌只保存在本机应用私有存储，不会上传到任何第三方服务器；加速镜像只用于文件下载，接触不到令牌。

## 已实现的功能

### 仓库
- 我的全部仓库（含私有、协作、组织），无限滚动分页
- 实时搜索仓库 / 用户 / 代码 / 议题（输入即搜，400ms 防抖）
- 按语言过滤（Kotlin、Java、Python、JS、TS、Go、Rust、C/C++、Swift、Dart… 共 20 种 + 全部）
- 排序：最近更新 / 最近推送 / 名称 / Star 最多
- 仓库详情：描述、Topics、默认分支、许可证、体积、创建/更新时间、主页、私有/归档/复刻标记
- **语言占比条**：调用 `GET /repos/{o}/{r}/languages`，按真实字节数计算百分比并彩色堆叠展示
- Star / Fork / Watch（关注）/ 复刻 Fork 到自己账号
- Star 列表、复刻列表、关注者列表、贡献者列表（含头像与贡献次数，可点进用户主页）
- 新建仓库（名称、描述、私有、README 初始化、.gitignore 模板、License 模板、Issues/Wiki/Projects/Discussions 开关）
- **真实删除仓库**（GitHub 服务器上彻底删除，需输入仓库名二次确认）
- 编辑仓库：描述、主页、Topics、私有、归档、功能开关、**改名**

### 代码
- 分支切换、目录浏览、面包屑路径
- 查看文件内容（带行号）
- **在线编辑并提交**（PUT contents，可写 Commit message，保存后 GitHub 立即可见）
- 新建文件、删除文件（真实产生提交）
- 下载单个文件 / 下载整仓 ZIP

### 议题 Issues
- 打开中 / 已关闭 列表，标签、评论数、作者、时间
- 新建议题（标题、正文、标签、指派）
- 关闭 / 重新打开 / 锁定讨论
- 评论列表与发表评论

### 拉取请求 Pull Requests
- 打开 / 关闭 / 全部 列表
- 详情：分支对比、增删行数、改动文件与 diff 片段
- 合并（merge）、关闭、重新打开、批准（review approve）
- 评论列表与发表评论

### Actions 工作流
- 工作流列表、手动触发（workflow_dispatch）
- 运行记录：状态（排队/进行中/成功/失败/取消/超时）、事件、分支、发起人、第几次运行
- **自动轮询**（默认 20 秒，可在 8–120 秒调节），进行中的运行会持续刷新耗时
- 运行详情：每个 Job、每个 Step 的状态与**各自耗时**，总耗时、已运行时长
- 重跑 / 取消 / 删除运行
- **下载产物 Artifacts**（走官方 API 原地址，带令牌，不支持镜像与分片）
- 下载运行日志 ZIP
- 删除产物

### 发行版 Releases
- 全部发行版：标签、标题、说明、正式版/预发行/草稿标记、发布时间、下载次数
- 每个附件单独下载（**镜像加速 + 多线程分片**）
- 新建发行版（tag / 标题 / 说明 / 草稿 / 预发行）
- 上传附件（SAF 选文件 → `uploads.github.com` 真实上传）
- 删除附件 / 删除发行版

### 讨论 Discussions（GraphQL）
- 讨论列表（分类 emoji、作者、回复数、是否已有采纳答案）
- 讨论详情与回复（`addDiscussionComment`）

### 用户与社交
- 用户主页：头像、简介、公司、位置、博客、加入时间、仓库数、关注者/正在关注
- 关注 / 取消关注
- 该用户全部仓库、公开动态（Push / Star / Fork / Release…）

### 通知与实时
- GitHub 通知列表（未读/全部），单条已读、全部已读
- 点击通知直接跳到对应议题 / PR / 仓库
- **前台实时监听服务**：App 在后台也按设定间隔同步，工作流一结束立刻本地通知（成功/失败/取消）
- **WorkManager 后台同步**（15 分钟一次）：新 Star、仓库新推送、新通知
- App 内自动刷新（每 20 秒），无需手动下拉，网页端做的改动会自动出现在 App

### 下载与加速
- 内置镜像：`ghfast.top`、`gh-proxy.com`、`ghproxy.net`、`hub.gitmirror.com`、`github.moeyy.xyz`、`gh.llkk.cc`
- 可**自行添加 / 删除**镜像，一键恢复默认
- 多线程分片下载（1–16 线程，Range 请求），自动探测 `Accept-Ranges`
- 镜像逐个回退，全部失败才报错
- 下载完成通知，APK 可直接调起安装器

### 其他
- 深色 / 浅色 / 跟随系统
- API 剩余额度查看
- 令牌退出登录（一键清除）

## 技术栈

- Kotlin 1.9.24 / AGP 8.5.2 / Gradle 8.7
- Jetpack Compose (Material 3, BOM 2024.06)
- OkHttp 4.12 + Gson（REST 与 GraphQL 统一客户端，自动解析 `Link` 分页）
- Coil（头像）
- WorkManager（后台同步）+ 前台服务（实时监听）

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease   # 或 assembleDebug
```

## 说明

- GitHub API 不支持通过 API 修改**用户名**，也不支持**删除议题**（只能关闭/锁定），这两项受官方限制。
- 单个文件通过 contents API 编辑上限 1MB，超过会提示直接下载。
- 工作流产物与日志必须使用官方原地址（带令牌）下载，镜像会失败，App 已自动禁用镜像。
