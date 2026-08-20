# G5-R6.3.1 审计 — Runtime V2 正确性修复 + 星星充电真实 Field Gate

> 分支：`codex/gkd-migration-repaired`
> 设备：Redmi 2509FPN0BC（HyperOS / Android 16 / API 36，`adb -s 479901bd`）
> 日期：2026-08-20
> 前置：GPT 源码审计（R6.3 不接受 CODE GATE PASS）列出的 P0-1..P0-6 + P1。

## 0. Gate 结论（最终状态）

- **CODE GATE：PASS**（P0/P1 全部修复 + JVM 测试 + build gates，见下）
- **STAR-CHARGE FIELD GATE：PENDING**（真实开屏广告样本 N=0<5，如实报告；
  已捕获真实页内横幅 2 例并验证 0 误触，见第 12 节）
- **GENERAL FIELD GATE：PENDING**（真实广告流量样本不足）

## 1. P0-1 ActionBudget 时序（Runtime V2）

问题：`reserveAttempt()` 原先在 actionDelay 判断之前执行，等待延迟也会消耗预算。

修复（`BypassRuntimeFlow` + `A11yRuleEngine`）：
- 新增纯运行时时序契约 `BypassRuntimeFlow`：match → policy/context gate →
  actionDelay（只 WAIT，不消耗预算）→ status/outdate → 即将 performAction
  前 `reserveAttempt(sessionId)` → performAction → verifier。
- `effectiveMaxAttempts = minOf(mode.policy.maxExitAttempts,
  rulePolicy.maxAttempts, 3)`（原先漏了 rulePolicy.maxAttempts）。
- 任何仅 schedule delay / query / selector miss 一律不消耗 attempt。

硬验收（`BypassRuntimeFlowTest`）：
- Conservative 首次匹配 delayed Skip → WAIT_FOR_DELAY，等待期间
  attemptsUsed=0；真正 performAction 前 PROCEED+reserve → 1；
  最终该广告 actionAttempts=1。
- effectiveMaxAttempts：CONSERVATIVE=1 / AGGRESSIVE=2 / CRAZY=3；
  rule cap（actionMaximum）可收紧；硬上限 3。

## 2. P0-2 mini-program OutcomeVerifier

问题：AppBrandUI/XRiverActivity 仍在 = 广告仍在 是错误的：微信小程序广告
关闭后小程序本体仍运行在 AppBrandUI。

修复（`BypassOutcomeVerifier` 重写）：
- 去掉 `shellContextStrong`（AppBrandUI 不再作为"广告仍在"证据）。
- 改为 fresh evidence：Action → delay → 重新读取 top package/activity →
  browser/store/launcher → MISCLICK_SUSPECTED；fresh root → fresh 广告出口
  查询 + fresh 广告 label 扫描（`BypassAdContextTracker.scanFreshAdLabel`，
  一次性、不持久化）→ 有真实广告证据 ACTION_NO_EFFECT；无广告证据且目标
  小程序仍正常 → SUCCESS_CONFIRMED；无法判断 → UNRESOLVED。
- AppBrandUI/XRiverActivity 仍保留**动作前**建立 STRONG 上下文的作用
  （`BypassAdContextTracker.evaluate` / `isMiniProgramAdActivity` 不变）。

新增测试（`OutcomeVerifierTest`）：
- WeChat AppBrandUI：before ad candidate exists → after 同一 AppBrandUI +
  无广告候选 + 无广告 label => SUCCESS_CONFIRMED。
- fresh ad label 仍在 → ACTION_NO_EFFECT。

## 3. P0-3 High-risk override 不得绕过安全 Gate

问题：微信/支付宝 BYPASS_OVERRIDE 在 high-risk 判断后直接 return allowed。

修复（`BypassStrategyGate.rejectReason`）：
- high-risk host：不可信来源（BUNDLED_GLOBAL/LOCAL_IMPORT_*/TEACH_*）
  → DENY（SENSITIVE_ACTIVITY）。
- BUNDLED_DEDICATED / BYPASS_OVERRIDE：允许继续检查 → strategy →
  startup window → size → STRONG context → semantic safety → action。
- BYPASS_OVERRIDE 只豁免"来源不可信"，不能豁免 context/window/size/strategy。

新增测试（`BypassStrategyMatrixTest`）：
- 微信 override + OUTSIDE_WINDOW => NO（OUTSIDE_WINDOW）
- 微信 override + context NONE => NO（NO_AD_CONTEXT）
- 微信 override + STRONG + inWindow => YES（null）
- 微信 override + CLOSE_TEXT 在 CONSERVATIVE => STRATEGY_GATE
- high-risk 上 BUNDLED_DEDICATED 也不能绕过 window（OUTSIDE_WINDOW）

## 4. P0-4 Local Import provenance

问题：merge 把 import id 改写为 BYPASS_SPLASH_SUBS_ID，Resolver 靠
`subsId != BYPASS_SPLASH_SUBS_ID` 判断 LOCAL_IMPORT_* 已失效。

修复（`BypassRuleProvenance` + `BypassRuleStackManager` +
`BypassRulePolicyResolver`）：
- 结构化 side-map：appId+groupKey（app 组）/ global+groupKey（全局组）。
- 仅在 merge 时由 `mergeBundledAndLocal` 填充；`clearLocalImport`/无 local
  层时清空，永不离散。
- Resolver `trustOf` 先查 side-map → LOCAL_IMPORT_DEDICATED / GLOBAL；
  不依赖规则名猜测 origin。
- `RuleIdentity` 增加 appId/groupKey 字段。

新增真实 integration 测试（`RuleStackManagerTest`，非假 subsId=999 单测）：
- parse local import → merge bundled → 构造 effective ResolvedRule
  （AppRule / GlobalRule）→ `BypassRulePolicyResolver.resolve` =>
  LOCAL_IMPORT_DEDICATED / LOCAL_IMPORT_GLOBAL。
- 反例：未被替换的内置组仍解析为 BUNDLED_DEDICATED；清空 import 后
  side-map 清除。

## 5. P0-5 OutcomeVerifier 二次检查 top app

问题：300ms verifier delay 后必须重新读取 topActivity；原先在 delay 前
检查 topActivityFlow，跳转发生在 delay 内会漏判。

修复：`BypassOutcomeVerifier.verify` 先 `delay(VERIFY_DELAY_MS)`，随后
用 freshWindowProvider（fresh root 的 package）优先、topFallbackProvider
（事件驱动 topActivityFlow）兜底重新读取 top package；freshTop != 原包 →
external 判 MISCLICK_SUSPECTED、其它判 UNRESOLVED。

新增测试：
- performAction=true → 200ms 后跳 Chrome → 300ms verifier（window 读不到、
  fallback=Chrome）=> MISCLICK_SUSPECTED，绝不 SUCCESS_CONFIRMED。
- 跳到未知应用 => UNRESOLVED；window 读不到且 top 未变 => UNRESOLVED。

## 6. P0-6 reconnect 不得重新打开 startup window

问题：新 A11yRuleEngine / service reconnect 时 observedTopApp 初始为空，
第一次 fresh root 读取会 `onAppObserved(now)` 重开窗口。

修复（`BypassWindowAnchor` + `A11yRuleEngine`）：
- 纯函数 `shouldReanchorOnObservation(previous, fresh)`：空基线（reconnect/
  全新启动）首次读取只建立基线，不 re-anchor；只有真实 top-app transition
  （基线非空且不同）才 re-anchor。
- 引擎锚定块改用该判定；同应用反复读取从不刷新窗口。

新增测试（`AdContextTrackerTest`）：
- normal screen 停留 60s → service reconnect（空基线）→ 首次 fresh root
  不 re-anchor → inStartupWindow/freshEntry == false。
- 真实 A→B transition 仍会 re-anchor。

## 7. P1 同步修复

- English ad label 边界匹配：`ad|ads|advertisement|sponsored` 用词边界
  regex（`(?<![a-z0-9])...(?![a-z0-9])`）；address/badge/read/adapter/radio/
  ready 不再命中（`BypassStrategyTest.english_ad_label_uses_word_boundaries`）。
- scheduled finalization 必须 terminal：`scheduleFinalization` 落盘
  FAILURE_CONFIRMED 后 `active = null`，不再复用同一 active session
  （`BypassDetectionSessions`）。
- Home "最近触发" 改为 latest finalized Session（`latestAction` 由
  `sessionRecords` 派生，排除 OPEN）；原始 ActionLog 仅保留在
  Advanced/debug 页面。
- PROJECT_STATE / G5_R6_3_AUDIT 修正：TestAd 是合成 fixture，不是真实
  Field Gate；真实 Field Gate 保持 PENDING。

## 8. 新增/变更文件

- 新增：`BypassRuntimeFlow.kt`、`BypassRuleProvenance.kt`
- 变更：`A11yRuleEngine.kt`、`BypassOutcomeVerifier.kt`、
  `BypassStrategyGate.kt`、`BypassRulePolicy.kt`、`BypassRuleStack.kt`、
  `BypassAdContext.kt`、`BypassStrategy.kt`、`BypassDetectionSessions.kt`、
  `GkdBypassEngine.kt`、`BypassEngine.kt`
- 测试：`BypassRuntimeFlowTest.kt`（新）、`RuleStackManagerTest.kt`、
  `OutcomeVerifierTest.kt`、`BypassStrategyMatrixTest.kt`、
  `BypassStrategyTest.kt`、`AdContextTrackerTest.kt`、`TeachPolicyTest.kt`

## 9. 构建门禁

- `:app:testGkdDebugUnitTest`（新增 Runtime 集成用例后全绿）
- `:app:assembleGkdDebug` / `:app:assembleGkdDebugAndroidTest` /
  `:app:assembleFulltoolsDebug`
- test_splash_policy / test_safety_exclusions / test_multi_source
- check_repo_integrity、git diff --check

## 10. 星星充电 Field Gate 前置检查

- 设备：Redmi 2509FPN0BC（Android 16 / API 36 / BP2A.250605.031.A3）
- 微信 com.tencent.mm 已安装；支付宝 com.eg.android.AlipayGphone 已安装
- 先确认 Accessibility event 流健康后再开始；服务异常只做一次干净 rebind
  并记录原因；不反复 force-stop。

## 11. 星星充电 Field Gate 方法

- 目标：用户真实打开星星充电小程序时出现的真实开屏广告（优先微信宿主）。
- 第一轮只观察并分类真实候选（SKIP_TEXT / CLOSE_TEXT / CLOSE_DESC /
  CLOSE_VIEW_ID / CLOSE_ICON / STRUCTURAL_CLOSE / COORDINATE_FALLBACK /
  NONE），不预设按钮。
- 只采广告相关最小证据：package / activity / strategy / sessionId /
  rule-trust / candidate type / candidate class / bounds / ad text-desc-viewId /
  attempt / ActionResult / OutcomeVerifier result / final SessionResult /
  latency。禁止聊天/输入/密码/银行卡/完整 UI tree。
- 策略矩阵按真实候选分类决定期望（Skip 三档 YES；Close/ad_close/×：
  CONSERVATIVE NO / AGGRESSIVE YES / CRAZY YES；structural/coordinate：
  仅 CRAZY YES）。
- 一个广告 = 1 Session = 1 success（N actions 但只 +1）。
- 期望 YES 的策略：同一真实广告至少 5 次独立曝光；5/5 广告真正消失、
  5/5 无误触、5/5 Session 结果正确。N<5 如实报告 N/N，FIELD GATE=PENDING。
- 期望 NO 的模式至少验证 3 次，不得误点正常小程序页面控件。

## 12. 星星充电真实轮次（实机执行，2026-08-20 23:20-23:40 CST）

### 12.1 设备与前置

- 设备：Redmi 2509FPN0BC（HyperOS / Android 16 / API 36 / BP2A.250605.031.A3）
- 安装 `app-gkd-debug.apk`（gkd debug flavor，applicationId=app.bypassads.debug），
  通过 `settings put secure enabled_accessibility_services` 开启无障碍。

### 12.2 第一轮（前置健康检查）：发现 HyperOS 无障碍假死并修复

- 症状：dumpsys 显示服务 **Bound**，但 `activity_log_v2` 停在 22:18（重装前），
  当前进程收不到任何 `onNewA11yEvent`；app UI 显示"正在恢复"。
- 原因：`adb install -r` 重装后 HyperOS 把无障碍服务留在"bound 但事件不投递"
  的假死状态（与已知 HyperOS 问题一致）。
- 处置：按审计规则**只做一次干净 rebind**（settings 先清空再写回），
  事件流立即恢复（`onNewA11yEvent type:32 app:com.tencent.mm`、
  `A11yRuleEngine` query job 随 WeChat 内容事件持续触发）。
- 附带发现（环境/状态层已知问题，不影响引擎）：HyperOS bind flap
  （destroy→rebind 复用同一 service 实例）后 `A11yService.isRunning` 因只由
  onCreate/onDestroyed 驱动而保持 false，UI 长期显示"正在恢复"；
  引擎不读该标志、无自禁用逻辑，功能不受影响；进程重启可恢复显示。

### 12.3 真实候选观察与分类

| 轮次 | 入口 | 结果 | 分类 |
|---|---|---|---|
| L1-L5 | 使用过的小程序（warm） | 无开屏广告（直达充电站列表首页） | NONE |
| L6 | 冷启动微信后经搜索进入 | 无开屏广告 | NONE |
| L7 | 搜索小程序入口 | 无开屏广告 | NONE |
| L8 | 搜索网络结果入口 | 无开屏广告 | NONE |
| D1 | 点场站卡片→场站详情 | **真实广告 #1：瓜子二手车横幅** | NONE* |
| D2 | 会员页（未登录） | **真实广告 #2：GNC 营养包横幅** | NONE* |

*真实广告详情（最小证据，非完整树）：

1. **场站详情页横幅**（AppBrandUI00，com.tencent.mm）：
   - `广告` label：TextView bounds=[78,1221][138,1254]
   - `feedback_icon`：Image bounds=[144,1230][162,1245]
   - 文案："闲置爱车出手选瓜子，个人卖个人，同款车轻松多卖 10%"
     TextView bounds=[678,1218][1134,1341]
   - 广告主："瓜子二手车" TextView bounds=[738,1500][921,1551]
   - CTA："详情" TextView bounds=[285,1653][387,1719]
2. **会员页横幅**（AppBrandUI00，com.tencent.mm）：
   - `广告` label：TextView bounds=[93,1890][156,1923]
   - `feedback_icon`：Image bounds=[159,1902][180,1917]
   - 文案："熬夜男生真的要注意爱护身体，GNC男士营养包一天一包多方位补充"
     TextView bounds=[291,1887][1119,2031]
   - 广告主："GNC-健安喜官方正品" TextView bounds=[351,2127][708,2175]
   - CTA："立即购买" TextView bounds=[918,2121][1083,2181]

结论：星星充电小程序内的真实广告为**页内横幅**（WeChat 广告组件，带强制
`广告` 标识 + feedback_icon + 广告主 + CTA），**没有跳过/关闭按钮**；
按候选分类法归为 NONE（无 SKIP_TEXT/CLOSE_TEXT/CLOSE_DESC/CLOSE_VIEW_ID/
CLOSE_ICON/STRUCTURAL_CLOSE 候选）。

### 12.4 引擎对真实广告的行为

- logcat：`A11yRuleEngine: bypass rule matched: 开屏广告-微信小程序/null
  status=StatusOk`（AppBrandUI 窗口被命中）→ 无候选 → **未建 session、
  未执行任何动作**。
- 23:00 后新增 session 数：0；actionAttempts：0；误触：0。
- 正确性：这些横幅的 CTA（详情/立即购买）点击会打开广告落地页，属于
  MISCLICK；引擎不点 = 符合策略矩阵对 NONE 候选的行为（无动作）。

### 12.5 结论

- 开屏广告（splash）：8 次独立启动（warm + 冷启动）均未出现填充
  （广告 SDK 无库存/频控），**N=0 < 5，如实报告**。
- 真实广告样本：2 个页内横幅（瓜子二手车 / GNC），候选分类 NONE。
- **STAR-CHARGE FIELD GATE：PENDING**（真实开屏广告样本 N<5，无法验证
  期望 YES 策略 5/5 广告消失；已捕获真实横幅并验证 0 误触）。
- GENERAL FIELD GATE：PENDING（真实广告流量样本不足）。
- 期间未出现任何误触、误点正常控件；引擎事件流健康（rebind 后持续投递）。

### 12.6 环境留证

- getprop：ro.build.version.release=16，ro.build.version.sdk=36，
  ro.build.display.id=BP2A.250605.031.A3，ro.product.model=2509FPN0BC
- 无障碍：Bound services=[Bypass Ads]，Enabled services 含该组件；
  app.bypassads.debug 进程持续处理 com.tencent.mm 事件。


## 13. 最终提交报告要素

- commit SHA；Git Gate；JVM tests；build gates；新增 regression tests；
  星星充电真实广告每轮结果、session rows、候选分类、最终策略矩阵；
  失败/成功关键日志证据；设备 getprop（release/sdk/display.id）。
- 最终状态：CODE GATE PASS/FAIL；STAR-CHARGE FIELD GATE
  PASS/PENDING/FAIL；GENERAL FIELD GATE PASS/PENDING。
