package io.campusflow.maintenance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/maintenance")
class LifecycleApi {
    private final DClient d;private final Lifecycle lifecycle;private final boolean enabled;
    LifecycleApi(DClient d,Lifecycle lifecycle,@Value("${maintenance.lifecycle-enabled:false}") boolean enabled){this.d=d;this.lifecycle=lifecycle;this.enabled=enabled;}
    private DClient.Actor actor(HttpServletRequest req,String permission,Set<String> allowed){
        if(!enabled)throw new Failure(503,"LIFECYCLE_NOT_ENABLED");
        String token=null;if(req.getCookies()!=null)for(var cookie:req.getCookies())if(cookie.getName().equals("CF_SESSION"))token=cookie.getValue();
        var actor=d.authorize(token,permission,null);
        for(String key:req.getParameterMap().keySet())if(!allowed.contains(key)||req.getParameterValues(key).length!=1)throw new Failure(400,"INVALID_PARAMETER");
        return actor;
    }
    @GetMapping("/repair/work-orders") Map<String,Object> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest req){return lifecycle.list(actor(req,"maintenance.repair",Set.of("page","size")).id(),page,size);}
    @GetMapping("/repair/work-orders/{id}") Map<String,Object> detail(@PathVariable UUID id,HttpServletRequest req){return lifecycle.detail(actor(req,"maintenance.repair",Set.of()).id(),id);}
    private ResponseEntity<String> command(UUID id,Lifecycle.Action action,Lifecycle.Input input,String key,HttpServletRequest req){
        var actor=actor(req,action.acceptance?"maintenance.accept":"maintenance.repair",Set.of());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(lifecycle.execute(actor.id(),id,action,key,input));
    }
    @PostMapping("/repair/work-orders/{id}/start") ResponseEntity<String> start(@PathVariable UUID id,@RequestBody Lifecycle.Input input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req){return command(id,Lifecycle.Action.START,input,key,req);}
    @PostMapping("/repair/work-orders/{id}/completion") ResponseEntity<String> complete(@PathVariable UUID id,@RequestBody Lifecycle.Input input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req){return command(id,Lifecycle.Action.COMPLETE,input,key,req);}
    @PostMapping("/work-orders/{id}/acceptance/pass") ResponseEntity<String> pass(@PathVariable UUID id,@RequestBody Lifecycle.Input input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req){return command(id,Lifecycle.Action.PASS,input,key,req);}
    @PostMapping("/work-orders/{id}/acceptance/reject") ResponseEntity<String> reject(@PathVariable UUID id,@RequestBody Lifecycle.Input input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req){return command(id,Lifecycle.Action.REJECT,input,key,req);}
}
