#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Privacy-policy gate for the Field Gate tooling (P0-6).

Static checks, no device required. Enforces that the tools NEVER:
  - save a full uiautomator XML anywhere (repo or cwd),
  - print or persist full page text,
  - write per-round node dumps (tmp_round_N),
  - read the session DB with SELECT * (text-bearing columns),
and that they DO carry an ad-only whitelist + redaction + temp-file hygiene.
"""
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TOOLS = REPO / "tools"
GITIGNORE = REPO / ".gitignore"

FAILURES = []


def check(name: str, cond: bool, detail: str = ""):
    if not cond:
        FAILURES.append(f"{name}: {detail}")


def source(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def main() -> int:
    relaunch = source(TOOLS / "relaunch_mp.py")
    ui_auto = source(TOOLS / "ui_auto.py")
    pull = source(TOOLS / "pull_sessions.py")
    collect = source(TOOLS / "collect_real_ad_samples.py")

    # --- no full-tree persistence anywhere ---
    for fname in ("relaunch_mp.py", "ui_auto.py"):
        src = source(TOOLS / fname)
        check(
            f"{fname} no local XML save",
            "write_text(ET.tostring" not in src and "write_text(ET.tostring(" not in src,
            "must never persist the full XML tree",
        )
        check(
            f"{fname} no cwd temp dumps",
            "tmp_round" not in src and "Path(\"tmp_" not in src and ".with_name(" not in src,
            "must never write dumps into the repo/cwd",
        )
        check(
            f"{fname} ad-only whitelist present",
            "AD_TOKENS" in src,
            "must carry an explicit ad-only token whitelist",
        )
        check(
            f"{fname} redaction present",
            "<redacted>" in src,
            "non-ad node text must be redacted",
        )
        check(
            f"{fname} device scratch deleted",
            "rm" in src and "-f" in src,
            "device-side dump file must be deleted after reading",
        )
        check(
            f"{fname} no unqualified page-text dump",
            "node_text(node)[:60]" not in src and "print(f\"{txt[:60]" not in src,
            "must not print full page text",
        )

    # --- relaunch_mp specifics ---
    check(
        "relaunch_mp no per-round files",
        "tmp_round_" not in relaunch,
        "per-round node dumps are forbidden",
    )
    check(
        "relaunch_mp pipes instead of pulling",
        '"pull"' not in relaunch,
        "must read the dump via pipe (cat), never pull it to the host",
    )

    # --- ui_auto specifics ---
    check(
        "ui_auto no --out XML option",
        '"--out"' not in ui_auto,
        "the --out full-XML option is removed",
    )

    # --- pull_sessions / collect specifics ---
    check(
        "pull_sessions no SELECT *",
        "SELECT * FROM" not in pull,
        "must select whitelisted metadata columns only",
    )
    check(
        "pull_sessions temp file + cleanup",
        "tempfile" in pull and "unlink(missing_ok=True)" in pull,
        "DB pull must go to a system temp file and be deleted",
    )
    check(
        "collect excludes diagnostic_timeline",
        "diagnostic_timeline" not in collect.split("SELECT_COLS =", 1)[1].split(")", 1)[0],
        "the session collector must not carry text-bearing timeline columns",
    )

    # --- runtime sanitizer (BypassCandidateSanitizer) ---
    sessions_src = source(TOOLS.parent / "app/src/main/kotlin/li/songe/gkd/bypass/BypassDetectionSessions.kt")
    check(
        "runtime sanitizer carries sensitive-value tokens",
        "sensitiveTokens" in sessions_src
        and "密码" in sessions_src
        and "验证码" in sessions_src
        and "银行卡" in sessions_src
        and "phoneOrCard" in sessions_src,
        "the candidate sanitizer must filter secrets/phone/card tokens",
    )
    check(
        "runtime sanitizer truncates values",
        "take(80)" in sessions_src,
        "candidate text/desc/viewId must be truncated",
    )
    check(
        "runtime sanitizer drops editable nodes",
        "isEditable" in sessions_src,
        "editable/input nodes must never be snapshotted",
    )

    # --- .gitignore protection ---
    gi = GITIGNORE.read_text(encoding="utf-8")
    check(
        "gitignore covers tmp dumps",
        "/tmp_*" in gi,
        "repo root tmp dumps must stay ignored",
    )

    if FAILURES:
        print("PRIVACY POLICY FAIL")
        for f in FAILURES:
            print("  -", f)
        return 1
    print("PRIVACY POLICY PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
