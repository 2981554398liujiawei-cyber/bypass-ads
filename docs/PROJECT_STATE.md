# Bypass Ads 项目状态（唯一项目状态文档）

> 本文件是项目的唯一现状事实源。旧 HANDOVER 文档已标记 ARCHIVED / HISTORICAL，
> 历史审计文档（docs/G5_*.md）仅作过程留档，不代表当前状态。

## 当前架构

- **产品 facade**：`BypassEngine`（`bypass/BypassEngine.kt`）→ 唯一实现
  `GkdBypassEngine`。UI 只碰该接口，不接触 GKD 概念。
- **运行时**：`A11yRuleEngine`（GKD 匹配循环）+ Bypass 层：
  - 广告会话：`BypassDetectionSessions`（一次广告 = 一个 Session，含
    strategy_mode / result / action_attempts / candidate_type /
    confirmed_latency_ms / rule_origin；schema 16）
  - 结果验证：`BypassOutcomeVerifier`（动作后 fresh state 复扫，
    SUCCESS_CONFIRMED / ACTION_NO_EFFECT / UNRESOLVED / MISCLICK_SUSPECTED）
  - 动作预算：`BypassActionBudget`（按 sessionId，1/2/3，硬上限 3）
  - 规则策略：`BypassRulePolicyResolver`（按身份 subsId/groupType/appId/
    groupKey/ruleKey + bypassMode 元数据，名字不作安全边界）
  - 广告上下文：`BypassAdContextTracker`（NONE/WEAK/STRONG，package+activity
    双入口时间；服务重连不是启动）
  - 三档策略：`BypassAdStrategyMode`（保守/激进/疯狂；激进拆分
    glyph vs structural，structural+coordinate 仅疯狂，generic 需 STRONG）
  - 规则栈：`BypassRuleStackManager`（Teach > 官方 override > Local import >
    Bundled > Source global > Fallback；冲突记录；原子 apply）
  - 安全：`BypassSafetyConfig`（`rules/safety_exclusions.json` 单一来源）

## 当前分支

- `codex/gkd-migration-repaired`（修复主线，健康历史 f7001e7 之上）
- `archive/disconnected-r6.2-4118883`（断历史归档，只读）
- 旧 `codex/gkd-migration`（断历史，不再使用）

## 核心入口

- 主 Activity：`app/src/main/kotlin/li/songe/gkd/MainActivity.kt`
- 匹配引擎：`app/src/main/kotlin/li/songe/gkd/a11y/A11yRuleEngine.kt`
- 规则生成：`tools/build_ad_bundle.py`（实现位于 `tools/build_splash_bundle.py`）
- 自用构建：`tools/build_selfuse.ps1`
- 真机采样：`tools/collect_real_ad_samples.py --serial <serial>`

## 产品边界

- 完全离线：默认 flavor 无 INTERNET；不采集聊天/输入/密码/短信/银行卡/完整
  Accessibility tree。
- 广告类别：开屏 / 全屏·插屏 / 营销弹窗 / 其它可关闭广告。
- 默认关闭 generic 高风险：banks/payment/settings/authenticators 等
  仅允许成熟专用规则或审核过的官方 override。
- 坐标规则：仅 package+activity+STRONG 上下文+known rule；无全局坐标。

## 已通过 Gate

- Git 历史恢复 Gate（f7001e7/606182d ancestry、树与断历史一致）
- JVM 单元测试（Bypass 策略/预算/验证/上下文/规则栈/Teach，65+ 用例）
- Python policy / safety / multi-source 测试
- 构建：assembleGkdDebug / assembleGkdDebugAndroidTest / assembleFulltoolsDebug
- Manifest Gate：默认无 INTERNET、allowBackup=false、无 REQUEST_INSTALL_PACKAGES
- 真机 TestAd 矩阵（保守/激进/疯狂 × close/desc/ad_close/×/普通关闭）
- 无障碍 ON/OFF/ON、导航回归、BypassUiTest

## 未通过 Gate（诚实记录）

- **FIELD GATE：PENDING**。真实微信/支付宝小程序广告与普通 App 开屏广告的
  成功率验证在本测试台无法自动触发（广告 SDK 填充不可控），真实样本收集为
  NO_SAMPLE。`tools/collect_real_ad_samples.py` 已就绪，等待真实流量采样。
- 结构 X / 坐标 selector 对真实广告快照仍需实机样本调优（TestAd fixture 已闭环）。
- Shizuku 路径：NO_ENV（无授权环境，R6.1 结论保留）。
