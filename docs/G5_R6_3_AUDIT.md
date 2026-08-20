# G5-R6.3 审计 — 仓库历史恢复 + 广告 Runtime V2 + 三档策略分层 + Records/Teach/规则栈收口

> 分支：`codex/gkd-migration-repaired`（修复主线）
> 归档：`archive/disconnected-r6.2-4118883`（断历史只读）
> 设备：Redmi 2509FPN0BC（HyperOS / Android 15，`adb -s 479901bd`）
> 日期：2026-08-20

## 1. Git 历史恢复（P0）

- 断历史根因：`1cf2244` 为无父 root commit（R6.1 时本地 .git 损坏），
  `4118883` 的 ancestry 只有 `1cf2244`；健康历史 `f7001e7`/`606182d` 从未上远程。
- 恢复方式：全新 clone `bypass-ads-repaired`；健康历史对象通过
  `git fetch <旧仓库> <sha>` 零接触导入（旧仓库未改动，仅作事故现场）；
  `codex/gkd-migration-repaired` 从 `f7001e7` 创建；`git diff --binary f7001e7 4118883`
  生成树补丁并应用为 `chore: recover R6.1 and R6.2 onto healthy history`。
- Recovery Gate：`git merge-base f7001e7 HEAD = f7001e7` ✅；
  `git merge-base 606182d HEAD = 606182d` ✅；`git diff 4118883 HEAD` 为空 ✅。
- 归档分支 `archive/disconnected-r6.2-4118883` 已 normal push；
  修复主线 `codex/gkd-migration-repaired` 已 normal push；无 force push、
  无手修 .git 文件。

## 2. 广告会话语义（Session V2）

- `BypassSessionResult`：OPEN / SUCCESS_CONFIRMED / FAILURE_CONFIRMED /
  UNRESOLVED / MISCLICK_SUSPECTED。
- schema 16 新增 `strategy_mode / result / action_attempts / candidate_type /
  confirmed_latency_ms / rule_origin`（全部带默认值，AutoMigration 15→16）。
- 一次广告 = 一个 Session：多动作（Skip→X→Close）在同一窗口内累积为
  一个 Session、一次结果；GKD ActionLog 仅表示"执行过一次动作"。
- 产品 Records 全部来自 Session（不再混 raw ActionLog）；历史策略存
  `strategy_mode`（发生时），不是当前值。

## 3. OutcomeVerifier

- `ActionResult.result=true` 语义 = ACTION_ACCEPTED，不是 success。
- 动作后 300ms 重新读取 active window（复用 A11yContext/GKD Selector，
  无第二扫描器）：fresh 状态若有任一广告候选仍匹配 → ACTION_NO_EFFECT；
  小程序广告壳仍在 → ACTION_NO_EFFECT；跳到外部落地包（浏览器/商店/
  桌面）→ MISCLICK_SUSPENDED 并立即停止；候选消失且无广告上下文 →
  SUCCESS_CONFIRMED；无法确定 → UNRESOLVED。
- 实测：动作成功但广告仍在（scene y）→ 会话 UNRESOLVED（非成功）✅。

## 4. ActionBudget

- 按 sessionId 计数（禁止 per-package 计数）；动作前 reserve，失败也计数；
  CONSERVATIVE=1 / AGGRESSIVE=2 / CRAZY=3，所有 Bypass 会话硬上限 3。
- 删除双重 retry（300ms 无条件 requery + 350ms strategy retry），唯一循环：
  Action → OutcomeVerifier → 广告仍在 + 预算 → fresh query。

## 5. RulePolicyResolver / RuleTrust

- 信任分级：BUNDLED_DEDICATED / BUNDLED_GLOBAL / BYPASS_OVERRIDE /
  LOCAL_IMPORT_DEDICATED / LOCAL_IMPORT_GLOBAL / TEACH_NODE / TEACH_COORDINATE。
- 策略按身份（subsId+groupType+appId+groupKey+ruleKey）+ `bypassMode` 元数据
  解析；规则名仅作日志可读性（不再是安全边界）。
- 成熟专用规则保守档正常运行（scene aa：保守 → SUCCESS_CONFIRMED ✅）。
- 高风险 App 仅 BUNDLED_DEDICATED / BYPASS_OVERRIDE 豁免。
- TEACH_NODE=AGGRESSIVE；TEACH_COORDINATE=CRAZY；local import 坐标=CRAZY、
  global generic close=AGGRESSIVE+。

## 6. AdContextTracker

- NONE / WEAK / STRONG 三级；STRONG 由小程序广告 Activity、窗口内 skip 证据、
  广告 label 证据建立；WEAK = 新进入 + close 候选但无广告证据。
- package + activity 双入口时间（微信 LauncherUI→AppBrandUI 无包名变化但
  活动变化重置窗口）；服务重连不再视为新启动窗口。
- 窗口级广告标签扫描（有界 ≤80 节点，复用已取 root）：真实广告场景的
  独立"广告"标签可建立 STRONG 上下文。

## 7. 三档策略 V2

- 保守：成熟专用 + 明确跳过语义 + 安全可点击父节点；不启用通用关闭/裸 X/结构/坐标。
- 激进：+ 关闭/Close/desc/ad_close/×（glyph）；generic 必须 STRONG 上下文；
  `allowGlyphClose=true` / `allowStructuralNoSemanticClose=false`。
- 疯狂：+ `allowStructuralNoSemanticClose=true` + `allowCoordinateFallback=true`，
  仍要求 STRONG 上下文。
- 真机硬 Gate（TestAd）：
  - structural (t)：保守 NO / 激进 NO / 疯狂 YES
  - coordinate (u)：保守 NO / 激进 NO / 疯狂 YES
  - multi-stage (x)：疯狂下 1 会话、3 动作、1 SUCCESS_CONFIRMED
  - trusted dedicated close (aa)：保守 YES
  - ACTION_RESULT_TRUE_BUT_AD_REMAINS (y)：非 SUCCESS（UNRESOLVED）

## 8. 微信/支付宝

- 微信 override 显式绑定 `AppBrandUI` / `AppBrandLaunchProxyUI` activityIds；
  规则 200 Skip / 201 Close(AGGRESSIVE) / 202 ×(AGGRESSIVE) / 203 结构(CRAZY)。
- 支付宝绑定 XRiverActivity / Nebula（真实类名未在设备确认，上下文门控兜底）。
- 真实小程序成功率：NO_SAMPLE（环境无法稳定触发广告 SDK 填充）。

## 9. Teach V2

- "测试一次" → "开始一次验证"（armed retest）：记录目标 → 返回目标应用 →
  仅执行一次验证 → OutcomeVerifier 写回；首个未匹配不判失败，验证窗口
  （60s）结束仍无成功才 TEST_FAILED；VERIFIED 必须 SUCCESS_CONFIRMED。
- 微信/支付宝仅 ARM，不自动 deep link。
- 结构候选 snapshot 已支持（class/bounds/clickable/parentClickable/
  package/activity，不保存页面文字）。

## 10. RuleStackManager

- 分层：Teach > Bypass 官方 override > Local import > Bundled > Source global > Fallback。
- local import 按 (appId+group 名) 合并：导入 100 个应用不删除内置 745 个
  （单测验证）；冲突记录 winner。
- 恢复内置移除 Local import 层、保留 Teach；import/restore 使用可等待的
  `updateSubscriptionNow`（UI 显示 success 时引擎已使用新规则）。

## 11. Safety

- `rules/safety_exclusions.json` 单一来源（generator 读
  `generator_disabled_apps`，Kotlin 读 asset `high_risk_packages`）；
  `test_safety_exclusions.py` 防漂移（runtime veto ⊆ generator disabled、
  asset 副本一致、generator 确实读 JSON）。
- 分类器全量 trim+lowercase 归一化；NEXT/Next/next、PAYMENT/Payment、
  CLOSE/Close/close 测试覆盖；支付/转账/验证码/权限/安装/卸载/银行卡/
  身份认证永久 Veto；global coordinate 永久禁止。

## 12. 构建 / CI / 文档

- `.github/workflows/ci.yml`（fixture 多源测试，无私有规则源）；
  `tools/check_repo_integrity.py`（f7001e7 ancestry、重要文件非空、
  tracked 数量、无意外构建产物）。
- `build_selfuse.ps1` 去除用户绝对路径（gradlew.bat + SDK 自动定位 +
  build-tools 最高版本 aapt）；`tools/build_ad_bundle.py` 规范入口。
- Manifest Gate：默认 gkd 无 INTERNET / allowBackup=false /
  无 REQUEST_INSTALL_PACKAGES；fulltools 有 INTERNET。
- 版本：versionCode 2 / versionName 0.2.0。
- README / tools README / HANDOVER(ARCHIVED) / `docs/PROJECT_STATE.md` /
  `docs/PERMISSIONS.md`。
- `tools/collect_real_ad_samples.py`：真机采样（仅会话元数据，隐私安全）。

## 13. 构建门禁

- `:app:testGkdDebugUnitTest` ✅（65+ 用例）
- `:app:assembleGkdDebug` ✅
- `:app:assembleGkdDebugAndroidTest` ✅
- `:app:assembleFulltoolsDebug` ✅
- test_splash_policy / test_safety_exclusions / test_multi_source ✅
- check_repo_integrity ✅、git diff --check ✅

## 14. 已知事项 / 未通过

- **FIELD GATE：PENDING**（真实广告样本 NO_SAMPLE，采样工具就绪）。
- 结构/坐标 selector 对真实广告快照待实机样本调优（TestAd fixture 已闭环）。
- Shizuku：NO_ENV（无授权环境）。
- HyperOS 在 force-stop 后可能使无障碍服务假死（bound 但事件不投递），
  需 `settings put` 重建绑定恢复；这是设备环境问题，非产品缺陷。
