# GKD Feature Parity

This matrix records where user-facing GKD 1.12.1 capabilities are exposed in
Bypass Ads. The product keeps common ad-blocking actions in its own UI and
uses the existing GKD pages for advanced workflows.

| Original capability | Original source | Bypass destination | Status | Notes |
| --- | --- | --- | --- | --- |
| Service state and work mode | `ui/home/ControlPage.kt` | Home, Settings > Service and permissions | PORTED_RENAMED | Product status replaces the original control screen. |
| Persistent notification | `ui/home/SettingsPage.kt` | Settings > Notifications | PORTED_RENAMED | Uses the existing `StatusService` setting. |
| Trigger records | `ui/ActionLogPage.kt` | Home > Trigger records, Settings > Records and logs | PORTED_RENAMED | Both links open one Bypass trigger-record page. |
| Interface, accessibility-event and activity logs | `ui/*LogPage.kt` | Settings > Advanced tools > Diagnostics | PORTED | Existing pages remain available from the advanced tool hub. |
| Subscription add, refresh, sort, multi-select and delete | `ui/home/SubsManagePage.kt` | Ad blocking > Advanced rule management | FULLTOOLS_ONLY | Remote actions require the fulltools build. Local rules remain offline. |
| Local subscriptions and app/global rules | `ui/*Subs*Page.kt` | Ad blocking > Advanced rule management | PORTED | Existing GKD pages are reused. |
| Rule categories and slow rules | `ui/SubsCategoryPage.kt`, `ui/SlowGroupPage.kt` | Ad blocking > Advanced rule management | PORTED | Product categories are also exposed as ad switches. |
| App list, search, filters, whitelist and rule details | `ui/home/AppListPage.kt`, `ui/AppConfigPage.kt` | Applications | PORTED_RENAMED | Product facade handles common controls; advanced detail stays reachable. |
| Per-group enable/disable | `ui/SubsAppGroupListPage.kt` | Application detail | PORTED_RENAMED | Stored in the existing `SubsConfig` table. |
| Trigger prompt, toast style and track hint | `ui/home/SettingsPage.kt` | Settings > Notifications and advanced tools | PORTED_RENAMED | Default toast remains Bypass Ads branded. |
| Backup import, export, share and Downloads save | `util/BackupUtils.kt` | Settings > Backup and restore | PORTED_RENAMED | Existing backup content includes app, group, category and store state. |
| Accessibility scope and whitelist/blacklist | `ui/A11yScopeAppListPage.kt`, `ui/BlockA11yAppListPage.kt` | Settings > Advanced tools > Runtime and permissions | PORTED | Existing pages are reused. |
| Snapshot, screenshot and overlays | `ui/SnapshotPage.kt`, `ui/AdvancedPage.kt` | Settings > Advanced tools > Diagnostics | PORTED | Available offline. |
| AppOps, Shizuku and restricted-settings checks | `ui/AppOpsAllowPage.kt`, `ui/AdvancedPage.kt` | Settings > Advanced tools > Runtime and permissions | PORTED | Available offline. |
| Crash report and shared logs | `ui/CrashReportPage.kt`, `ui/component/ShareLogDlg.kt` | Settings > Advanced tools > Diagnostics | PORTED | Available offline. |
| HTTP server | `service/HttpService.kt` | Settings > Advanced tools > Network tools | FULLTOOLS_ONLY | Not registered in the default offline APK. |
| Remote subscription update | `util/Network*.kt` | Ad blocking > Advanced rule management | FULLTOOLS_ONLY | Default build contains no INTERNET permission. |
| Web documentation and WebView | `ui/WebViewPage.kt` | Settings > Help | FULLTOOLS_ONLY | No network surface in the default APK. |
| Update check | `util/Upgrade.kt` | Settings > About | FULLTOOLS_ONLY | No network surface in the default APK. |
