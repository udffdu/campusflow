package io.campusflow.maintenance;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class EligibilityGuardTest {
    private final AssigneeEligibility.Request request=new AssigneeEligibility.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
    private AssigneeEligibility.Decision decision(Boolean allowed){return new AssigneeEligibility.Decision(request.assigneeId(),request.roomId(),request.departmentId(),allowed,"TEST_ONLY");}
    @Test void requiresExplicitAllow(){
        assertThatCode(()->new EligibilityGuard(r->decision(true)).requireEligible(request)).doesNotThrowAnyException();
        assertThatThrownBy(()->new EligibilityGuard(r->decision(false)).requireEligible(request)).hasMessage("ASSIGNEE_INELIGIBLE");
        assertThatThrownBy(()->new EligibilityGuard(r->decision(null)).requireEligible(request)).hasMessage("D_INVALID_ELIGIBILITY");
    }
    @Test void rejectsMissingOrMismatchedContext(){
        assertThatThrownBy(()->new EligibilityGuard(r->null).requireEligible(request)).hasMessage("D_INVALID_ELIGIBILITY");
        for(int mismatch=0;mismatch<3;mismatch++){
            var result=new AssigneeEligibility.Decision(mismatch==0?UUID.randomUUID():request.assigneeId(),mismatch==1?UUID.randomUUID():request.roomId(),mismatch==2?UUID.randomUUID():request.departmentId(),true,null);
            assertThatThrownBy(()->new EligibilityGuard(r->result).requireEligible(request)).hasMessage("D_INVALID_ELIGIBILITY");
        }
    }
    @Test void pendingProviderNeverGrantsEligibility(){
        assertThatThrownBy(()->new EligibilityGuard(new PendingAssigneeEligibility()).requireEligible(request)).hasMessage("D_ELIGIBILITY_NOT_READY");
    }
}
