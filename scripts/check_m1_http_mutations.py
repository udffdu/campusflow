#!/usr/bin/env python3
"""Run only this phase's behavioral mutations in disposable copies; reuse the verified isolation runner."""
import check_m1_core_mutations as runner

TEST = 'io.campusflow.seats.http.QueryHttpTest'
runner.MUTATIONS = [
    ('ignore_online_deny', 'http/DQueryClient.java',
     'if (!allowed && requireAllowed) throw new QueryProblem(403', 'if (false) throw new QueryProblem(403',
     'explicitDenyCannotBeOverriddenByPermissionSnapshot', TEST+'#explicitDenyCannotBeOverriddenByPermissionSnapshot'),
    ('trust_permissionless_identity', 'http/DQueryClient.java',
     'if (allowed && !permissions.contains(permission))', 'if (false)',
     'trueWithoutRequiredPermissionFailsClosed', TEST+'#trueWithoutRequiredPermissionFailsClosed'),
    ('upstream_error_logs_user_out', 'http/DQueryClient.java',
     'if (response.statusCode()!=200) throw QueryProblem.upstream();',
     'if (response.statusCode()!=200) throw new QueryProblem(401,"SESSION_REQUIRED","请重新登录");',
     'dTransportFailureIs503NotBrowserLogout(int)[1]', TEST+'#dTransportFailureIs503NotBrowserLogout'),
    ('room_directory_scope_leak', 'http/QueryController.java',
     '.filter(r -> r.scope()==ReservationAccessPolicy.BookingScope.CAMPUS || r.departmentId().equals(identity.departmentId()))',
     '.filter(r -> true)',
     'roomTotalIsFilteredBeforeBrowserPagination', TEST+'#roomTotalIsFilteredBeforeBrowserPagination'),
    ('return_partial_d_page', 'http/DQueryClient.java',
     'if (items.size()!=expected) throw QueryProblem.upstream();',
     'if (items.size()!=expected) return List.copyOf(result);',
     'incompleteOrDuplicatePagesNeverReturnPartialResults', TEST+'#incompleteOrDuplicatePagesNeverReturnPartialResults'),
    ('renew_budget_each_request', 'http/DQueryClient.java',
     'long remaining=deadline-System.nanoTime();', 'long remaining=budget.toNanos();',
     'sharedBudgetIncludesPreviousCalls', TEST+'#sharedBudgetIncludesPreviousCalls'),
]
if __name__ == '__main__':
    runner.main()
