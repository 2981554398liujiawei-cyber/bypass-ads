#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the Bypass Ads bundled splash-ad subscription.

Pipeline (single source of truth for the self-use rule stack):

    third-party splash rules (开屏广告* only)
            ↓ filter
    merge rules/bypass_overrides.json   (Bypass-owned overrides, tracked in git)
            ↓
    append host rules (WeChat / Alipay miniprogram host patches)
            ↓
    append conservative generic splash fallback (global group)
            ↓
    validate + report
            ↓
    app/src/main/assets/bypass_splash_rules.local.json (gitignored)

License note: the generated file is written to a gitignored path and the
third-party rule bodies are NEVER committed. The repository only tracks the
generator, the validator, the small self-owned fixture
(app/src/main/assets/bypass_splash_rules.json) and rules/bypass_overrides.json.

Usage:
    python tools/build_splash_bundle.py <input.json5> [output.json]
"""

import hashlib
import json
import re
import sys
from pathlib import Path

SPLASH_GROUP_PREFIX = "开屏广告"
REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT = REPO_ROOT / "app/src/main/assets/bypass_splash_rules.local.json"
OVERRIDES_PATH = REPO_ROOT / "rules/bypass_overrides.json"

# ---------------------------------------------------------------------------
# Conservative generic splash fallback (global group).
#
# Only matches a short, visible, clickable "跳过/跳過/Skip" button. Never acts
# on clickable=false nodes (no clickCenter gestures here). High-risk apps are
# excluded via the `apps` list (enable=false), so banking / payment /
# authentication / password-manager apps are only ever handled by precise
# per-app rules, never by this generic rule.
# ---------------------------------------------------------------------------
GENERIC_FALLBACK_GROUP = {
    "key": 9000,
    "name": "开屏广告-通用跳过",
    "matchTime": 10000,
    "actionMaximum": 1,
    "resetMatch": "app",
    "fastQuery": True,
    "rules": [
        {
            "key": 0,
            "matches": [
                '[text="跳过" || text="跳過" || text="Skip"][clickable=true][visibleToUser=true][text.length<10][width<400 && height<200]'
            ],
        }
    ],
    # enable=false -> these apps are excluded from the generic fallback.
    # Hosts with their own precise rules plus high-risk categories.
    "apps": [
        {"id": "com.tencent.mm", "enable": False},
        {"id": "com.eg.android.AlipayGphone", "enable": False},
        # payment / banking
        {"id": "com.unionpay", "enable": False},
        {"id": "com.unionpay.cloudpay", "enable": False},
        {"id": "com.icbc", "enable": False},
        {"id": "com.ccb.life", "enable": False},
        {"id": "com.cmbchina.ccd.pluto.cmbActivity", "enable": False},
        {"id": "cmb.pb", "enable": False},
        {"id": "com.android.bankabc", "enable": False},
        {"id": "com.boc.bocrm", "enable": False},
        {"id": "com.bankcomm.Bankcomm", "enable": False},
        {"id": "com.psbc.mobilebank", "enable": False},
        {"id": "com.spdbccc.app", "enable": False},
        {"id": "com.cib.android", "enable": False},
        {"id": "com.ecitic.bank.mobile", "enable": False},
        {"id": "com.cebbank.mobile.cemb", "enable": False},
        {"id": "com.pingan.paces.ccms", "enable": False},
        {"id": "com.cgbchina.xpt", "enable": False},
        {"id": "com.hxb.creditcard", "enable": False},
        {"id": "com.jd.jrapp", "enable": False},
        {"id": "com.jdjr", "enable": False},
        {"id": "com.duxiaoman.jinrong", "enable": False},
        {"id": "com.lu.com", "enable": False},
        # securities / crypto
        {"id": "com.hexin.plat.android", "enable": False},
        {"id": "com.eastmoney.android.fund", "enable": False},
        {"id": "com.xueqiu.android", "enable": False},
        {"id": "com.futu.mobile.tiger", "enable": False},
        {"id": "com.tigerbrokers.stock", "enable": False},
        {"id": "com.binance.dev", "enable": False},
        {"id": "com.okinc.okex", "enable": False},
        # authenticators / password managers
        {"id": "com.google.android.apps.authenticator2", "enable": False},
        {"id": "com.azure.authenticator", "enable": False},
        {"id": "com.lastpass.lpandroid", "enable": False},
        {"id": "com.1password.1password", "enable": False},
        # GKD already excludes system apps by default. These explicit entries
        # also cover OEM system surfaces that are not marked as system apps.
        {"id": "com.android.systemui", "enable": False},
        {"id": "com.android.settings", "enable": False},
        {"id": "com.google.android.permissioncontroller", "enable": False},
        {"id": "com.android.permissioncontroller", "enable": False},
        {"id": "com.google.android.packageinstaller", "enable": False},
        {"id": "com.android.packageinstaller", "enable": False},
        {"id": "com.miui.securitycenter", "enable": False},
        {"id": "com.miui.home", "enable": False},
        {"id": "com.huawei.systemmanager", "enable": False},
        {"id": "com.coloros.safecenter", "enable": False},
        {"id": "com.vivo.permissionmanager", "enable": False},
        # Bypass Ads itself
        {"id": "app.bypassads", "enable": False},
        {"id": "app.bypassads.debug", "enable": False},
    ],
}


def strip_json5(text: str) -> str:
    """Minimal JSON5 -> JSON cleanup: strip // and /* */ comments, trailing
    commas, and convert single-quoted strings to double-quoted ones (handles
    \\\\ and \\' escapes inside single-quoted strings)."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            # copy double-quoted string verbatim
            out.append(c)
            i += 1
            while i < n:
                out.append(text[i])
                if text[i] == "\\" and i + 1 < n:
                    out.append(text[i + 1])
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "'":
            # convert single-quoted string to double-quoted
            out.append('"')
            i += 1
            while i < n:
                ch = text[i]
                if ch == "\\" and i + 1 < n:
                    nxt = text[i + 1]
                    if nxt == "'":
                        out.append("\\'")
                    else:
                        out.append("\\")
                        out.append(nxt)
                    i += 2
                    continue
                if ch == "'":
                    out.append('"')
                    i += 1
                    break
                out.append(ch)
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    cleaned = "".join(out)
    # remove trailing commas before } or ]
    cleaned = re.sub(r",\s*([}\]])", r"\1", cleaned)
    return cleaned


def is_splash_group(name: str) -> bool:
    return name == SPLASH_GROUP_PREFIX or name.startswith(SPLASH_GROUP_PREFIX + "-")


def load_subscription(raw: str):
    """Parse a GKD subscription that may be JSON or JSON5. Prefers the json5
    library when installed; falls back to the built-in minimal cleaner."""
    try:
        import json5  # type: ignore
        return json5.loads(raw)
    except Exception:
        pass
    return json.loads(strip_json5(raw))


def _same_rule(a: dict, b: dict) -> bool:
    """Rules are considered duplicates when their key match strings (and the
    delay that gates the action) are identical — this is what makes override
    merging idempotent."""
    return (
        a.get("matches") == b.get("matches")
        and a.get("anyMatches") == b.get("anyMatches")
        and a.get("actionDelay") == b.get("actionDelay")
    )


def apply_overrides(apps: list, overrides: dict) -> int:
    """Merge Bypass-owned overrides into the filtered app list by
    (appId + group name). Idempotent: an override rule whose content already
    exists in the group is not appended twice. Returns the number of rules
    actually appended."""
    added = 0
    for ov_app in overrides.get("apps", []):
        app = next((a for a in apps if a.get("id") == ov_app.get("id")), None)
        if app is None:
            app = {"id": ov_app["id"], "name": ov_app.get("name", ov_app["id"]), "groups": []}
            apps.append(app)
        for ov_group in ov_app.get("groups", []):
            group = next((g for g in app["groups"] if g.get("name") == ov_group.get("name")), None)
            if group is None:
                group = {
                    "key": max((g.get("key", 0) for g in app["groups"]), default=-1) + 1,
                    "name": ov_group["name"],
                    "matchTime": 10000,
                    "rules": [],
                }
                app["groups"].append(group)
            existing = group.get("rules", [])
            for rule in ov_group.get("rules", []):
                if any(_same_rule(rule, r) for r in existing):
                    continue
                new_rule = dict(rule)
                # never collide with existing keys; keep override keys stable
                used_keys = {r.get("key", -1) for r in existing}
                key = rule.get("key")
                while key is None or key in used_keys:
                    key = max(used_keys, default=-1) + 1
                new_rule["key"] = key
                existing.append(new_rule)
                used_keys.add(key)
                added += 1
    return added


def add_generic_fallback(bundle: dict) -> int:
    """Append the generic splash fallback global group (idempotent by name).
    Returns 1 when appended, 0 when already present."""
    groups = bundle.setdefault("globalGroups", [])
    if any(g.get("name") == GENERIC_FALLBACK_GROUP["name"] for g in groups):
        return 0
    groups.append(GENERIC_FALLBACK_GROUP)
    return 1


def validate_bundle(bundle: dict) -> list:
    """Validate the final bundle. Returns a list of error strings (empty = ok)."""
    errors = []
    # 1. only splash groups allowed
    for app in bundle.get("apps", []):
        for g in app.get("groups", []):
            if not is_splash_group(g.get("name", "")):
                errors.append(f"非开屏组: {app.get('id')}/{g.get('name')}")
    # 2. globalGroups: only our generic fallback allowed
    for g in bundle.get("globalGroups", []):
        if g.get("name") != GENERIC_FALLBACK_GROUP["name"]:
            errors.append(f"非法的全局组: {g.get('name')}")
    # 3. presence checks
    app_ids = {a.get("id") for a in bundle.get("apps", [])}
    if "com.tencent.qqmusic" not in app_ids:
        errors.append("缺少 QQ音乐")
    if "com.tencent.mm" not in app_ids:
        errors.append("缺少 微信")
    if "com.eg.android.AlipayGphone" not in app_ids:
        errors.append("缺少 支付宝")
    # 4. QQ音乐 must keep a clickCenter-capable splash rule
    qq = next((a for a in bundle.get("apps", []) if a.get("id") == "com.tencent.qqmusic"), None)
    if qq:
        has_click = any(
            r.get("action") == "clickCenter" or r.get("position") is not None
            for g in qq.get("groups", [])
            for r in g.get("rules", [])
        )
        if not has_click:
            errors.append("QQ音乐缺少 clickCenter 规则")
    # 5. host miniprogram groups
    wx = next((a for a in bundle.get("apps", []) if a.get("id") == "com.tencent.mm"), None)
    if wx and not any(g.get("name") == "开屏广告-微信小程序" for g in wx.get("groups", [])):
        errors.append("微信缺少 开屏广告-微信小程序 组")
    ali = next((a for a in bundle.get("apps", []) if a.get("id") == "com.eg.android.AlipayGphone"), None)
    if ali and not any(g.get("name") == "开屏广告-小程序开屏广告" for g in ali.get("groups", [])):
        errors.append("支付宝缺少 开屏广告-小程序开屏广告 组")
    return errors


def summarize(bundle: dict) -> dict:
    apps = bundle.get("apps", [])
    groups = sum(len(a.get("groups", [])) for a in apps) + len(bundle.get("globalGroups", []))
    rules = sum(
        len(g.get("rules", []))
        for a in apps
        for g in a.get("groups", [])
    ) + sum(
        len(g.get("rules", []))
        for g in bundle.get("globalGroups", [])
    )
    return {"apps": len(apps), "groups": groups, "rules": rules}


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUTPUT
    if not src.exists():
        print(f"ERROR: input subscription not found: {src}", file=sys.stderr)
        return 1
    try:
        data = load_subscription(src.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"ERROR: failed to parse {src}: {e}", file=sys.stderr)
        print("Note: strings must use double quotes; comments/trailing commas are OK.", file=sys.stderr)
        return 1

    apps_in = data.get("apps", [])
    total_groups = 0
    kept_apps = []
    kept_groups = 0
    kept_rules = 0
    for app in apps_in:
        groups = app.get("groups", [])
        total_groups += len(groups)
        splash = [g for g in groups if is_splash_group(g.get("name", ""))]
        if not splash:
            continue
        kept_groups += len(splash)
        kept_rules += sum(len(g.get("rules", [])) for g in splash)
        kept_apps.append({**app, "groups": splash})

    # Bypass-owned overrides (tracked in git)
    overrides_added = 0
    if OVERRIDES_PATH.exists():
        try:
            overrides = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))
            overrides_added = apply_overrides(kept_apps, overrides)
        except Exception as e:
            print(f"ERROR: failed to load overrides {OVERRIDES_PATH}: {e}", file=sys.stderr)
            return 1
    else:
        print("WARNING: rules/bypass_overrides.json not found, skip overrides")

    bundle = {
        "id": data.get("id", 100000001),
        "name": "Bypass Ads 开屏规则",
        "version": data.get("version", 1),
        "author": data.get("author", "bypass-ads"),
        "globalGroups": [],
        "categories": [c for c in data.get("categories", []) if c.get("name", "").startswith(SPLASH_GROUP_PREFIX)],
        "apps": kept_apps,
    }
    fallback_added = add_generic_fallback(bundle)

    errors = validate_bundle(bundle)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8")

    summary = summarize(bundle)
    print(f"input subscription : {src}")
    print(f"  total apps       : {len(apps_in)}")
    print(f"  total rule groups: {total_groups}")
    print("filtered splash only:")
    print(f"  kept apps        : {len(kept_apps)}")
    print(f"  kept groups      : {kept_groups}")
    print(f"  kept rules       : {kept_rules}")
    print(f"overrides added    : {overrides_added}")
    print(f"generic fallback   : {'added' if fallback_added else 'already present'}")
    print(f"final apps/groups/rules: {summary['apps']}/{summary['groups']}/{summary['rules']}")
    for want in ("com.tencent.qqmusic", "com.tencent.mm", "com.eg.android.AlipayGphone"):
        hit = next((a for a in kept_apps if a.get("id") == want), None)
        if hit:
            names = [g.get("name") for g in hit["groups"]]
            print(f"  {want:32s} IN BUNDLE  groups={names}")
        else:
            print(f"  {want:32s} absent")
    if errors:
        print("VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"validation          : PASS")
    print(f"output             : {dst}")
    print(f"output sha256      : {sha256_of(dst)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
