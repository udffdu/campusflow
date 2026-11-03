package io.campusflow.maintenance;
import org.springframework.stereotype.Component;

/** Must be invoked by the dispatch command after actor and work-order authorization. */
@Component
public class EligibilityGuard {
    private final AssigneeEligibility provider;
    public EligibilityGuard(AssigneeEligibility provider){this.provider=provider;}
    public void requireEligible(AssigneeEligibility.Request request){
        var decision=provider.check(request);
        if(decision==null||decision.allowed()==null||!request.assigneeId().equals(decision.assigneeId())
            ||!request.roomId().equals(decision.roomId())||!request.departmentId().equals(decision.departmentId()))
            throw new Failure(503,"D_INVALID_ELIGIBILITY");
        if(!Boolean.TRUE.equals(decision.allowed()))throw new Failure(409,"ASSIGNEE_INELIGIBLE");
    }
}
