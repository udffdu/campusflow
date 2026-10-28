#!/usr/bin/env python3
"""History/privacy/repeat behavior mutations on disposable M1 source copies only."""
import check_m1_core_mutations as runner

HTTP='io.campusflow.seats.http.QueryHttpTest'
runner.MUTATIONS=[
 ('history_foreign_leak','ReservationStore.java',
  '"select * from m1.reservations where user_id=?"+boundary',
  '"select * from m1.reservations where (user_id=? or true)"+boundary',
  'historyIsOwnerScopedAndIncludesAllStatesWithStableTies','ReservationStoreTest#historyIsOwnerScopedAndIncludesAllStatesWithStableTies'),
 ('history_wrong_cursor_direction','ReservationStore.java',
  'boundary=" and (created_at,id) < (?,?)"','boundary=" and (created_at,id) > (?,?)"',
  'historyCursorDoesNotRepeatRowsAfterNewInsertion','ReservationStoreTest#historyCursorDoesNotRepeatRowsAfterNewInsertion'),
 ('history_hides_terminal_states','ReservationStore.java',
  '"select * from m1.reservations where user_id=?"+boundary',
  '"select * from m1.reservations where user_id=? and status=\'CONFIRMED\'"+boundary',
  'historyIsOwnerScopedAndIncludesAllStatesWithStableTies','ReservationStoreTest#historyIsOwnerScopedAndIncludesAllStatesWithStableTies'),
 ('repeat_ignores_resource_disable','http/ReservationReadController.java',
  'if(!room.effectiveActive()||!seat.effectiveActive())','if(false)',
  'repeatContextRechecksCurrentScopeDisableAndQueryPermission',HTTP+'#repeatContextRechecksCurrentScopeDisableAndQueryPermission'),
]
if __name__=='__main__':runner.main()
