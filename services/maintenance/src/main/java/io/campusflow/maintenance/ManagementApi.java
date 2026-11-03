package io.campusflow.maintenance;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/maintenance/management/work-orders")
class ManagementApi {
    private final DClient d; private final Management management;
    ManagementApi(DClient d,Management management){this.d=d;this.management=management;}
    private DClient.Actor actor(HttpServletRequest req,Set<String> allowed){
        String token=null;
        if(req.getCookies()!=null) for(var cookie:req.getCookies()) if(cookie.getName().equals("CF_SESSION")) token=cookie.getValue();
        var actor=d.authorize(token,"maintenance.read.scope",null);
        for(String key:req.getParameterMap().keySet()) if(!allowed.contains(key)||req.getParameterValues(key).length!=1) throw new Failure(400,"INVALID_PARAMETER");
        return actor;
    }
    @GetMapping Map<String,Object> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String status,HttpServletRequest req){
        return management.list(actor(req,Set.of("page","size","status")),page,size,status);
    }
    @GetMapping("/{id}") Map<String,Object> detail(@PathVariable UUID id,HttpServletRequest req){return management.detail(actor(req,Set.of()),id);}
}
