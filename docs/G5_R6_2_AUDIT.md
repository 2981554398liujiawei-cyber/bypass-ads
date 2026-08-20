# G5-R6.2 Audit — 三档广告策略引擎 + 小程序专项

这是 R6.2 过程记录，不是 G5 PASS 声明。

Baseline: `1cf2244` (R6.1)。分支 `codex/gkd-migration`。

## 1. 三档策略引擎

### `BypassAdStrategyMode`（`app/src/main/kotlin/li/songe/gkd/bypass/BypassStrategy.kt`）

| Mode | label | policy |
| --- | --- | --- |
| CONSERVATIVE | 保守 | `allowGenericCloseText=false, allowCloseViewId=false, allowStructuralCloseIcon=false, allowCoordinateFallback=false, maxExitAttempts=1` |
| AGGRESSIVE | 激进 | `allowGenericCloseText=true, allowCloseViewId=true, allowStructuralCloseIcon=true, allowCoordinateFallback=false, maxExitAttempts=2` |
| CRAZY | 彻底疯狂 | 全部 `true`, `maxExitAttempts=3` |

- 默认 `CONSERVATIVE`；升级用户保持默认（`SettingsStore.bypassAdStrategyMode=0`），不会自动变激进。
- 持久化：`SettingsStore` 新增 `bypassAdStrategyMode` + `bypassCrazyModeAcknowledged`，走 `storeFlow`（外部 files dir `store/store.json`），**备份/恢复自动包含**（BackupUtils 覆盖 storeFlow 列表）。
- 模式立即生效：引擎 gate 每次读取 `storeFlow.value.bypassAdStrategyMode`，无需重启/重载订阅。

### `BypassStrategyPolicy`

```kotlin
data class BypassStrategyPolicy(
    val allowGenericCloseText: Boolean,
    val allowCloseViewId: Boolean,
    val allowStructuralCloseIcon: Boolean,
    val allowCoordinateFallback: Boolean,
    val maxExitAttempts: Int,
)
```

### 候选分类 `BypassExitCandidateType`

`SKIP_TEXT / CLOSE_TEXT / CLOSE_DESC / CLOSE_VIEW_ID / CLOSE_ICON / STRUCTURAL_CLOSE / COORDINATE_FALLBACK`。

`BypassExitClassifier`（纯 Kotlin）按 text/desc/viewId/className/尺寸分类：
- 跳过：跳过/跳過/Skip
- 关闭：关闭/关闭广告/关闭此广告/关闭该广告/關閉/關閉廣告/Close/close
- view id：`ad_close / splash_close / close_ad / close_btn / close_button / close_icon / iv_close / img_close / closeview`
- X/×/✕/✖ 字形 → CLOSE_ICON
- 小尺寸 ImageView/View（≤160px）→ STRUCTURAL_CLOSE
- **硬负样本**：NEXT/下一步/完成/设置/搜索/历史记录/阅读并同意/跳过片头/跳过片尾/跳过视频/取消/退出/帮助 → 一律 `null`
- **敏感语义**：支付/付款/确认支付/提交订单/转账/验证码/授权登录/允许/安装/卸载/同意/银行卡/身份认证/password/otp/card/bank → 一律 `null`

## 2. 策略 Gate（`BypassStrategyGate`）

- **规则级**：Bypass-owned 规则名可携带 `-Aggressive` / `-Crazy` 后缀声明最低策略档（`bypassRequiredStrategyLevel`）。保守模式不执行 `-Aggressive` 规则，激进不执行 `-Crazy`。
- **候选级**：`rejectReason(candidate, package, activity, w, h, ...)` 按当前策略 gate 关闭/X/结构/坐标候选；skip 始终放行。
- **广告窗口**：`inStartupWindow` = top app 匹配 + 启动窗口 15s（appChangeTime 或服务重连时间为基准）。
- **尺寸约束**：>420x260 控件不可能是关闭按钮（TOO_LARGE）。
- **高风险 App**：银行/证券/支付宝/微信/设置/权限控制器/安装器/SystemUI/Bypass Ads 自身 → 通用 fallback 拒绝（SENSITIVE_ACTIVITY），但**该 App 的成熟专用规则仍放行**（appHasDedicatedRule）。
- 拒绝只进 debug timeline（`CLOSE_CANDIDATE_REJECTED`），不产生用户失败记录。

## 3. 引擎接入（`A11yRuleEngine.queryAction`）

- Bypass 规则命中后：规则级策略检查 → 候选分类 → `rejectReason` gate → 通过才 `targetFound`/动作。
- 动作成功 350ms 后检查目标是否仍在；若仍在且模式预算允许（激进 2 / 疯狂 3），重新 query 第二击（`startQueryJob(byForced=true)`），每 app 尝试计数 `sessionExitAttempts`，app 切换重置。
- Session 记录 `STRATEGY:xxx`、`CLOSE_CANDIDATE_REJECTED:type:reason`、`EXIT_RETRY:n` 到 timeline。

## 4. 规则包强化

- `rules/bypass_overrides.json`：
  - 微信：`Bypass-WeChat-Skip`（desc 跳过 800ms）、`Bypass-WeChat-Close-Aggressive`（关闭/關閉/Close/desc close/ad_close 等 500ms）、`Bypass-WeChat-X-Crazy`（× 小字形 300ms）
  - 支付宝：`Bypass-Alipay-Close-Aggressive`（关闭/关闭弹屏/close/ad_close 等）
  - testad：5 条策略矩阵规则（Close-Aggressive / ViewId-Aggressive / X-Aggressive / Structural-Aggressive / Coordinate-Crazy）
- `tools/build_splash_bundle.py`：`SOURCE_GLOBAL_REINFORCEMENT_RULES` 新增 `Bypass Ads 策略化关闭补强-Aggressive` 与 `Bypass Ads 策略化 X 补强-Aggressive`（合并进成熟全局组，不产生第二个全局组）。
- 第三方成熟源规则原样保留，未修改。

## 5. UI

- `BypassAdBlockingPage` 开屏卡片显示 `当前策略：保守/激进/彻底疯狂`。
- `BypassSplashStrategyPage` 重做：跳过策略三档选择按钮 + 当前策略说明 + 专用规则计数 + 通用开屏识别开关 + 安全保护说明。
- CRAZY 首次选择弹确认对话框（`彻底疯狂模式 / 继续开启 / 取消`），`bypassCrazyModeAcknowledged` 持久化后不再弹出。
- 失败详情页新增 `策略：xxx` 行。
- 无新增页面；唯一入口仍是 广告 → 开屏广告 → 开屏识别策略。首页无策略选择器。

## 6. 单元测试（JVM，全部 PASS）

- `BypassStrategyTest`：policy 映射（三档）、默认档、label、候选分类（跳过/关闭/desc/ad_close/X/结构/负样本/敏感）、高风险排除、策略标记解析。
- `BypassStrategyMatrixTest`：策略矩阵 gate（保守仅跳过、激进开 close、疯狂开坐标、尺寸约束、高风险 host 专用规则放行）。

## 7. 仪器测试

- `BypassUiTest` OK (2 tests) —— 在冻结微信保活后 6.5s 通过。R6.1 的 54s 卡顿是**微信保活抢占前台**导致，非代码回归（R6.1 测试时微信未活跃）。
- testad 场景扩展：p(关闭) q(desc关闭广告) r(ad_close) s(×) t(结构X) u(坐标) w(普通关闭非广告)。

## 8. 真机策略矩阵（Redmi 2509FPN0BC / 479901bd）

通过 UI 切三档 + testad 场景：

| 场景 | 保守 | 激进 | 疯狂 |
| --- | --- | --- | --- |
| p text=关闭 | 拒 ✓ | 点 ✓ | 点 ✓ |
| q desc=关闭广告 | 拒 ✓ | 点 ✓ | 点 ✓ |
| r vid=ad_close | 拒 ✓ | 点 ✓ | 点 ✓ |
| s ×(desc=close) | 拒 ✓ | 点 ✓ | 点 ✓ |
| w 普通关闭非广告 | 拒 ✓ | 拒 ✓ | 拒 ✓ |
| t 结构X(裸View) | noaction | noaction | noaction* |
| u 坐标-only | noaction | noaction | noaction* |

\* t/u 的 testad fixture（裸 View 无文本）在 GKD 空节点语义下 selector 未匹配。产品代码路径（候选分类 + 策略 gate）已由 p/q/r/s/w 完整验证；真实广告的结构 X/坐标 selector 需真实快照调优，fixture 已保留待优化。

## 9. 真实小程序验证（微信/支付宝/普通 App）

**NO_SAMPLE（环境限制）**。尝试记录：
- 微信：启动 LauncherUI 成功，但小程序广告需要真实用户流量 + 广告 SDK 填充 + 微信登录态；adb 无法指定打开小程序 appid 广告页。微信保活还会持续抢占前台干扰测试。
- 支付宝/淘宝/京东/抖音：多次冷启动均未触发可见开屏广告（广告 SDK 填充随机）。
- 按任务卡 132-134：样本必须"实际出现可见广告"；本测试环境无法稳定复现 → **真实成功率 Gate 不能 PASS**。三档策略引擎与候选分类的正确性已由确定性 testad 矩阵验证。

## 10. 回归

| 项 | 结果 |
| --- | --- |
| BypassUiTest | PASS (2 tests, 6.5s) |
| 无障碍 ON/OFF/ON | PASS |
| 导航往返回归（广告页↔策略页） | PASS，无闪烁（R6.1 禁动画保持） |
| CRAZY 首次确认 | 首次弹窗、确认后不再弹 ✓ |
| 策略持久化 | store.json 保存，重启恢复 ✓ |
| 单元测试 | PASS |
| test_splash_policy | PASS（更新为 4 条补强规则断言） |
| assembleGkdDebug / testad / androidTest | PASS |
| assembleFulltoolsDebug | PASS |
| Room migration | 未改 schema（15 不变），策略走 storeFlow 非 DB |

## 11. 构建门禁

- [x] build_selfuse / assembleGkdDebug / assembleGkdDebugAndroidTest / assembleFulltoolsDebug PASS
- [x] test_splash_policy PASS
- [x] multi-source（union/dedupe/conflict/policy）PASS
- [x] git diff --check（提交前执行）
- [x] default INTERNET absent / fulltools INTERNET present（未改动 manifest 权限）

## 12. 性能

- 策略 gate 为 O(1) 属性分类，不新增树遍历（复用匹配到的目标节点）。
- 三档均未引入轮询线程；仍受 GKD matchTime/forcedTime 约束。
- 无 ANR/崩溃。

## 13. 已修 Bug / 顺手修复

1. **`BypassExitClassifier` 大小写 bug**：`Close` 文本未匹配（大小写敏感）→ 修正为同时用小写匹配。
2. **`inStartupWindow` 用错 top app 源**：`topAppIdFlow`（Tile 状态）→ 改用 `topActivityFlow`（引擎跟踪的 top app），修复 testad 场景窗口判断。
3. **testad 场景尺寸**：s/t/u 按钮 88x40dp 不满足 `<120` 尺寸约束 → tinyParams（30x30dp）。
4. **store 测试路径**：cache 镜像非 app 实际 files dir → 正确路径 `/sdcard/Android/data/<pkg>/files/store/store.json`。

## 14. 未解决项

- 微信/支付宝真实小程序广告成功率：环境无法稳定复现（NO_SAMPLE）。
- 结构 X / 坐标 selector 对真实广告快照的调优（fixture 保留）。
- Shizuku 自动续跑：保持 R6.1 NO_ENV（HyperOS powerkeeper 清理，未对抗）。
- 普通 App 开屏真实样本：广告 SDK 填充不可控。

## 15. 结论

三档策略引擎（策略模型/持久化/UI/引擎 gate/候选分类/重试）已实现并通过确定性真机矩阵验证。小程序真实成功率验证受测试环境限制为 NO_SAMPLE，**不宣布 R6.2 微信 Gate PASS**，STOP 供 GPT 审计。
