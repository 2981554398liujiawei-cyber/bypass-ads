# Maintenance tools

Release and CI use:

- `build_selfuse.ps1` — generate and verify the signed self-use release.
- `build_splash_bundle.py` — build the local rule bundle.
- `build_ad_bundle.py` — compatibility entry point for the bundle builder.
- `check_repo_integrity.py` — repository safety checks.
- `test_splash_policy.py`, `test_safety_exclusions.py`,
  `test_multi_source.py`, `test_privacy_policy.py` — automated policy gates.

Field and maintenance support:

- `field_v1_gate.py` — TestAd/device gate helper.
- `collect_real_ad_samples.py` — privacy-safe real-ad sample collection.
- `pull_sessions.py` — pull sanitized session metadata.
- `relaunch_mp.py`, `ui_auto.py` — controlled device checks without retaining
  full UI trees.
- `reliability_loop.py`, `reliability_reboot.py` — service recovery checks.

Generated bundles, device dumps, APKs, and temporary outputs are ignored by
Git. Do not place third-party subscription bodies in the repository.
