#!/usr/bin/env python3
"""Asserts the securityheaders.com-grade header set on one response.

Reads raw HTTP response headers on stdin (curl -D - output), fails
with a precise message for each missing or mismatched header. Used by
check-portal-headers.sh (issue #39, S8-05); the same expected set is
the portal's security-headers.conf contract.
"""
import re
import sys

EXPECTED = {
    "content-security-policy": (
        "default-src 'self'; script-src 'self'; style-src 'self' "
        "'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
        "connect-src 'self'; object-src 'none'; base-uri 'self'; "
        "form-action 'self'; frame-ancestors 'none'"
    ),
    "strict-transport-security": "max-age=31536000; includeSubDomains",
    "x-frame-options": "DENY",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    "permissions-policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
}


def main() -> int:
    header_file = sys.argv[1]
    label = sys.argv[2] if len(sys.argv) > 2 else header_file
    with open(header_file, "r", encoding="utf-8") as handle:
        raw = handle.read()
    headers: dict[str, str] = {}
    for line in raw.splitlines():
        match = re.match(r"^([A-Za-z0-9-]+):\s*(.*)$", line)
        if match:
            headers[match.group(1).lower()] = match.group(2).strip()

    failures = []
    for name, expected in EXPECTED.items():
        actual = headers.get(name)
        if actual is None:
            failures.append(f"{label}: missing {name}")
        elif actual != expected:
            failures.append(f"{label}: {name} mismatch\n  expected: {expected}\n  actual:   {actual}")
    if failures:
        print("FAIL", file=sys.stderr)
        for failure in failures:
            print("  " + failure, file=sys.stderr)
        return 1
    print(f"ok {label}: all {len(EXPECTED)} security headers present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
