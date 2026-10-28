#!/usr/bin/env python3
"""Ordinary gateway/calendar checks on disposable source trees."""
import check_m1_core_mutations as runner
runner.MUTATIONS=[
 ('calendar_extra_day','ReservationPolicy.java','plusDays(maxAdvance.toDays()+1)','plusDays(maxAdvance.toDays()+2)',
  'calendarDayWindowIncludesTomorrowEvenBeyondTwentyFourHours','ReservationRulesTest#calendarDayWindowIncludesTomorrowEvenBeyondTwentyFourHours'),
 ('calendar_uses_utc_day','ReservationPolicy.java','now.atZone(CAMPUS_ZONE).toLocalDate().plusDays','now.atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays',
  'calendarWindowUsesShanghaiDayAndAcceptsConfigurableZeroAndTwoDays','ReservationRulesTest#calendarWindowUsesShanghaiDayAndAcceptsConfigurableZeroAndTwoDays'),
 ('ordinary_origin_bypass','http/ReservationWriteAccess.java','if(!origin.equals(single(request,"Origin")))','if(false)',
  'ordinaryHttpsOriginIsExactAndTokenRemainsSessionBound','io.campusflow.seats.http.ReservationWriteAccessTest#ordinaryHttpsOriginIsExactAndTokenRemainsSessionBound'),
 ('ordinary_returns_demo_stage','http/ReservationHandler.java','wire(result.reservation(),permit.stage())','wire(result.reservation(),"PERSONAL_DEMO")',
  'ordinaryApplicationCreatesWithoutAnyTestConfiguration','io.campusflow.seats.http.QueryHttpTest#ordinaryApplicationCreatesWithoutAnyTestConfiguration'),
]
if __name__=='__main__':runner.main()
