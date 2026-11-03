#!/usr/bin/env python3
"""Behavioral checks on disposable copies of the M1-A submission implementation."""
import check_m1_core_mutations as runner

HTTP='io.campusflow.seats.http.QueryHttpTest'
runner.MUTATIONS=[
 ('skip_csrf_origin','http/ReservationHandler.java','permit.verify(servlet,session);','/* deliberately bypass CSRF and Origin */',
  'csrfAndOriginFailuresDoNotReachDatabase',HTTP+'#csrfAndOriginFailuresDoNotReachDatabase'),
 ('csrf_not_bound_to_session','http/ReservationWriteAccess.java',r'("m1-csrf\n"+session+"\n"+payload)',r'("m1-csrf\n"+"unbound"+"\n"+payload)',
  'validTokensAreRandomAndBoundToSession','io.campusflow.seats.http.DemoWritePermitTest#validTokensAreRandomAndBoundToSession'),
 ('skip_locked_authorization','ReservationStore.java','authorize.run();','/* deliberately skip online locked authorization */',
  'lockedAuthorizationRevocationPreventsCreation',HTTP+'#lockedAuthorizationRevocationPreventsCreation'),
 ('skip_new_resource_validation','ReservationStore.java','long resourceVersion=validateNew.getAsLong();','long resourceVersion=1;',
  'changedResourceOrBadVersionRollsBackNewRequest',HTTP+'#changedResourceOrBadVersionRollsBackNewRequest'),
 ('skip_replay_lookup','ReservationStore.java','if (!previous.isEmpty()) {','if (false) {',
  'demoCreatesAndReplaysExactlyOneReservationAndEvent',HTTP+'#demoCreatesAndReplaysExactlyOneReservationAndEvent'),
 ('skip_time_after_validation','ReservationStore.java','policy.validate(request.period());','/* deliberately skip late clock check */',
  'validatedCreateRechecksTimeAfterExternalValidation','ReservationStoreTest#validatedCreateRechecksTimeAfterExternalValidation'),
 ('foreign_record_leak','ReservationStore.java','select * from m1.reservations where user_id=? and id=?',
  'select * from m1.reservations where (user_id=? or user_id is not null) and id=?',
  'ownReadsHideOtherUsersRecordsAndKeys',HTTP+'#ownReadsHideOtherUsersRecordsAndKeys'),
]
if __name__=='__main__':runner.main()
