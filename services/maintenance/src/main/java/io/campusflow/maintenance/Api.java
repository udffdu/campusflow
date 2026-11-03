package io.campusflow.maintenance;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/maintenance")
class Api {
    private final DClient d; private final Orders orders;
    Api(DClient d,Orders orders) { this.d=d;this.orders=orders; }
    private String token(HttpServletRequest request) {
        if(request.getCookies()!=null) for(var c:request.getCookies()) if(c.getName().equals("CF_SESSION")) return c.getValue();
        return null;
    }
    private void params(HttpServletRequest req,Set<String> allowed) {
        for(String key:req.getParameterMap().keySet()) if(!allowed.contains(key) || req.getParameterValues(key).length!=1) throw new Failure(400,"INVALID_PARAMETER");
    }
    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken csrf) { return Map.of("token",csrf.getToken(),"headerName",csrf.getHeaderName()); }
    @PostMapping("/work-orders") ResponseEntity<String> create(@RequestBody Orders.Input input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) {
        params(req,Set.of());String token=token(req);
        var actor=d.authorize(token,"maintenance.report",null);
        var valid=orders.validate(input);var room=d.room(valid.roomId());
        var scoped=d.authorize(token,"maintenance.report",room.departmentId());
        if(!actor.id().equals(scoped.id())) throw new Failure(403,"ACCESS_DENIED");
        return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(orders.create(scoped,room,key,valid));
    }
    @GetMapping("/work-orders") Map<String,Object> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest req) {
        var actor=d.authorize(token(req),"maintenance.read.own",null);params(req,Set.of("page","size"));return orders.list(actor.id(),page,size);
    }
    @GetMapping("/work-orders/{id}") Map<String,Object> detail(@PathVariable UUID id,HttpServletRequest req) {
        var actor=d.authorize(token(req),"maintenance.read.own",null);params(req,Set.of());return orders.detail(actor.id(),id);
    }
}
