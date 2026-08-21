# Bypass Ads

完全离线的 Android 开屏广告自动跳过工具。安装后开启无障碍服务即可，无需联网、无需导入订阅、无需手动配置：内置开屏广告规则在首次启动时自动初始化并默认启用。

## 特性

- **完全离线**：默认 APK 不含 `INTERNET` 权限，运行时无任何网络请求。
- **开箱即用**：首次安装自动内置广告规则（仓库跟踪的是小型自写基线夹具；完整自用规则包由本机构建生成），默认启用，无需导入订阅。
- **四类广告**：开屏 / 全屏·插屏 / 营销弹窗 / 其它可关闭广告。
- **三档策略**：保守（成熟专用规则 + 明确跳过语义）→ 激进（确认广告上下文后的
  关闭/Close/ad_close/×）→ 彻底疯狂（进一步尝试结构型关闭与受限坐标，仅限强广告
  上下文）。切换立即生效，策略按会话记录。
- **Records**：成功/失败记录全部来自广告会话（结果需验证确认），显示发生时策略、
  动作次数、响应延迟与最终出口类型；统计只计"已确认成功"的会话。
- **Teach**：失败快照可一键教 Bypass Ads 跳过；"开始一次验证"式验证（armed retest，
  验证窗口结束才判失败，首个未匹配不判负）。
- **总开关**：首页一键"自动跳过广告"开关，关闭即暂停全部规则执行。
- **Default offline / FullTools**：默认 `gkd` flavor 完全离线；`fulltools` 额外提供
  本机 HTTP 检查等高级工具（含 INTERNET，仅本机用途）。
- **品牌化**：包名 `app.bypassads`（debug 为 `app.bypassads.debug`）。

## 构建

需要 JDK 17+ 与 Android SDK（`local.properties` 配置 `sdk.dir`）：

自用正式版（推荐，单命令产出已签名 Release APK）：

```
.\tools\build_selfuse.ps1 -SubscriptionPath D:\rules\gkd.json5
```

该脚本会：生成完整本地规则包 → 运行策略/JVM 测试 → `assembleGkdRelease`（R8 + 资源裁剪）→
APK 权限/包名/版本/allowBackup/内置规则 SHA 检查 → apksigner 验签 → 输出
`dist\Bypass-Ads-v1.0.0-selfuse.apk`。

- 签名：优先复用配置的 `GKD_STORE_*`；否则首次自动在
  `%USERPROFILE%\.bypass-ads\signing\` 创建长期自用密钥（密码仅存本机，后续构建复用同一密钥）。
- Release 构建**永不静默回退 debug 密钥**：未配置正式签名时 Gradle 只产出未签名 Release
  （供 CI/R8 验证），本脚本会拒绝输出“正式 APK”。
- 开发构建：

```
gradle :app:assembleGkdDebug
```

产物：`app/build/outputs/apk/gkd/debug/app-gkd-debug.apk`

> 本机若遇 Kotlin daemon 增量缓存损坏（`class-attributes.tab is already registered`），
> `gradle.properties` 已内置 `kotlin.incremental=false` + `kotlin.compiler.execution.strategy=in-process` 规避。

## 内置规则

- 仓库只跟踪自写的基线夹具 `app/src/main/assets/bypass_splash_rules.json`（用于构建与测试）。
- 自用完整构建经 `tools/build_selfuse.ps1` 从本机第三方订阅生成 full local bundle
  （`app/src/main/assets/bypass_splash_rules.local.json`，已 gitignore）。
- 未获再分发许可的第三方规则正文不进入本仓库；包含这类规则的 self-use APK 仅限本机自用，
  不公开分发。

## 免责声明

**本项目基于 [GKD](https://github.com/gkd-kit/gkd) 修改，依据 [GPL-3.0](/LICENSE) 发布。软件按现状提供，无担保。使用者需遵守适用法律法规。**

- 上游项目：[gkd-kit/gkd](https://github.com/gkd-kit/gkd)（GKD v1.12.1 基线，commit `5a00f84`）
- 本仓库保留 GKD 的全部 GPL-3.0 源码与版权声明。
