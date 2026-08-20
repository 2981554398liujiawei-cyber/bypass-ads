# G5-R6.3.2 审计 — 真实生产 high-risk gate 修复 + Session-Scoped Verifier + 持久化 Provenance

> 分支：`codex/gkd-migration-repaired`（禁止回退 `codex/gkd-migration`、禁 force push）
> 设备：Redmi 2509FPN0BC（HyperOS / Android 16 / API 36 / BP2A.250605.031.A3，adb -s 479901bd）
> 日期：2026-08-2x
> 前置：R6.3.1 审计（CODE GATE PASS）后，GPT 源码审计指出真实生产路径 high-risk gate 可被绕过等 P0-1..P0-6。

## 0. Gate 结论（最终状态）

- **CODE GATE：PASS**（P0-1..P0-6 全部修复 + 117 JVM 测试 + 3 个 assemble build gates + Python gates，见下）
- **STAR-CHARGE FIELD GATE：PENDING**（本轮不加刷真实广告库存；先以基于真实观测结构的代码 fixture 完成回归，真实 splash 出现后再继续 Field Gate）
- **GENERAL FIELD GATE：PENDING**（真实广告流量样本不足）

## 1. P0-1 真实生产 high-risk gate 修复（结构化 Rule Origin + 共享 execution gate）

**问题（真实生产路径，非单测可见）**：
- `BypassRulePolicyResolver.trustOf` 用 `bypassMode != null => BYPASS_OVERRIDE` 推断来源；而生产解析 `RawSubscription.parse`（手动 `jsonToRuleRaw`/`jsonToGlobalRule`）**丢弃了 bypassMode**（R6.3.1 单测直接构造 RuleIdentity 所以看不到）→ 生产路径上所有规则 bypassMode=null。
- 微信 `Bypass-WeChat-Skip`（key 200）本身无 bypassMode → 被认成 `BUNDLED_DEDICATED`。
- `A11yRuleEngine` 中 `isDedicated = trust == BUNDLED_DEDICATED` 时 `reject = null` → high-risk 宿主（微信/支付宝）上的 dedicated 规则**完全绕过 execution gate**；`candidate ?: SKIP_TEXT` 把无语义候选伪装成 skip。

**修复**：
1. **结构化 origin 字段**（RawSubscription.kt）：`RawRuleProps` 增加可序列化 `bypassOrigin: String?`，`RawAppRule`/`RawGlobalRule` 增加字段，`jsonToRuleRaw`/`jsonToGlobalRule` 解析（并补上被丢弃的 `bypassMode`）。`rules/bypass_overrides.json` 全部 12 条规则显式加 `"bypassOrigin": "OVERRIDE"`（含无 bypassMode 的 200/105），bundle 生成脚本 `apply_overrides` 原样保留规则字段。
2. **origin 与 minimumMode 分离**（BypassRulePolicy.kt）：`trustOf` 优先读持久化 origin（OVERRIDE→BYPASS_OVERRIDE、LOCAL_IMPORT→LOCAL_IMPORT_*），再 teach 前缀、side-map、最后 subsId/isGlobal 回退；`bypassMode` **不再参与来源推断**。minimumModeOf 不变（BYPASS_OVERRIDE 仍按 bypassMode，null→CONSERVATIVE，即微信 Skip=CONSERVATIVE）。
3. **共享 execution gate**（BypassStrategyGate.kt）：新增 `evaluateExecution(candidate?, ...)`，引擎与生产路径测试调用**同一个函数**：
   - high-risk 宿主上 `BUNDLED_DEDICATED`/`BYPASS_OVERRIDE` 仅豁免"来源不可信"，之后仍走 candidate → startup window → size → strategy → ad context → semantic；
   - `candidate == null`（无退出语义/负向语义）对**任何来源都是硬拒绝**（NEGATIVE_SEMANTIC），`candidate ?: SKIP_TEXT` 伪装已删除；
   - 非 high-risk 宿主的成熟 `BUNDLED_DEDICATED` 保持低成本可信路径（仍需真实候选）。
   - `rejectReason` 保留为委托包装（旧测试兼容）。
4. `A11yRuleEngine` B/C 段改为统一调用 `evaluateExecution`（删除 isDedicated 直通与 SKIP_TEXT 伪装），诊断 detail 携带 trust。

**验收测试**（BypassStrategyMatrixTest，全部调用 `evaluateExecution`）：
- 微信官方 Skip（origin=OVERRIDE、无 bypassMode）+ OUTSIDE_WINDOW => NO
- 微信官方 Close + context NONE => NO；+ STRONG + inWindow + Aggressive => YES
- high-risk bundled dedicated + OUTSIDE_WINDOW => NO；+ candidate=null => NO；+ inWindow + 真实语义 => ALLOW
- normal app mature dedicated + 真实候选 => 低成本路径 ALLOW（inWindow=false 也 ALLOW）；+ candidate=null => NO
- high-risk 上 LOCAL_IMPORT 即使 STRONG+CRAZY+inWindow 仍 SENSITIVE_ACTIVITY
- AppBrandUI alone（WEAK）+ structural + Crazy => NO_AD_CONTEXT；AppBrandUI + 真实证据 + structural + Crazy => YES（P0-3）

**解析层测试**（RulePolicyResolverTest）：origin=OVERRIDE 无 bypassMode => BYPASS_OVERRIDE + CONSERVATIVE；bypassMode 单独不再推断 override（BUNDLED_DEDICATED）；origin=LOCAL_IMPORT 在 BYPASS subsId 下仍 LOCAL_IMPORT_*。

## 2. P0-2 Session-Scoped Verifier

**问题**：`hasFreshAdCandidate()` 扫最多 24 条所有 Bypass 规则、`scanFreshAdLabel()` 窗口内任意广告 label 都算"广告仍在" → 无法判断"刚才那个广告"；星星充电正常页面的瓜子/GNC banner 会误判 ACTION_NO_EFFECT。

**修复**：
1. **Session 证据**（BypassDetectionSession schema 17 + BypassDetectionSessions）：保存 acted candidate type（已有 candidateType）、结构化 rule identity（`acted_rule_key`/`acted_group_key`）、候选 bounds（`acted_candidate_bounds` "l,t,r,b"）；`noteCandidate` 记录，`activeSessionEvidence()` 提供给 verifier。
2. **Fresh verifier 按 session 查询**（BypassOutcomeVerifier 重写 + 引擎）：
   - 新签名 `verify(packageName, sessionEvidence, freshWindowProvider, topFallbackProvider, freshSameAdProvider, freshProximityAdLabelProvider)`；
   - `hasSameAdCandidate`：只查询 session 的 acted rule（subsId+groupKey+ruleKey 精确匹配），重新匹配且 bounds 与 acted bounds 同区域（中心距 ≤ 500px）才算"同一广告仍在"；规则已不存在/不匹配 => 广告已消失；
   - `scanFreshAdLabelNear`：广告 label 与 acted candidate 同区域才算"广告 overlay 仍在"；无关 banner（页面中部的瓜子/GNC）不阻止 SUCCESS；
   - 判定链提取 `decideFromVerify`（纯函数，JVM 可测）。
3. `decide`：external landing => MISCLICK_SUSPECTED；topChanged => UNRESOLVED；无 session evidence => UNRESOLVED；same-ad 仍在 / 邻近 label => ACTION_NO_EFFECT；否则 SUCCESS_CONFIRMED。

**验收测试**（OutcomeVerifierTest）：
- same AppBrandUI + no same-ad evidence => SUCCESS
- same AppBrandUI + unrelated banner"广告"（>500px）=> SUCCESS
- same AppBrandUI + unrelated small ImageView（可匹配 Crazy structural selector）=> SUCCESS
- same splash candidate remains => ACTION_NO_EFFECT
- external Chrome => MISCLICK；fresh root 不可用 => UNRESOLVED；无 session evidence => UNRESOLVED
- **STAR-CHARGE fixture**：before=AppBrandUI+splash Skip candidate（bounds 972,192,1152,265），after 仍 AppBrandUI + splash 消失 + 瓜子[78,1221]/GNC[93,1890] banner => SUCCESS_CONFIRMED，绝非 ACTION_NO_EFFECT
- bounds 数学：`sameAdRegion` 中心距、`parseBounds` 容错

## 3. P0-3 STRONG AdContext 修正

**问题**：`isMiniProgramAdActivity`（AppBrandUI/XRiverActivity）alone 直接 => STRONG，而星星充电主页/场站页/会员页都在 AppBrandUI。

**修复**（BypassAdContext.evaluate）：AppBrandUI alone => **WEAK**；STRONG 仅来自真实广告证据：explicit Skip candidate（windowSkipSeen）或广告 label（windowAdLabelSeen）；close candidate + fresh entry => WEAK；其余 NONE。

**测试**（AdContextTrackerTest + BypassStrategyMatrixTest）：AppBrandUI alone => WEAK；AppBrandUI + skip evidence => STRONG；normal AppBrandUI + no evidence + small ImageView + Crazy => NO ACTION；AppBrandUI + real ad evidence + structural + Crazy => YES。

## 4. P0-4 Provenance 跨进程可靠 + groupKey collision

**问题**：`BypassRuleProvenance` 纯内存 ConcurrentHashMap 不能作安全边界；groupKey 冲突（bundled app=A key=10 name=X vs local app=A key=10 name=Y）会共享 DB identity、bundled 被误标 local、config 相互覆盖。

**修复**：
1. **持久化**：provenance 镜像到 `filesDir/bypass_provenance.json`；`restore()` 在任何 rule resolution 前惰性恢复（`trustOf` 首次查询即触发），`GkdBypassEngine.init` 显式 restore；merge 时写入、`clearLocalImport`/空 local 时删除。JVM 测试无 app 上下文时安全跳过文件 IO。
2. **groupKey collision**：merge 时同 app 下 local 组 key 与保留的 bundled 组 key 冲突（name 不同）=> local 组 remap 到稳定唯一 key（`0x40000000 + hash(appId|name)`，冲突递增），bundled 保留原 key；side-map 只记录**最终 key**（否则 bundled 共享旧 key 会被误标 local）；冲突记录 `LOCAL_IMPORT_REMAPPED(remappedFromKey)`；同名替换的 LOCAL_IMPORT 冲突只在 bundled 真有同名组时记录。
3. **bundled upgrade**：升级 bundle（新版本+新组）后 local import 组、其 LOCAL_IMPORT origin、稳定 key 全部保留（DB 配置按 appId+groupKey 依旧有效）。

**测试**（RuleStackManagerTest）：
- `local_provenance_survives_process_restart`：merge → snapshotJson（模拟持久化）→ clear（新进程内存空）→ restoreFromJson（模拟加载持久化文件）→ resolve effective 订阅 => LOCAL_IMPORT_DEDICATED；bundled 组仍 BUNDLED_DEDICATED
- `same_key_different_name_collision_is_remapped_not_shared`：两组并存不同 key、bundled 不误标 local、local 解析 LOCAL_IMPORT_DEDICATED、remap 稳定可复现
- `bundled_upgrade_keeps_local_import_teach_and_origin`：v1 merge → v2 bundle（新组）→ 再 merge，local 组/origin 保留、新 bundled 组正确 BUNDLED_DEDICATED

## 5. P0-5 Window Anchor 四态模型

**问题**：`BypassWindowAnchor` 用空串作唯一语义；`onTopActivityChanged` 用 `putIfAbsent` 使 A→B→A→B 第二次 B 不刷新 packageEntryTime；`onAppObserved` 无条件覆盖。

**修复**（BypassAdContext.kt + A11yRuleEngine）：
- 显式四态 `BypassWindowAnchorObservationType`：`SERVICE_RECONNECT`（空基线首观察，只建基线） / `REAL_PACKAGE_CHANGE`（刷新 packageEntryTime） / `REAL_ACTIVITY_CHANGE`（事件驱动，刷新 activityEntryTime） / `ENGINE_FALLBACK_OBSERVATION`（同 app 重读，不刷新）；
- `onTopActivityChanged`：package 变化才覆盖 packageEntryTime（tracker 记录 lastEventPackage）；activity 变化只刷 activityEntryTime；
- `onAppObserved(pkg, time, type)`：仅 REAL_PACKAGE_CHANGE 刷新；
- 引擎锚定块 `classifyObservation` 后仅 REAL_PACKAGE_CHANGE 调 `onAppObserved`；`shouldReanchorOnObservation` 保留为兼容委托。

**验收测试**（AdContextTrackerTest）：
- A→B activity=null => B startup window YES
- B→A→60s→B activity=null => 第二次 B startup window YES（事件驱动覆盖刷新）
- 停在 B 60s → service reconnect → first fresh root B（SERVICE_RECONNECT）=> startup window NO
- WeChat LauncherUI→AppBrandUI（REAL_ACTIVITY_CHANGE）=> fresh activity window YES
- ENGINE_FALLBACK_OBSERVATION 60s 后不刷新

## 6. P0-6 Privacy-safe Field Gate 工具

**问题**：`tools/relaunch_mp.py`（保存完整 uiautomator XML 到本地 + tmp_round_N 写全部 nodes + 输出全部页面文字）、`tools/ui_auto.py`（`--out` 保存完整 XML + dump 全文本）、`pull_sessions.py`（`SELECT *` 全列）。

**修复**：
- `relaunch_mp.py` / `ui_auto.py` 重写：设备 dump 走 adb cat 管道读取、**设备端立即删除**，不落地任何本地文件；只输出 AD 白名单命中的 text/desc/viewId（截断 40），其余一律 `<redacted>`；bounds/class/clickable 保留（非内容）；删除 tmp_round / --out。
- `pull_sessions.py`：系统 temp + 用完删除；只 SELECT 元数据列（不含 candidate_snapshots / matched_rules / diagnostic_timeline）。
- `collect_real_ad_samples.py`：SELECT_COLS 去掉 diagnostic_timeline。
- 运行时快照（BypassCandidateSnapshot）即 privacy-safe AdEvidenceSnapshot：字段白名单（package/activity/candidate type/class/bounds/clickable/ad-related text-desc-viewId/rule identity/strategy/session/attempt/outcome），sanitizer 滤敏感 token（密码/验证码/银行卡/手机号）、截断 80、跳过 editable 节点。
- 新增 `tools/test_privacy_policy.py`（PRIVACY POLICY PASS）：静态断言工具无完整 XML 落盘、无全文本输出、无 SELECT *、有白名单+redaction+temp 清理；运行时 sanitizer 携带敏感 token/截断/editable 过滤；.gitignore 覆盖 /tmp_*。
- 新增 `AdEvidenceSnapshotTest`（JVM）：快照字段 ⊆ 白名单；模型无 input/chat 内容字段。

## 7. P1 保留确认（未回退）

- ActionBudget：delay 前不扣额度、effectiveMaxAttempts=min(mode,rule,3)（BypassRuntimeFlow）
- verifier delay 后重读 top（decideFromVerify 保留）
- English ad word boundary、FAILURE_CONFIRMED terminal、Home 最近触发来自 Session
- 相关测试原样通过。

## 8. 测试统计

- JVM 单元测试：`testGkdDebugUnitTest` 117 tests / 0 failed（原 86 + 新增 31：P0-1 生产路径 9、P0-2 verifier/bounds/star-charge 12、P0-3 context 2、P0-4 collision/restart/upgrade 3、P0-5 anchor 5、P0-6 snapshot 2，另 TeachPolicy/RulePolicyResolver 调整）
- build gates：`:app:assembleGkdDebug` / `:app:assembleGkdDebugAndroidTest` / `:app:assembleFulltoolsDebug`（见 §9）
- Python gates：test_splash_policy（PASS）、test_safety_exclusions（4 PASS）、test_multi_source（2 PASS）、test_privacy_policy（PASS）
- check_repo_integrity.py：ok（467 tracked files, healthy ancestry f7001e7c）；`git diff --check` 干净

## 9. 设备 / 环境

- Redmi 2509FPN0BC，HyperOS / Android 16 / SDK 36 / display.id=BP2A.250605.031.A3
- debug applicationId=app.bypassads.debug；DB `/storage/emulated/0/Android/data/app.bypassads.debug/files/db/gkd.db`
- 本轮为纯代码轮：未刷真实广告库存，未安装 APK，无 Field Gate 现场数据新增（STAR-CHARGE 结论沿用 R6.3.1：8 次启动 0 splash、2 例真实横幅 0 误触）

## 10. STAR-CHARGE REGRESSION（代码 fixture）

基于 R6.3.1 真实观测结构（正常页=AppBrandUI00+充电站列表/场站详情/会员页；页内 banner：瓜子二手车[广告 label 78,1221 / feedback_icon 144,1230 / CTA"详情"285,1653]、GNC[广告 label 93,1890 / CTA"立即购买"918,2121]；无跳过/关闭控件）：
- fixture before=AppBrandUI+splash Skip candidate（972,192,1152,265），after=仍 AppBrandUI+splash 消失+瓜子/GNC banner => SUCCESS_CONFIRMED（绝不能 ACTION_NO_EFFECT）
- after 正常页面 unrelated 小 ImageView 能匹配 Crazy structural selector、Session=CONSERVATIVE => 仍 SUCCESS_CONFIRMED（verifier 只查 session acted rule/region）
- banner/CTA 永不误点：gate 层 candidate 语义为空 => NEGATIVE_SEMANTIC；structural 需 STRONG 而正常页 AppBrandUI 仅 WEAK => NO_AD_CONTEXT

**后续 Field Gate**：真实 splash 出现时验证——splash 成功关闭 → 落到带瓜子/GNC banner 正常页面 → Session 必须 SUCCESS_CONFIRMED → 不得继续点 banner/CTA。

## 11. 提交信息

- commit SHA：见 §12（提交后回填）
- Git ancestry：健康历史 f7001e7c（archive/disconnected-r6.2-4118883 保留旧断历史）

---

**STOP FOR GPT AUDIT**
