#!/usr/bin/env python3
"""Confirm M1 and D supplement tests detect broken disposable source copies."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
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
    ("missing_opening_filter", "SeatAvailabilityQuery.java",
     "if (!room.openingHours().covers(period))", "if (false)",
     "entirePeriodMustBeOpen", "SeatAvailabilityQueryTest#entirePeriodMustBeOpen"),
    ("missing_occupied_seat_filter", "SeatAvailabilityQuery.java",
     ".filter(seat -> !occupancy.occupiedSeatIds().contains(seat.id()))", ".filter(seat -> true)",
     "bookingAfterQueryChangesNextQueryWithoutReservingOnRead",
     "SeatAvailabilityQueryTest#bookingAfterQueryChangesNextQueryWithoutReservingOnRead"),
    ("missing_user_query_conflict", "SeatAvailabilityQuery.java",
     "if (occupancy.userConflict())", "if (false)",
     "ownOverlapInAnotherRoomReturnsExplicitConflict",
     "SeatAvailabilityQueryTest#ownOverlapInAnotherRoomReturnsExplicitConflict"),
    ("missing_outlet_filter", "SeatAvailabilityQuery.java",
     '.filter(seat -> !requiresPower || seat.features().contains("POWER_OUTLET"))', '.filter(seat -> true)',
     "powerFilterUsesExactSeatMarkerAndStillExcludesOccupiedSeats",
     "SeatAvailabilityQueryTest#powerFilterUsesExactSeatMarkerAndStillExcludesOccupiedSeats"),
    ("missing_department_booking_scope", "ReservationAccessPolicy.java",
     "room.bookingScope() == BookingScope.DEPARTMENT", "false",
     "departmentRoomsRejectOtherDepartmentsEvenWithAllBusinessPermissions",
     "ReservationAccessPolicyTest#departmentRoomsRejectOtherDepartmentsEvenWithAllBusinessPermissions"),
    ("missing_seat_permission", "ReservationAccessPolicy.java",
     "!identity.permissions().contains(action.permission())", "false",
     "catalogOrAdministrativePermissionsDoNotGrantSeatAccess",
     "ReservationAccessPolicyTest#catalogOrAdministrativePermissionsDoNotGrantSeatAccess"),
]
PLATFORM_MUTATIONS = [
    ("reset_scope_on_legacy_update", "ResourceService.java",
     "return requested!=null?requested:current!=null?current:BookingScope.DEPARTMENT;",
     "return requested!=null?requested:BookingScope.DEPARTMENT;",
     "roomScopeSurvivesLegacyUpdatesAndIsVisibleThroughInternalApi",
     "PlatformIntegrationTest#roomScopeSurvivesLegacyUpdatesAndIsVisibleThroughInternalApi"),
    ("room_outlet_marker_allowed", "ResourceService.java",
     'if(!type.equals("SEAT")&&features.contains("POWER_OUTLET"))', "if(false)",
     "powerOutletMarkerBelongsToIndividualSeatsAndRoundTrips",
     "PlatformIntegrationTest#powerOutletMarkerBelongsToIndividualSeatsAndRoundTrips"),
    ("seat_role_unprotected", "RoleService.java",
     '"AUDITOR","MEMBER","SEAT_USER"', '"AUDITOR","MEMBER"',
     "seatRoleIsProtectedAndCustomRolesCanSelectRegisteredSeatPermissions",
     "PlatformIntegrationTest#seatRoleIsProtectedAndCustomRolesCanSelectRegisteredSeatPermissions"),
]


def source_hashes(core):
    return {str(p.relative_to(core)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in (core / "src").rglob("*.java")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--module", choices=("seats", "platform"), default="seats")
    args = parser.parse_args()
    core = ROOT / "services" / args.module
    package = Path("src/main/java/io/campusflow") / args.module
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    original = source_hashes(core)
    results = []
    for name, filename, before, after, expected_test, test_selector in (MUTATIONS if args.module == "seats" else PLATFORM_MUTATIONS):
        with tempfile.TemporaryDirectory(prefix="campusflow-m1-mutation-") as directory:
            copy = Path(directory) / args.module
            shutil.copytree(core, copy, ignore=shutil.ignore_patterns("target"))
            source = copy / package / filename
            text = source.read_text(encoding="utf-8")
            if text.count(before) != 1:
                raise RuntimeError(f"Mutation target changed: {name}; review the experiment")
            source.write_text(text.replace(before, after), encoding="utf-8")
            with (output / f"{name}.log").open("w", encoding="utf-8") as log:
                maven = os.environ.get("CF_MAVEN_EXECUTABLE", str(ROOT / "services/platform/mvnw"))
                run = subprocess.run(
                    [maven, "-o", "-B", "-ntp",
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
    if source_hashes(core) != original:
        raise RuntimeError("Original source changed during the experiment")
    summary = {"module": args.module, "results": results, "original_source_unchanged": True}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
