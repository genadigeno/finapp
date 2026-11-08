"""Compares one Semgrep run with the baseline of accepted findings (P15-TSK-006; infra/scripts/sast.sh).

Run inside the pinned Semgrep image, which carries this interpreter, so the check is the same on a laptop and in CI.

A finding is accepted only by a row of infra/semgrep/baseline.tsv, one row per finding:

    fingerprint <TAB> rule <TAB> path <TAB> excerpt <TAB> until <TAB> reason

The fingerprint covers the rule, the file and the matched text with its white space collapsed, not a line number: a
row survives a line moving and stops matching the moment the matched text changes, so a changed statement is looked
at again. Rows are counted, so two findings with the same text in one file take two rows.

Exit 0: every finding has its row, every row its finding, none expired, and the scanner read everything it was given.
Exit 1: a new finding, a stale or expired row, or a malformed row. Exit 2: the scanner did not read everything.
"""

import collections
import datetime
import hashlib
import json
import re
import sys


def fingerprint(rule, path, text):
    normalised = " ".join(text.split())
    return hashlib.sha256("\0".join((rule, path, normalised)).encode("utf-8")).hexdigest()[:16], normalised


def rule_of(check_id):
    return check_id.rsplit(".", 1)[-1]


def read_baseline(path):
    rows = []
    problems = []
    with open(path, encoding="utf-8", newline="") as baseline:
        for number, line in enumerate(baseline, start=1):
            line = line.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            columns = line.split("\t")
            if len(columns) != 6:
                problems.append(f"line {number}: expected six tab-separated columns, found {len(columns)}")
                continue
            digest, rule, source, excerpt, until, reason = columns
            if not re.fullmatch(r"[0-9a-f]{16}", digest):
                problems.append(f"line {number}: {digest!r} is not a fingerprint")
            try:
                expires = datetime.date.fromisoformat(until)
            except ValueError:
                problems.append(f"line {number}: {until!r} is not an expiry date")
                expires = datetime.date.min
            if len(reason.strip()) < 20:
                problems.append(f"line {number}: a baseline row carries its reason, and this one does not")
            rows.append((digest, rule, source, excerpt, expires, reason, number))
    return rows, problems


def main(report_path, baseline_path, tree, expected_java):
    with open(report_path, encoding="utf-8") as handle:
        report = json.load(handle)

    # 1. A scanner that did not read a file has not checked it.
    errors = report.get("errors", [])
    scanned = report.get("paths", {}).get("scanned", [])
    java = [path for path in scanned if path.endswith(".java")]
    if errors:
        for error in errors[:20]:
            print(f"?? {error.get('type')}: {error.get('path')}: {str(error.get('message'))[:300]}")
        print(f"?? the scanner reported {len(errors)} errors: what it could not read it did not check")
        return 2
    if len(java) != expected_java:
        print(f"?? the scanner read {len(java)} Java files of the {expected_java} in the tree")
        return 2

    # 2. The findings.
    findings = collections.defaultdict(list)
    for result in report["results"]:
        rule = rule_of(result["check_id"])
        path = result["path"]
        with open(f"{tree}/{path}", "rb") as source:
            matched = source.read()[result["start"]["offset"] : result["end"]["offset"]].decode("utf-8", "replace")
        digest, normalised = fingerprint(rule, path, matched)
        findings[digest].append((rule, path, result["start"]["line"], normalised))

    rows, problems = read_baseline(baseline_path)
    for problem in problems:
        print(f"!! baseline: {problem}")

    wanted = collections.Counter({digest: len(where) for digest, where in findings.items()})
    accepted = collections.Counter(row[0] for row in rows)

    status = 1 if problems else 0
    today = datetime.date.today()

    for digest, count in (wanted - accepted).items():
        status = 1
        rule, path, line, normalised = findings[digest][0]
        print(f"!! {rule} {path}:{line}: {normalised[:160]}")
        print(f"   not accepted ({count} occurrence(s) with no row). If it is accepted rather than fixed, a row:")
        print(f"   {digest}\t{rule}\t{path}\t{normalised[:60]}\t<until, YYYY-MM-DD>\t<the reason>")

    surplus = accepted - wanted
    for row in rows:
        digest = row[0]
        if surplus.get(digest, 0) > 0:
            surplus[digest] -= 1
            status = 1
            print(f"!! baseline: line {row[6]} matches no finding (a stale row is removed, not kept): {row[1]} {row[2]}")
        if row[4] < today:
            status = 1
            print(f"!! baseline: line {row[6]} expired on {row[4]}: {row[1]} {row[2]}")

    print(f"-- {sum(accepted.values())} accepted row(s), {sum(wanted.values())} finding(s), {len(java)} Java file(s) read")
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])))
