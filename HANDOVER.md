# Bypass Ads 项目交接文档（会话续接用）

> 生成时间：2026-08-19 凌晨（会话过长，供新会话/后续代理快速续接）
> 仓库：`C:\Users\cruelworld\Desktop\codex\bypass-ads`
> 设备：`adb -s 479901bd`（HyperOS / Android 15）
> GPT 会话：网页版 ChatGPT，会话标题 `bypass ads.开发`（`gpt_web_session.py ask --title "bypass ads.开发" "<问题>"`，工作目录 `C:\Users\cruelworld\Desktop\DeepSeek\连接浏览器`；页面输入框间歇不可用，失败可稍后重试）
>
> 更新：2026-08-19 01:35（GKD migration 卡已完成真机验证，见 §3）

---

## 0. 当前分支与 git 状态（最重要）

- **当前分支**：`codex/gkd-migration`（基于 `codex/m2-2-active-experimental-trial` 新建，已 push 到 origin）
- **HEAD**：`452b9ea` `chore(gkd): import GKD v1.12.1 baseline onto codex/gkd-migration (start of GKD migration)`——GKD 全部源码已导入并提交（约 380 文件）
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
- [ ] 风险：`adb install` 被 HyperOS 限制（INSTALL_FAILED_USER_RESTRICTED），后续装新 APK 用 `pm install` 方式

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
