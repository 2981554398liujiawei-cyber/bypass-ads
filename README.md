# Bypass Ads

完全离线的 Android 开屏广告自动跳过工具。安装后开启无障碍服务即可，无需联网、无需导入订阅、无需手动配置：内置开屏广告规则在首次启动时自动初始化并默认启用。

## 特性

- **完全离线**：APK 不含 `INTERNET` 权限，运行时无任何网络请求。
- **开箱即用**：首次安装自动内置开屏广告规则（覆盖大量常见 App），默认启用，无需导入订阅。
- **仅开屏广告**：内置规则只保留"开屏广告"类别（含微信/支付宝小程序开屏），不做应用内广告、弹窗、信息流等。
- **总开关**：首页一键"自动跳过开屏广告"开关，关闭即暂停全部规则执行。
- **品牌化**：包名 `app.bypassads`（debug 为 `app.bypassads.debug`）。

## 构建

需要 JDK 17+ 与 Android SDK（`local.properties` 配置 `sdk.dir`）：

```
gradle :app:assembleGkdDebug
```

产物：`app/build/outputs/apk/gkd/debug/app-gkd-debug.apk`

> 本机若遇 Kotlin daemon 增量缓存损坏（`class-attributes.tab is already registered`），
> `gradle.properties` 已内置 `kotlin.incremental=false` + `kotlin.compiler.execution.strategy=in-process` 规避。

## 内置开屏规则

- 仓库跟踪的基线夹具：`app/src/main/assets/bypass_splash_rules.json`（自写 QQ 音乐开屏规则，用于构建与测试）。
- 本地大规模规则生成：`tools/build_splash_bundle.py <第三方订阅.json5>`，只过滤"开屏广告*"规则组，
  输出到 `app/src/main/assets/bypass_splash_rules.local.json`（已 gitignore，**第三方规则正文不提交到公开仓库**）。

## 免责声明

**本项目基于 [GKD](https://github.com/gkd-kit/gkd) 修改，遵循 [GPL-3.0](/LICENSE) 开源，仅供学习交流，禁止用于商业或非法用途。**

- 上游项目：[gkd-kit/gkd](https://github.com/gkd-kit/gkd)（GKD v1.12.1 基线，commit `5a00f84`）
- 本仓库保留 GKD 的全部 GPL-3.0 源码与版权声明。
