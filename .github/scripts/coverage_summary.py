#!/usr/bin/env python3
"""Print a Markdown per-module coverage table from each module's target/site/jacoco/jacoco.csv.

"Gated" is what the jacoco:check rule in the root pom.xml enforces: line coverage of the
business packages (services, schedulers, consumer). "All code" is every class, for context.

Usage: coverage_summary.py [repo_root]   (CI appends the output to $GITHUB_STEP_SUMMARY)
"""
import csv
import glob
import os
import sys

GATED_PACKAGE_SUFFIXES = (".services", ".schedulers", ".consumer")


def pct(covered, missed):
    total = covered + missed
    return f"{100.0 * covered / total:.1f}%" if total else "n/a"


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    reports = sorted(glob.glob(os.path.join(root, "*", "target", "site", "jacoco", "jacoco.csv")))
    if not reports:
        print("No JaCoCo reports found.")
        return

    rows = []
    for path in reports:
        module = path.split(os.sep)[-5]
        gated = [0, 0]
        overall = [0, 0]
        with open(path, newline="") as f:
            for r in csv.DictReader(f):
                missed, covered = int(r["LINE_MISSED"]), int(r["LINE_COVERED"])
                overall[0] += covered
                overall[1] += missed
                if r["PACKAGE"].endswith(GATED_PACKAGE_SUFFIXES):
                    gated[0] += covered
                    gated[1] += missed
        rows.append((module, gated, overall))

    print("### Coverage (JaCoCo, unit + integration tests)")
    print()
    print("| Module | Gated lines (services/schedulers/consumer) | All code lines |")
    print("|---|---:|---:|")
    for module, gated, overall in rows:
        print(f"| `{module}` | {pct(*gated)} ({gated[0]}/{gated[0] + gated[1]}) "
              f"| {pct(*overall)} ({overall[0]}/{overall[0] + overall[1]}) |")
    print()
    print("Gate: line coverage >= `jacoco.line.minimum` (root `pom.xml`) on the gated packages, per module.")


if __name__ == "__main__":
    main()
