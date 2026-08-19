#!/usr/bin/env python3
"""Regression checks for the Bypass-owned generic splash fallback policy.

This intentionally tests the generated selector contract rather than trying
to duplicate GKD's selector engine. The engine remains upstream-owned; these
checks make the Bypass build policy auditable before an APK reaches a device.
"""

import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
MODULE_PATH = ROOT / "tools" / "build_splash_bundle.py"
SPEC = importlib.util.spec_from_file_location("build_splash_bundle", MODULE_PATH)
assert SPEC and SPEC.loader
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


def test_fallback_selector_contract() -> None:
    group = builder.GENERIC_FALLBACK_GROUP
    serialized = json.dumps(group, ensure_ascii=False)

    assert group["name"] == "开屏广告-通用跳过"
    assert group["matchTime"] == 10000
    assert "text*=" in serialized and "跳过" in serialized
    assert "desc*=" in serialized
    assert "vid~=" in serialized and ".*skip.*" in serialized
    assert "@[clickable=true]" in serialized
    assert '"action": "clickCenter"' in serialized

    for unsafe in ("NEXT", "跳过片头", "跳过视频", "阅读并同意"):
        assert unsafe in serialized


def test_source_global_filter_contract() -> None:
    assert builder.is_splash_global_group("开屏广告")
    assert builder.is_splash_global_group("开屏广告-全局")
    assert not builder.is_splash_global_group("更新提示")
    assert not builder.is_splash_global_group("权限提示")


def test_source_global_reinforcement_contract() -> None:
    groups = [{"key": 0, "name": "开屏广告-全局", "rules": [{"key": 0}]}]
    assert builder.reinforce_source_global_groups(groups) == 2
    assert builder.reinforce_source_global_groups(groups) == 0
    serialized = json.dumps(groups[0], ensure_ascii=False)
    assert "Bypass Ads 可点击父节点补强" in serialized
    assert "Bypass Ads 安全手势补强" in serialized
    assert '"action": "clickCenter"' in serialized
    for unsafe in ("NEXT", "跳过片头", "跳过视频", "阅读并同意"):
        assert unsafe in serialized


def test_override_group_policy_is_preserved() -> None:
    apps = []
    added = builder.apply_overrides(
        apps,
        {
            "apps": [
                {
                    "id": "app.bypassads.testad",
                    "groups": [
                        {
                            "name": "开屏广告-确定性测试",
                            "order": -20,
                            "ignoreGlobalGroupMatch": True,
                            "rules": [{"key": 0, "matches": ["[text=\"跳过广告\"]"]}],
                        }
                    ],
                }
            ]
        },
    )
    assert added == 1
    group = apps[0]["groups"][0]
    assert group["order"] == -20
    assert group["ignoreGlobalGroupMatch"] is True


if __name__ == "__main__":
    test_fallback_selector_contract()
    test_source_global_filter_contract()
    test_source_global_reinforcement_contract()
    test_override_group_policy_is_preserved()
    print("splash policy: PASS")
