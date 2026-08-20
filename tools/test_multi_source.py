#!/usr/bin/env python3
"""Multi-source merge tests (fixture based, no private rule sources needed).

Delegates to the multi-source cases in test_splash_policy.py so CI has a
dedicated, clearly named entry point.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import test_splash_policy as t  # noqa: E402


def main() -> int:
    cases = [
        t.test_multi_source_union_dedupe_and_conflict_report,
        t.test_multi_source_identical_group_is_deduplicated,
    ]
    for case in cases:
        case()
        print(f"PASS {case.__name__}")
    print(f"all {len(cases)} multi-source tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
