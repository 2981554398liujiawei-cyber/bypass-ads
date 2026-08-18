# Bypass Ads 项目交接文档（会话续接用）

> 生成时间：2026-08-19 凌晨（会话过长，供新会话/后续代理快速续接）
> 仓库：`C:\Users\cruelworld\Desktop\codex\bypass-ads`
> 设备：`adb -s 479901bd`（HyperOS / Android 15）
> GPT 会话：网页版 ChatGPT，会话标题 `bypass ads.开发`（`gpt_web_session.py ask --title "bypass ads.开发" "<问题>"`，工作目录 `C:\Users\cruelworld\Desktop\DeepSeek\连接浏览器`；页面输入框间歇不可用，失败可稍后重试）
>
> 更新：2026-08-19 01:35（GKD migration 卡已完成真机验证，见 §3）
> 更新：2026-08-19 02:0x（GKD 卡收尾：内置订阅 clickCenter 方案 + 品牌化/离线定制提交，见 §3.6）
> 更新：2026-08-19 02:0x（G2/G3 卡实现完成：内置订阅生成工具 + 首页精简 + 完全离线 + fresh install 真机验证通过，见 §3.7）
> 更新：2026-08-19 03:5x（G3.1 放行修复卡完成：initBundledSub 原子化 + HTTP 残留清理 + 链接拆分 + Case A-E 真机验证，见 §3.8）

---

## 0. 当前分支与 git 状态（最重要）

- **当前分支**：`codex/gkd-migration`（基于 `codex/m2-2-active-experimental-trial` 新建，已 push 到 origin）
- **HEAD**：`b366e3f`（G2/G3 离线开屏 MVP：品牌化+内置订阅+完全离线）；`97c7644`（本地订阅规则 + kotlin daemon 修复）；`452b9ea` = GKD v1.12.1 基线导入；`75bfef1`（HANDOVER 修正：B站替代拼多多）；G3.1 修复卡 commit 见 git log（§3.8）
- **品牌化/离线定制（已提交）**：applicationId=`app.bypassads`（debug 包 `app.bypassads.debug`）、移除 INTERNET 权限（完全离线）、精简订阅/高级设置页、内置开屏订阅自动初始化（`initBundledSubs`，App.kt）+ `assets/bypass_splash_rules.json`（规则主体在 gitignored 的 `.local.json`，由 `tools/build_splash_bundle.py` 生成）
- **上游旧分支** `codex/m2-2-active-experimental-trial` HEAD = `b113e18`（Toast 卡已提交推送，未合并——旧内核代码留在该分支）
- **⚠️ 危险教训**：PowerShell `Copy-Item -Recurse -Force 'A\*' 'B\'` 的通配符会连同**隐藏的 `.git` 目录**一起复制，本会话因此两次把 GKD 的 `.git` 覆盖到 bypass-ads（origin 变成 gkd-kit/gkd、git 历史"丢失"）。**今后复制整个项目必须用 `robocopy <src> <dst> /E /XD .git` 或显式排除 `.git`**。已重建：`git init` + `git remote add origin https://github.com/2981554398liujiawei-cyber/bypass-ads.git` + `git fetch origin`（远程历史完整，全部旧分支都在）。
- **git 用户配置已设**：`cyber <2981554398liujiawei@gmail.com>`（新 .git 重建后必须重设，否则 commit 报 "unable to auto-detect email"）

---

## 1. 项目背景与整体流程

- 目标：Android 15+ **完全离线**广告跳过工具；Web-ChatGPT 协作流程（GPT 发卡 → 代理实现/测试 → commit+push → 汇报 → GPT 批准后下一卡）。
- 里程碑：M2.3 真机试验（QQ音乐 5/5 连续冷启动真实广告自动跳过，全部 OUTCOME_VERIFIED SUCCESS，已 PASS）→ Toast 卡（已收尾）→ **当前 GKD migration 卡**。
- GPT 明确指示：旧内核（`codex/m2-2-active-experimental-trial` 的自行实现）只作为 legacy 收尾，**不再围绕 QQ音乐扩功能**；真正主线的下一步是 **GKD 迁移**。

---

## 2. 已完成：Toast 卡（M2.3 附加卡）

- **改动**：`OUTCOME_VERIFIED SUCCESS` → `Toast.makeText(this, "✨广告已跳过✨", LENGTH_SHORT).show()`；`scheduleOutcomeVerification` 回调整体 try-catch（异常时记录 OUTCOME_VERIFIED UNCERTAIN，保证 blackBox 不被吞）。文件：`app/src/main/java/app/bypassads/accessibility/BypassAdsAccessibilityService.kt`（旧内核分支）。
- **发现**：`AccessibilityService.showToast` 在 SDK 37 **不是公开 API**（javap 验证只有 SHOW_MODE 常量），只能用 `Toast.makeText`。
- **设备权限坑**：设备上 `app.bypassads.experimental` 的 `POST_NOTIFICATION=ignore`（通知权限被禁）→ 系统日志 `Suppressing toast from package ... by user request`，Toast 被压制不显示。已用 `adb shell appops set app.bypassads.experimental POST_NOTIFICATION allow` 修复（验过返回 allow）。**新装机/被系统降权时需复查此权限**。
- **验证状态**：00:51 有一次 QQ音乐真实广告命中（gesture completed @1080,211）但当时权限还是 ignore，Toast 被压（证据：Suppressing toast 日志）；权限修复后未再拿到真实广告样本。GPT 回复"静默OK"并直接发了 GKD 卡 → **Toast 卡按通过收尾**，代码已提交推送（b113e18）。
- 遗留（不影响本卡）：Toast 实际显示未做最终肉眼确认；testad 测试路径 HyperOS 事件不稳定（case 频繁 supersede），不可作为可靠验证路径。

---

## 3. 当前卡：GKD migration（✅ 已完成：构建+安装+规则+真机 5/5 验证，2026-08-19 01:35）

### 3.1 GPT 卡片原文（验收标准）
> GKD v1.12.1 基线 → Bypass Ads 仓库新分支 → HyperOS 真机运行 → 仅开屏规则启用 → 至少一个真实 App 开屏广告自动跳过成功。完成后找 GPT，GPT 会直接审计 `codex/gkd-migration` 的代码和提交，不看单方面"完成报告"就放行。下一张会更接近产品化：GKD 精简 + Bypass Ads 品牌化 + 大规模开屏规则内置/预配置。

### 3.2 已完成
- GKD v1.12.1 源码浅克隆到 `C:\Users\cruelworld\Desktop\codex\gkd-v1.12.1`（tag `5a00f84`，约 1.7MB 源码）。
- 分支 `codex/gkd-migration` 已建、GKD 全部文件已提交（452b9ea）并 push。
- **构建**：`gradle-9.5.0 :app:assembleGkdDebug` BUILD SUCCESSFUL（1m23s）。**注意：只构建 gkd flavor**（`assembleDebug` 会同时编 play flavor 浪费一半时间）。
- **关键构建修复**（本机 Kotlin daemon 增量缓存损坏：`class-attributes.tab is already registered`）：`gradle.properties` 追加 `kotlin.incremental=false` + `kotlin.compiler.execution.strategy=in-process`（已提交）。
- **安装**：`adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`（HyperOS USB 安装限制）→ 改用 `adb push /data/local/tmp` + `adb shell pm install -r -d` 成功。包名 `li.songe.gkd.debug`（debug 后缀），versionName 1.12.1-452b9ea。
- **规则**：`rules/qqmusic-splash.json`（本地订阅 id=-2 格式）→ push 覆盖 `/sdcard/Android/data/li.songe.gkd.debug/files/subscription/-2.json` → force-stop + 重启 GKD 生效。订阅页确认"1应用/1规则"。
- **订阅启用坑**：本地订阅默认 **enable=false**！必须在订阅页打开开关（PerfSwitch 自定义控件，坐标 tap 约 1050,474），否则首页"暂无规则"、规则不参与匹配。
- **无障碍服务**：`li.songe.gkd.debug/com.google.android.accessibility.selecttospeak.SelectToSpeakService`（GKD 伪装 Google 服务名）。`settings put secure enabled_accessibility_services 'app.bypassads.experimental/...:li.songe.gkd.debug/...'` 启用。**HyperOS 频繁杀服务**（日志多次"无障碍已关闭/已启动"循环），但服务会自动重连；已 `pm grant li.songe.gkd.debug android.permission.WRITE_SECURE_SETTINGS`（debug 构建可 grant，GKD 可自动修复服务）。
- **真机验证 ✅**：QQ音乐（com.tencent.qqmusic）连续 5 次冷启动，GKD 日志全部命中 `text=跳过 clickable=true`（bounds [1005,174][1155,249]，与旧内核一致）并 `clickNode result=true`，共 17 条命中记录。**5/5 真实广告自动跳过成功**。
- 设备残留：`gkd_subscriptions/qqmusic_splash.json`（上会话中间产物，id=10001 远程订阅格式，未使用未提交）、`tmp_*.png`/`gkd_screen1.png`（截图，untracked 不提交）。

### 3.3 GKD 项目要点（已确认）
- 结构：`app/`（主应用，applicationId=`li.songe.gkd`，versionName=1.12.1）、`selector/`（选择器引擎）、`hidden_api/`、`gradle/`。
- 构建配置：compileSdk=37、targetSdk=37、minSdk=26、buildTools=37.0.0；**无 `GKD_STORE_FILE` 属性时自动用 debug 签名**（`app/build.gradle.kts` ~L98-106）。debug buildType 有 `applicationIdSuffix=".debug"` → 真机包名 `li.songe.gkd.debug`；产物在 `app/build/outputs/apk/gkd/debug/app-gkd-debug.apk`（约 28MB）。
- 无障碍服务伪装名：`com.google.android.accessibility.selecttospeak.SelectToSpeakService`（main AndroidManifest）；HyperOS 设置页显示为"GKD-debug"。
- 订阅存储：`/sdcard/Android/data/li.songe.gkd.debug/files/subscription/{id}.json`（外部目录；`app.filesDir` 下无 `.gkd` 标记文件时用 externalFilesDir）。本地订阅 id=-2（`-2.json`），应用启动时 `initSubsState()`（App.kt L246）加载；直接覆盖文件 + force-stop + 重启即生效（离线方案）。
- 订阅格式：`RawSubscription.parse`（kotlinx.serialization，loadSubs 用标准 JSON）。必需字段 id/name/version；apps[].groups[].key/name；rules 的 `matches` 是 selector 字符串（如 `[text^="跳过"][clickable=true]`），`action` 缺省 = Click（可点击则 clickNode，否则点击中心）。
- GKD 默认无规则需订阅；本卡用本地订阅注入（见 3.2）。

### 3.4 构建/运行卡点（已全部解决，速查）
1. **Gradle wrapper 9.5.1 下载失败** → 用缓存完整版 gradle-9.5.0 直接构建：`& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat" :app:assembleGkdDebug --console=plain --no-daemon`（env 不跨 pwsh 持久；在 bypass-ads 目录执行）。
2. **Kotlin daemon 增量缓存损坏**（`class-attributes.tab is already registered`/`class-fq-name-to-source.tab`）→ `gradle.properties` 已加 `kotlin.incremental=false` + `kotlin.compiler.execution.strategy=in-process`（已提交，勿删）。若仍报错：`gradle --stop` + 删 `app\build`、`.kotlin` 后重跑。
3. **SDK location** → `local.properties`：`sdk.dir=C:/Users/cruelworld/AppData/Local/Android/Sdk`（正斜杠、无 BOM；被 .gitignore 忽略）。
4. **HyperOS 拒绝 `adb install`**（INSTALL_FAILED_USER_RESTRICTED）→ `adb push apk /data/local/tmp/gkd.apk` + `adb shell pm install -r -d /data/local/tmp/gkd.apk`。
5. **本地订阅默认 enable=false** → 订阅页手动开开关（PerfSwitch 在卡片右侧，tap 约 (1050,474)）。
6. **HyperOS 频繁杀无障碍服务** → 服务自动重连；已 `pm grant li.songe.gkd.debug android.permission.WRITE_SECURE_SETTINGS`（debug 构建可授），GKD 能自动修复。启用命令：
   `adb shell settings put secure enabled_accessibility_services 'app.bypassads.experimental/app.bypassads.accessibility.BypassAdsAccessibilityService:li.songe.gkd.debug/com.google.android.accessibility.selecttospeak.SelectToSpeakService'` + `settings put secure accessibility_enabled 1`。
7. GKD 日志：`/sdcard/Android/data/li.songe.gkd.debug/files/log/gkd-YYYYMMDD.log`（命中记录含 AttrInfo/ActionResult，可直接 grep `text=跳过` / `clickNode`）。

### 3.6 本会话收尾（2026-08-19 02:0x，已提交）

- **根因确认**：默认动作 `Click` 对 clickable 节点走 `clickNode`（`performAction(ACTION_CLICK)`），在 QQ音乐 WebView 广告上**无效**（匹配命中、`result=true` 但广告不跳——与旧内核结论一致）。**必须 `action:"clickCenter"`**（`dispatchGesture` 点击节点中心，坐标 `(1080.0, 211.5)`，与旧内核一致）。
- **内置订阅方案（推荐，已用）**：GKD 首次启动 `initBundledSubs()`（App.kt）自动把 `assets/bypass_splash_rules.json`（或 gitignored 的 `.local.json` 大规则集，166KB/version 567，含 QQ音乐等大量 App 的开屏规则）导入为内置订阅 id=`100000001` 并默认启用。**无需手动导入 -2.json / 无需网络**。设备已验证：`100000001.json` 生成、日志"内置开屏规则已初始化"。
- **包名注意**：定制后为 `app.bypassads.debug`（非官方 `li.songe.gkd.debug`）。启停/日志路径全部用 `app.bypassads.debug`：
  - 订阅文件：`/sdcard/Android/data/app.bypassads.debug/files/subscription/`
  - 日志：`/sdcard/Android/data/app.bypassads.debug/files/log/gkd-YYYYMMDD.log`
  - 无障碍：`app.bypassads.debug/com.google.android.accessibility.selecttospeak.SelectToSpeakService`
- **真机验证（内置订阅方案）✅**：QQ音乐连续 4 次冷启动（含 0.5s 密集采样），广告出现（跳过按钮可见）→ 1-3s 内进主界面；GKD 日志 `ActionResult(action=clickCenter, result=true, shell=false, position=(1080.0, 211.5))`。
- **编译修复**：`ControlPage.kt` 两处编译错误（`AboutRoute` 缺 import；`storeFlow.update { it.copy(enableMatch = it) }` 应为 `!it.enableMatch`）已修。
- **遗留**：GKD 主页显示"无障碍发生故障"（服务反复 <init>/销毁，HyperOS 杀服务）但服务实际绑定可用（capabilities=161 含 gesture），规则照常执行；旧内核服务（`app.bypassads.experimental`）仍在 Enabled 列表（未禁）。

### 3.7 G2/G3 卡：Bypass Ads 离线开屏广告 MVP（2026-08-19，已提交 b366e3f，待 GPT 审计）

GPT 二十节规格要点 → 实现与验证：

1. **品牌化**：applicationId=`app.bypassads`（debug=`app.bypassads.debug`，aapt 验证 ✅）；app_name="Bypass Ads"（无障碍 label 显示 "Bypass Ads-debug" ✅）；channel=bypassads；首页/协议弹窗/关于页全部品牌化；AboutPage 保留 "基于 GKD · GPL-3.0" attribution + 开源代码链接。
2. **完全离线**：AndroidManifest **移除 INTERNET 权限**（aapt 验证无 INTERNET ✅）；隐藏网络入口：订阅页 FAB 改"添加应用规则"（本地）、订阅设置弹窗删"更新订阅"、AdvancedPage 删 HTTP 服务区块、AboutPage 使用协议/隐私政策改本地静态文本（不再跳网络页）。
3. **内置开屏规则机制**：`App.kt` 新增 `initBundledSubs()`（onCreate 的 initSubsState 之后调用）：读 `assets/bypass_splash_rules.json`（fixture，git 跟踪）或 `bypass_splash_rules.local.json`（gitignored 大规则集，优先）→ 首次安装自动创建订阅 id=100000001、**默认 enable=true**；APK 升级时若 bundle version 更大则升级规则文件、**保留用户 enable 状态**。无 INTERNET 也可用，不依赖 adb push/手动开关。
4. **本地规则生成**：`tools/build_splash_bundle.py`（Python，json5 库优先）读本地第三方订阅（`tools/.cache/`，gitignored）→ 只保留"开屏广告*"规则组 → 输出 `app/src/main/assets/bypass_splash_rules.local.json`（gitignored，**第三方规则正文不 commit**）。当前生成结果：**170 应用 / 171 规则组 / 299 规则，version 567**（输入 Lin-arm 订阅 969 apps/2401 组）。
5. **首页精简**：ControlPage 只保留：服务状态、**"自动跳过开屏广告"总开关**（绑定 store.enableMatch，默认开启）、触发记录、关于 Bypass Ads；删除常驻通知/HTTP/界面日志卡片；appOps 受限卡片改跳 AuthA11yRoute（本地）。
6. **微信/支付宝小程序开屏规则**：bundle 内含 `com.tencent.mm`（开屏广告-微信小程序，2 规则）、`com.eg.android.AlipayGphone`（开屏广告-小程序开屏广告，2 规则）✅。
7. **仓库清理**：README 重写（Bypass Ads 说明 + GKD attribution + GPL-3.0）；删 .github/FUNDING.yml + workflows（GKD CI/赞助）；保留 GPL-3.0 LICENSE 与 GKD 作者 attribution（未重新声明 license）。
8. **构建**：clean 全量 `assembleGkdDebug` BUILD SUCCESSFUL（APK 28MB）。⚠️ 本机坑：**KSP 与 Kotlin 编译并发时 transform 报 "系统找不到指定的路径"**（class 输出到 built_in_kotlinc 而 transform 从 tmp/kotlin-classes 读）→ 解决：先单独跑 `:app:kspGkdDebugKotlin`（UP-TO-DATE）再 assemble，或确保 ksp 完成后构建；失败时杀残留 java 进程再重试（"stop command received"= 残留 gradle client 干扰）。
9. **fresh install 真机验证 ✅**（app.bypassads.debug，HyperOS）：首次启动 → 使用声明（Bypass Ads 品牌）→ 订阅页自动出现 **"Bypass Ads 开屏规则 170应用/171规则 v567"** 且 **开关 checked=true**（无需手动）；首页总开关默认开启；无障碍服务启用（label "Bypass Ads-debug"）后开屏广告自动跳过。
10. **真机开屏跳过（≥2 个普通 App，截图+日志双证据）✅**：
    - **QQ音乐**（com.tencent.qqmusic）：GKD 日志 `id:100000001 gName:开屏广告 → AttrInfo(text=跳过, clickable=true) → ActionResult(clickCenter, result=true, position=(1081.5,213.5))`（另见 G1 卡 5/5 验证）。
    - **B站**（tv.danmaku.bili）：冷启动 2 秒截图确认**开屏广告页**（"財神 窦占龍"广告 + 右下角圆形"跳过"按钮）→ 日志 `AttrInfo(id=count_down, text=跳过 3, clickable=true) → ActionResult(clickNode, result=true)` → 自动进 MainActivityV2 主界面。
    - 注：拼多多（com.xunmeng.pinduoduo）的"跳过 3"按钮**不是开屏广告**（用户确认拼多多无开屏），已从证据剔除；京东两次冷启动均未出开屏广告（规则匹配 status:ok）。
11. **安装坑（新）**：`pm install` 报 INSTALL_FAILED_USER_RESTRICTED → 用 **`adb install --user 0 -r -d -t <apk>`** 成功（-t 允许 testOnly）。
12. 遗留：首页"无障碍发生故障"显示问题（服务实际绑定运行，UI 状态流滞后）；未做微信/支付宝小程序开屏真机触发（规则已内置）。

---

### §3.8 G3.1 放行修复卡（GPT 审计：不 PASS → 小修后放行）

GPT 审计结论（新会话重建）：b366e3f 主体方向对、非"假完成"；阻断项 3 个，均已修复（commit 见 §0）：

1. **initBundledSubs 升级竞态 → 原子化**：`SubsState.kt` 新增 suspend helper `initBundledSub(bundled)`，在 `updateSubsMutex` 锁内执行（与 initSubsState 串行）；**版本比较读磁盘持久化文件真实 version**（不再依赖启动瞬间未加载的 subsMapFlow）。语义：无 SubsItem → 创建（enable=true）；文件丢失/损坏 → 用 bundle 修复（保留 enable）；`bundle.version > persisted` → 升级；`==` → 不重写；`<` → 不降级。`App.kt` 的 `initBundledSubs()` 只负责解析 assets 后调用 helper。
2. **HTTP 残留清理**：AndroidManifest **移除 `.service.HttpService` 与 `.service.HttpTileService` 注册**；删除 `HttpTileService.kt`（无其他引用）；`HttpService.kt` 保留为不可达代码（无 Manifest/UI/Tile 入口、App 不自动启动），`httpNotif` 通知项与 `clearHttpSubs()` 保留（App.onCreate 仍调用清理内存订阅）。Quick Settings 可添加 Tile 列表已无 HTTP Server Tile（dumpsys 验证：仅 Gkd/Snapshot/Button/Match/Activity/Event Tile）。
3. **链接拆分**：`Constants.kt` 新增 `BYPASS_REPOSITORY_URL`（bypass-ads 仓库）/`BYPASS_ISSUES_URL`/`GKD_UPSTREAM_URL`（gkd-kit/gkd），删除旧 REPOSITORY_URL/ISSUES_URL。AboutPage："开源代码"→Bypass Ads 仓库、"问题反馈"→Bypass Ads issues、"基于 GKD·GPL-3.0"→gkd-kit/gkd；CrashReportPage 反馈也指向 Bypass Ads issues；`AppMeta.commitUrl` 指向 Bypass Ads 仓库。
4. **INTERNET 彻底移除**：合并 manifest 中库（ktor 等）注入的 INTERNET 用 `tools:node="remove"` 强制移除 → **aapt 最终验证无 INTERNET** ✅（applicationId=app.bypassads.debug）。
5. **措辞修正**：README/HANDOVER 不再把"第三方规则未提交"表述为 license-clean/可再分发——Lin-arm bundle 仅作为本地研发/真机验证输入，公开 APK 规则许可方案后续单独处理。

**Case A-E 真机验证（可重复本地验证，HyperOS）**：
- Case A fresh install：卸载重装 → 订阅自动创建 v567、UI 开关蓝色（enable=true）✅
- Case B 用户关闭+升级：UI 关开关（enable=false）→ 文件 version 降为 566 → 重启 → 文件回 567（升级）且开关仍灰（enable 保留）✅
- Case C 同版本：文件 v567 → 重启 → mtime/md5 完全不变（不重写）✅
- Case D 防降级：文件改 568 → 重启 → 仍 568（不降级）✅
- Case E 文件丢失：删 100000001.json → 重启 → 文件自动恢复 v567/170apps，开关仍灰（enable 保留）✅

**真机补充验收**：
- 首页总开关关闭 → 冷启动 QQ音乐 **开屏广告完整显示未被跳过**（"已暂停·规则暂不执行"）；重新开启 → 冷启动 QQ音乐 **自动跳过**（日志 `gName:开屏广告 status:ok → AttrInfo(text=跳过, clickable=true) → ActionResult(clickCenter, result=true, position=(1084.5,217.0))` → 已进主界面）✅
- 无障碍服务：force-stop App 后服务被停（Bound services 空、首页显示"无障碍已关闭"）→ settings put 重新启用后恢复（HyperOS 行为，非缺陷）；**验证开屏跳过前必须确认服务 Bound**
- 构建：kspGkdDebugKotlin + assembleGkdDebug BUILD SUCCESSFUL

### 3.5 本卡剩余动作（下一会话）
1. 向 GPT 汇报（模板见 §6），等 GPT 审计 `codex/gkd-migration` 分支。
2. 下张卡方向（GPT 预告）：GKD 精简 + Bypass Ads 品牌化 + 大规模开屏规则内置/预配置。

---

## 4. 设备与测试环境速查（旧内核阶段积累，GKD 阶段仍有用）

- 设备串号：`479901bd`；QQ音乐包名 `com.tencent.qqmusic`，启动 Activity `com.tencent.qqmusic/.activity.AppStarterActivity`。
- QQ音乐开屏广告节奏：约 9-10 分钟一次（22:20-23:06 密集），深夜可能 40+ 分钟无广告；跳过节点 bounds 约 `[1005,174][1155,249]`（cx≈0.90 cy≈0.09），text="跳过" clickable=true（GKD 规则 `[text^="跳过"][clickable=true]` 命中）。
- GKD（新内核）无障碍服务：`li.songe.gkd.debug/com.google.android.accessibility.selecttospeak.SelectToSpeakService`；启用/恢复命令见 §3.4-6。
- 旧内核（experimental）无障碍服务：`app.bypassads.experimental/app.bypassads.accessibility.BypassAdsAccessibilityService`；HyperOS 常掉服务，恢复命令：
  `adb shell am force-stop app.bypassads.experimental; adb shell settings put secure enabled_accessibility_services ''; adb shell settings put secure enabled_accessibility_services 'app.bypassads.experimental/app.bypassads.accessibility.BypassAdsAccessibilityService'; adb shell settings put secure accessibility_enabled 1`（install 后首次启动常无事件，第二次启动正常）。
- 旧内核构建：`$env:BYPASS_ADS_EXPERIMENTAL_SIGNING_PROPERTIES='C:\secure\bypass-ads\experimental-test.properties'; .\gradlew.bat :app:packageExperimental`（env 不跨 pwsh 调用持久）。
- blackBox 证据读取：`adb -s 479901bd shell "run-as app.bypassads.experimental grep <pkg> files/bypass_ads_blackbox/2026-08-18.jsonl"`。
- **不要用 `git add -A` 提交测试产物**（历史上有过误提交几百个临时文件）；只 add 明确路径。`m23_*`、`tmp_*`、`*.png`、`*.apk`、`*.jsonl` 等都是 untracked 测试产物。

---

## 5. 待办/风险清单

- [x] 确认 GKD debug 构建结果 → 修残余构建错误（kotlin daemon 增量缓存损坏，已用 gradle.properties workaround 解决）
- [x] 安装 GKD 到真机、开无障碍、仅开屏规则、真机验证（5/5 真实广告跳过）
- [x] 提交规则/适配改动 + push（待：向 GPT 汇报，GPT 会直接审计代码）
- [ ] 旧内核 Toast 权限（POST_NOTIFICATION）在 GKD 上若沿用需同样复查
- [ ] 风险：GKD（`li.songe.gkd.debug`）与旧内核（`app.bypassads.*`）并行存在，两个无障碍服务同时开启；HyperOS 会频繁杀 GKD 服务（日志反复"无障碍已关闭/已启动"），已 grant WRITE_SECURE_SETTINGS 让 GKD 自动修复，但长期稳定性待观察
- [ ] 风险：GKD 规则走本地订阅（`files/subscription/-2.json`），完全离线可行；远程订阅需网络，设备离线时需本地兜底
- [ ] 风险：`adb install` 被 HyperOS 限制（INSTALL_FAILED_USER_RESTRICTED），后续装新 APK 用 `adb install --user 0 -r -d -t` 方式
- [x] G2/G3 卡：品牌化 + 完全离线（无 INTERNET）+ 内置订阅自动启用 + 首页精简 + 微信/支付宝小程序规则 + fresh install 真机验证（commit b366e3f）
- [x] G3.1 卡（GPT 审计放行修复卡）：initBundledSub 原子化（磁盘 version + 互斥锁）+ Manifest 移除 HttpService/HttpTileService 注册（删除 HttpTileService.kt，HttpService.kt 保留不可达代码）+ AboutPage 链接拆分（BYPASS_REPOSITORY_URL/BYPASS_ISSUES_URL/GKD_UPSTREAM_URL）+ commitUrl 指向 Bypass Ads 仓库 + Case A-E 验证（见 §3.7 补记）
- [ ] 风险：KSP/transform 并发构建失败（先跑 ksp 再 assemble 可绕过）；"无障碍发生故障"UI 显示与实际绑定不符（服务可用）
- [ ] 风险：内置订阅规则来自第三方（Lin-arm，license:null），**仅作为本地研发/真机验证输入，不构成公开再分发授权**；公开 APK 规则许可方案后续单独处理（可换自写规则或获得授权的规则源）

---

## 6. 汇报 GPT 的模板（GKD 卡完成后）

```
GKD migration 卡汇报：
1) 基线：GKD v1.12.1（tag 5a00f84）导入 codex/gkd-migration 分支，commit 452b9ea，已 push。
2) 构建：assembleDebug（debug 签名）成功，APK 路径/大小，安装到真机（HyperOS/Android 15）。
3) 配置：仅启用开屏广告规则（<说明规则来源：本地规则/订阅>），其余规则关闭。
4) 真机验证：<App 名> 冷启动 <N> 次，开屏广告自动跳过成功 <M> 次（附 GKD 日志/截图证据）。
5) 变更清单：<列出相对 GKD v1.12.1 的所有改动，GPT 会审计>
```
