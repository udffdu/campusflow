#!/usr/bin/env python3
"""Confirm core and database-lock tests detect broken logic in disposable source copies."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "services/seats"
PACKAGE = Path("src/main/java/io/campusflow/seats")
MUTATIONS = [
    ("adjacent_overlap", "TimeRange.java",
     "startAt.isBefore(other.endAt) && other.startAt.isBefore(endAt)",
     "!startAt.isAfter(other.endAt) && !other.startAt.isAfter(endAt)",
     "adjacentReservationsDoNotConflictInEitherOrder", "ReservationRulesTest"),
    ("missing_window_check", "ReservationPolicy.java",
     "range.startAt().isAfter(now.plus(maxAdvance))", "false",
     "nextDayOutsideTheConfiguredWindowIsRejected", "ReservationRulesTest"),
    ("missing_transaction_lock", "OccupancyLocks.java",
     'jdbc.query("select pg_advisory_xact_lock(?)", (RowCallbackHandler) row -> {}, key);',
     "/* deliberately omitted transaction lock acquisition */",
     "heldRoomLockCausesRetryableFailureAndNoWrites",
     "ReservationStoreTest#heldRoomLockCausesRetryableFailureAndNoWrites"),
]


def source_hashes():
    return {str(p.relative_to(CORE)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in (CORE / "src").rglob("*.java")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    original = source_hashes()
    results = []
    for name, filename, before, after, expected_test, test_selector in MUTATIONS:
        with tempfile.TemporaryDirectory(prefix="campusflow-m1-mutation-") as directory:
            copy = Path(directory) / "seats"
            shutil.copytree(CORE, copy, ignore=shutil.ignore_patterns("target"))
            source = copy / PACKAGE / filename
            text = source.read_text(encoding="utf-8")
            if text.count(before) != 1:
                raise RuntimeError(f"Mutation target changed: {name}; review the experiment")
            source.write_text(text.replace(before, after), encoding="utf-8")
            with (output / f"{name}.log").open("w", encoding="utf-8") as log:
                run = subprocess.run(
                    [str(ROOT / "services/platform/mvnw"), "-o", "-B", "-ntp",
                     "-f", str(copy / "pom.xml"), "-Dtest=" + test_selector, "test"],
                    cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=180)
            failed_tests = []
            errors = 0
            for report in (copy / "target/surefire-reports").glob("TEST-*.xml"):
                suite = ET.parse(report).getroot()
                errors += int(suite.get("errors", "0"))
                failed_tests.extend(case.get("name") for case in suite.findall("testcase")
                                    if case.find("failure") is not None)
            if run.returncode == 0 or errors or expected_test not in failed_tests:
                raise RuntimeError(f"{name}: expected behavioral failure absent; inspect its log")
            results.append({"mutation": name, "exit_code": run.returncode,
                            "failed_tests": failed_tests, "errors": errors})
    if source_hashes() != original:
        raise RuntimeError("Original source changed during the experiment")
    summary = {"results": results, "original_source_unchanged": True}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
