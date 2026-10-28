package io.campusflow.seats.http;

import io.campusflow.seats.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/m1/v1")
public final class ReservationReadController {
    private final DQueryClient d;
    private final ReservationStore store;
    private final ObjectProvider<DemoWritePermit> permit;
    public ReservationReadController(DQueryClient d,ReservationStore store,ObjectProvider<DemoWritePermit> permit) {this.d=d;this.store=store;this.permit=permit;}
    @GetMapping("/reservation-capabilities") public Map<String,Object> capabilities(HttpServletRequest request) {
        noParams(request);var identity=d.call().currentIdentity(QueryController.session(request));
        return Map.of("stage",permit.getIfAvailable()==null?"CANDIDATE_ONLY":"PERSONAL_DEMO","productionReady",false,
                "canCreateDemoReservation",permit.getIfAvailable()!=null&&identity.permissions().contains(ReservationAccessPolicy.Action.CREATE.permission()),
                "canReadOwn",identity.permissions().contains(ReservationAccessPolicy.Action.READ_OWN.permission()));
    }
    @GetMapping("/reservations/{id}") public Map<String,Object> find(@PathVariable String id,HttpServletRequest request) {
        noParams(request);UUID uuid=uuid(id);var identity=d.call().identity(QueryController.session(request),ReservationAccessPolicy.Action.READ_OWN);
        return wire(store.findForUser(identity.userId(),uuid).orElseThrow(ReservationReadController::missing));
    }
    @GetMapping("/reservation-requests/{key}") public Map<String,Object> findRequest(@PathVariable String key,HttpServletRequest request) {
        noParams(request);key(key);var identity=d.call().identity(QueryController.session(request),ReservationAccessPolicy.Action.READ_OWN);
        return wire(store.findForRequest(identity.userId(),key).orElseThrow(ReservationReadController::missing));
    }
    static QueryProblem missing(){return new QueryProblem(404,"RESERVATION_NOT_FOUND","未查询到本人的已提交记录；请求可能仍在处理中，请保留原请求编号");}
    static UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw QueryProblem.invalid();return id;}catch(IllegalArgumentException e){throw QueryProblem.invalid();}}
    static void key(String key){if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw QueryProblem.invalid();}
    static void noParams(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw QueryProblem.invalid();}
    public static Map<String,Object> wire(ReservationRecord record){
        return Map.of("id",record.id(),"roomId",record.roomId(),"seatId",record.seatId(),"startAt",record.period().startAt(),
                "endAt",record.period().endAt(),"status",record.status(),"version",record.version(),"resourceVersion",record.resourceVersion(),
                "stage","PERSONAL_DEMO","productionReady",false);
    }
}
