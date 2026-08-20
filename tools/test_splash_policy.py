#!/usr/bin/env python3
"""Regression checks for the Bypass-owned generic splash fallback policy.

This intentionally tests the generated selector contract rather than trying
to duplicate GKD's selector engine. The engine remains upstream-owned; these
checks make the Bypass build policy auditable before an APK reaches a device.
"""

import importlib.util
import json
import tempfile
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


def test_multi_source_union_dedupe_and_conflict_report() -> None:
    source_a = ROOT / "tools" / "fixtures" / "source_a.json"
    source_b = ROOT / "tools" / "fixtures" / "source_b.json"
    parsed = [
        (builder.load_subscription(source_a.read_text(encoding="utf-8")), source_a),
        (builder.load_subscription(source_b.read_text(encoding="utf-8")), source_b),
    ]
    merged, report = builder.merge_sources(parsed)
    assert {app["id"] for app in merged["apps"]} == {"fixture.app1", "fixture.shared", "fixture.app3"}
    assert report["coverageDiff"]["primaryOnlyApps"] == ["fixture.app1"]
    assert report["coverageDiff"]["secondaryOnlyApps"] == ["fixture.app3"]
    assert report["coverageDiff"]["sharedApps"] == ["fixture.shared"]
    assert len(report["conflicts"]) == 1
    shared = next(app for app in merged["apps"] if app["id"] == "fixture.shared")
    assert shared["groups"][0]["rules"][0]["matches"] == ['[vid="skip_a"]']


def test_multi_source_identical_group_is_deduplicated() -> None:
    source_a = ROOT / "tools" / "fixtures" / "source_a.json"
    data_a = builder.load_subscription(source_a.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory() as temp:
        second = Path(temp) / "source-a-copy.json"
        second.write_text(source_a.read_text(encoding="utf-8"), encoding="utf-8")
        merged, report = builder.merge_sources([(data_a, source_a), (data_a, second)])
    assert len(merged["apps"]) == 2
    assert report["deduplicatedGroups"] == 2


if __name__ == "__main__":
    test_fallback_selector_contract()
    test_source_global_filter_contract()
    test_source_global_reinforcement_contract()
    test_override_group_policy_is_preserved()
    test_multi_source_union_dedupe_and_conflict_report()
    test_multi_source_identical_group_is_deduplicated()
    print("splash policy: PASS")
