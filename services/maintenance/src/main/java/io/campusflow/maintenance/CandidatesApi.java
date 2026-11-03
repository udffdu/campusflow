package io.campusflow.maintenance;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
@RestController
@RequestMapping("/api/v1/maintenance/management/work-orders/{id}/candidates")
class CandidatesApi {
    private final DClient d;private final JdbcTemplate db;private final CandidateProvider provider;
    CandidatesApi(DClient d,JdbcTemplate db,CandidateProvider provider){this.d=d;this.db=db;this.provider=provider;}
    @GetMapping CandidateProvider.Page search(@PathVariable UUID id,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest req){
        String token=null;if(req.getCookies()!=null)for(var c:req.getCookies())if(c.getName().equals("CF_SESSION"))token=c.getValue();
        var actor=d.authorize(token,"maintenance.dispatch",null);
        for(String key:req.getParameterMap().keySet())if(!Set.of("q","page","size").contains(key)||req.getParameterValues(key).length!=1)throw new Failure(400,"INVALID_PARAMETER");
        if(page<0||page>100000||size<1||size>100||q.codePointCount(0,q.length())>100)throw new Failure(400,"INVALID_PARAMETER");
        var rows=db.queryForList("select room_id,department_id,status from work_orders where id=? and (? or department_id=?)",id,actor.admin(),actor.departmentId());
        if(rows.isEmpty())throw new Failure(404,"NOT_FOUND");var order=rows.getFirst();
        if(!"PENDING_DISPATCH".equals(order.get("status")))throw new Failure(409,"INVALID_STATE");
        UUID department=(UUID)order.get("department_id"),room=(UUID)order.get("room_id");
        var result=provider.search(actor.id(),room,department,q.strip(),page,size);
        // Never filter a broken provider page silently: doing so would corrupt total/pagination.
        if(result==null||result.items()==null||result.page()!=page||result.size()!=size||result.total()<0||result.items().size()!=Math.min(size,Math.max(0,result.total()-(long)page*size)))throw new Failure(503,"D_INVALID_CANDIDATES");
        var ids=new HashSet<UUID>();
        for(var person:result.items())if(person==null||person.id()==null||!ids.add(person.id())||person.displayName()==null||person.displayName().isBlank()||!person.active()||!"maintenance.repair".equals(person.qualification())||person.departmentIds()==null||(!person.globalScope()&&!person.departmentIds().contains(department)))throw new Failure(503,"D_INVALID_CANDIDATES");
        return result;
    }
}
