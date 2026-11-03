package io.campusflow.maintenance;
import org.springframework.stereotype.Component;
import java.util.UUID;
@Component
class PendingCandidateProvider implements CandidateProvider {
    public Page search(UUID actorId,UUID roomId,UUID departmentId,String query,int page,int size) {
        throw new Failure(503,"D_CANDIDATES_NOT_READY");
    }
}
