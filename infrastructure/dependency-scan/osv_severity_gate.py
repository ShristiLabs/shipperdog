#!/usr/bin/env python3
"""Severity gate over an osv-scanner JSON report (issue #39, S8-05).

Reads the JSON output of `osv-scanner scan source --format json` on
stdin and exits non-zero when any finding is HIGH or CRITICAL (the
story's acceptance criterion: the scan fails on high/critical).
Lower-severity findings are reported but do not fail the gate — they
are the next cleanup's runway, not an outage.

CVSS mapping: osv-scanner emits database_specific.severity
("LOW"/"MODERATE"/"HIGH"/"CRITICAL") per finding; advisories without a
severity are treated as LOW (listed, passing) — an unknown-impact
advisory is not silently dismissed, it is visible in the output.
"""
import json
import sys

FAILING = {"HIGH", "CRITICAL"}


def main() -> int:
    report = json.load(sys.stdin)
    counts: dict[str, int] = {}
    failures = []
    for result in report.get("results", []):
        source = result.get("source", {}).get("path", "?")
        for package in result.get("packages", []):
            name = package.get("package", {}).get("name", "?")
            version = package.get("package", {}).get("version", "?")
            for vuln in package.get("vulnerabilities", []):
                severity = (vuln.get("database_specific") or {}).get("severity", "UNKNOWN")
                severity = severity.upper()
                counts[severity] = counts.get(severity, 0) + 1
                line = f"{vuln.get('id', '?')} [{severity}] {name}:{version} ({source})"
                if severity in FAILING:
                    failures.append(line)
                else:
                    print(f"  (not gating) {line}")
    for severity in sorted(counts):
        print(f"{severity}: {counts[severity]}")
    if failures:
        print("DEPENDENCY SCAN FAIL: high/critical findings", file=sys.stderr)
        for line in failures:
            print("  " + line, file=sys.stderr)
        return 1
    print("dependency scan: no high/critical findings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
