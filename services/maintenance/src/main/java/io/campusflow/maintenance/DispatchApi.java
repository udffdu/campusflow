package io.campusflow.maintenance;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/maintenance/management/work-orders/{id}/dispatch")
class DispatchApi {
 private final DClient d;private final Dispatch dispatch;
 DispatchApi(DClient d,Dispatch dispatch){this.d=d;this.dispatch=dispatch;}
 @PostMapping ResponseEntity<String> assign(@PathVariable UUID id,@RequestBody Dispatch.Input input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req){
  String token=null;if(req.getCookies()!=null)for(var c:req.getCookies())if(c.getName().equals("CF_SESSION"))token=c.getValue();
  var actor=d.authorize(token,"maintenance.dispatch",null);
  if(!req.getParameterMap().isEmpty())throw new Failure(400,"INVALID_PARAMETER");
  return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(dispatch.assign(actor,id,key,input));
 }
}
