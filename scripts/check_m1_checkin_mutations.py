#!/usr/bin/env python3
"""Confirm CF-M1-007 tests reject security and lifecycle regressions in disposable copies."""
import check_m1_core_mutations as runner

runner.MUTATIONS = [
    ('daily_code_uses_utc', 'DailyCheckInCode.java',
     'ZoneId.of("Asia/Shanghai")', 'ZoneId.of("UTC")',
     'currentCodeRotatesExactlyAtShanghaiMidnight', 'DailyCheckInCodeTest#currentCodeRotatesExactlyAtShanghaiMidnight'),
    ('daily_code_ignores_room', 'DailyCheckInCode.java',
     '+ roomId + "\\n" + date', '+ "same-room" + "\\n" + date',
     'codeIsStableForRoomAndShanghaiDateButSeparatedByRoomAndDay', 'DailyCheckInCodeTest#codeIsStableForRoomAndShanghaiDateButSeparatedByRoomAndDay'),
    ('checkin_rate_limit_delayed', 'ReservationStore.java',
     'failures >= 5 ? now.plus(Duration.ofMinutes(15)) : null',
     'failures > 5 ? now.plus(Duration.ofMinutes(15)) : null',
     'wrongRoomOrDateCodeIsRejectedAndFailuresAreDurablyLimited', 'ReservationCheckInStoreTest#wrongRoomOrDateCodeIsRejectedAndFailuresAreDurablyLimited'),
    ('checkin_idempotency_removed', 'ReservationStore.java',
     'return new CheckInAttempt(current, true, null);',
     'return new CheckInAttempt(current, false, null);',
     'repeatedCheckInIsIdempotentAndDoesNotNeedCodeAgain', 'ReservationCheckInStoreTest#repeatedCheckInIsIdempotentAndDoesNotNeedCodeAgain'),
    ('display_department_scope_removed', 'http/CheckInHandler.java',
     'if (!identity.departmentId().equals(room.departmentId()))', 'if (false)',
     'authorizedDepartmentManagerReadsDailyRoomCodeWithoutCaching', 'io.campusflow.seats.http.QueryHttpTest#authorizedDepartmentManagerReadsDailyRoomCodeWithoutCaching'),
]

if __name__ == '__main__':
    runner.main()
