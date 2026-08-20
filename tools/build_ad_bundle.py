#!/usr/bin/env python3
"""Canonical entry point for building the Bypass Ads advertising bundle.

The full implementation currently lives in build_splash_bundle.py (kept as
the compatible implementation module and CLI); this wrapper is the canonical
name going forward. A future card can move the implementation here wholesale
without breaking either name.
"""

import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE))

import build_splash_bundle as _impl  # noqa: E402


def main() -> int:
    return _impl.main()


if __name__ == "__main__":
    sys.exit(main())
