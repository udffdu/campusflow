package io.campusflow.maintenance;
import java.util.*;
/** M3 integration port. D transport contract awaits provider review. */
public interface CandidateProvider {
    record Candidate(UUID id,String displayName,boolean active,String qualification,boolean globalScope,List<UUID> departmentIds) {}
    record Page(List<Candidate> items,int page,int size,long total) {}
    Page search(UUID actorId,UUID roomId,UUID departmentId,String query,int page,int size);
}
