# Bypass Ads 构建工具

Bypass Ads 的开屏规则栈统一由 `build_splash_bundle.py` 生成，`build_selfuse.ps1`
一键完成"完整自用 APK"的构建与自检。

## 规则栈结构

```text
第三方成熟开屏规则（本地输入，不进 Git）
        ↓ 只过滤 开屏广告* 组
rules/bypass_overrides.json（Bypass 自有补丁，进 Git）
        ↓ merge（appId + group name，幂等）
宿主专项规则（微信 / 支付宝小程序 desc 补丁，位于 overrides）
        ↓
保守型通用开屏 fallback（global group「开屏广告-通用跳过」）
        ↓
app/src/main/assets/bypass_splash_rules.local.json（生成物，gitignored）
```

运行时表现为一个统一的「Bypass Ads 开屏规则」订阅，用户不感知这些来源层级。

## 测试规则源（第三方输入）

- 上游：`Lin-arm/GKD_subscription`
- 本机测试版本：v567（对应 `tools/.cache/lin-arm-gkd.json5`，gitignored）

使用方式（开发者自取输入，构建脚本**不会**自动联网下载）：

1. 手动获取第三方 GKD 订阅文件（JSON/JSON5），放入 `tools/.cache/`；
2. 运行 generator 或一键构建（见下）。

> 许可声明：第三方规则未获授权在本仓库或公开 APK 中再分发；生成的
> `.local.json` 仅用于本地个人自用构建。仓库只跟踪 generator、validator、
> `rules/bypass_overrides.json`、自有 fixture 与文档。

## 生成 bundle（含 validator）

```powershell
python tools\build_splash_bundle.py tools\.cache\lin-arm-gkd.json5
```

校验项（不满足即 FAIL）：

- 全部 app group 名为「开屏广告」或「开屏广告-*」；
- `globalGroups` 只允许 Bypass 自有的通用 fallback；
- QQ音乐 / 微信 / 支付宝 存在；
- QQ音乐保留 `clickCenter` 开屏规则；
- 微信含「开屏广告-微信小程序」组、支付宝含「开屏广告-小程序开屏广告」组。

输出末尾打印 `apps/groups/rules` 统计与 `output sha256`。

## 幂等性

同一输入连续运行两次，输出 SHA256 一致；overrides 不会重复追加。

## 一键完整自用构建

```powershell
.\tools\build_selfuse.ps1 -SubscriptionPath "D:\rules\gkd.json5"
```

流程：验证输入 → 生成完整 bundle（validator 内置于 generator）→
Gradle `assembleGkdDebug` → APK 自检（包名 / 无 INTERNET / APK 内 bundle
SHA256 与生成物一致）→ 打印最终报告。

**完整构建禁止 fallback**：没有大规则源输入、生成失败或 bundle 过小
（<100KB 视为 fixture 退化）时直接 FAIL，绝不静默使用 588B 的 QQ音乐 fixture。

## 开发构建（允许 fixture）

日常开发可直接 `assembleGkdDebug`：未生成 `.local.json` 时 App 会回退到
仓库内自写的 `app/src/main/assets/bypass_splash_rules.json` fixture。

## 当前已知输出

- 完整 bundle 规模随第三方源输入变化（生成器实时汇总 apps/groups/rules，
  不在本文件固化，避免陈旧数字误导）。
- 微信小程序组（`开屏广告-微信小程序`，仅 AppBrandUI / AppBrandLaunchProxyUI
  Activity）：key 200 desc「跳过」+ key 201 关闭（AGGRESSIVE）+ key 202 ×
  （AGGRESSIVE）+ key 203 结构关闭（CRAZY）；支付宝小程序组绑定
  XRiverActivity / Nebula（key 210 关闭，AGGRESSIVE）。
- 全局补强：`开屏广告-全局` 组追加可点击父节点补强、安全手势补强、
  策略化关闭补强（AGGRESSIVE）、策略化 X 补强（AGGRESSIVE）、
  策略化结构关闭补强（CRAZY）；所有策略化规则携带 `bypassMode` 元数据。
