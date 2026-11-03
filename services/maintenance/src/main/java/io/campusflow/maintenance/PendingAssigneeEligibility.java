package io.campusflow.maintenance;
import org.springframework.stereotype.Component;
@Component
class PendingAssigneeEligibility implements AssigneeEligibility {
    public Decision check(Request request) { throw new Failure(503,"D_ELIGIBILITY_NOT_READY"); }
}
