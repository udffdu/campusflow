package io.campusflow.maintenance;

import java.util.UUID;

/** Final eligibility check, separate from candidate discovery. D transport awaits review. */
public interface AssigneeEligibility {
    record Request(UUID actorId, UUID assigneeId, UUID roomId, UUID departmentId) {
        public Request {
            if(actorId==null||assigneeId==null||roomId==null||departmentId==null)
                throw new IllegalArgumentException("Eligibility context must be complete");
        }
    }
    record Decision(UUID assigneeId, UUID roomId, UUID departmentId, Boolean allowed, String reasonCode) {}
    Decision check(Request request);
}
