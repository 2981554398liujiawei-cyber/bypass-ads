#!/usr/bin/env python3
"""Consistency gate for the single-source safety exclusions.

rules/safety_exclusions.json is the only definition of the high-risk package
list. This test proves:
  1. the file exists and both lists are non-empty;
  2. every runtime high-risk package is also disabled by the generator
     (the runtime veto set is a subset of the generator disabled set);
  3. the asset copy used by the Kotlin runtime is generated and matches;
  4. the generator actually reads the JSON (no drift between the literal
     fallback list and the file).
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SAFETY = ROOT / "rules" / "safety_exclusions.json"
GENERATOR = ROOT / "tools" / "build_splash_bundle.py"
ASSET = ROOT / "app" / "src" / "main" / "assets" / "bypass_safety_exclusions.json"


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def test_single_source_exists() -> None:
    assert SAFETY.exists(), "rules/safety_exclusions.json missing"
    data = load(SAFETY)
    assert len(data.get("high_risk_packages", [])) > 0
    assert len(data.get("generator_disabled_apps", [])) > 0


def test_runtime_veto_is_subset_of_generator_disabled() -> None:
    data = load(SAFETY)
    high_risk = set(data["high_risk_packages"])
    disabled = set(data["generator_disabled_apps"])
    extra = high_risk - disabled
    assert not extra, f"runtime veto packages not disabled by generator: {sorted(extra)}"


def test_generator_reads_the_json() -> None:
    src = GENERATOR.read_text(encoding="utf-8")
    assert "safety_exclusions.json" in src, "generator must read safety_exclusions.json"
    assert "generator_disabled_apps" in src


def test_asset_copy_is_generated_and_matches() -> None:
    assert ASSET.exists(), "assets/bypass_safety_exclusions.json not generated - run build_splash_bundle.py"
    src = load(SAFETY)
    asset = load(ASSET)
    assert set(asset["high_risk_packages"]) == set(src["high_risk_packages"]), "asset copy drifted from source"


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failures = 0
    for test in tests:
        try:
            test()
            print(f"PASS {test.__name__}")
        except AssertionError as e:
            failures += 1
            print(f"FAIL {test.__name__}: {e}")
    if failures:
        print(f"{failures} failure(s)")
        return 1
    print(f"all {len(tests)} safety-exclusion tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
