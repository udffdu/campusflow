package io.campusflow.seats.http;

import io.campusflow.seats.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/m1/v1")
public final class ReservationReadController {
    private final DQueryClient d;
    private final ReservationStore store;
    private final ObjectProvider<ReservationWriteAccess> permit;
    private final ReservationPolicy policy;
    public ReservationReadController(DQueryClient d,ReservationStore store,ObjectProvider<ReservationWriteAccess> permit,ReservationPolicy policy) {this.d=d;this.store=store;this.permit=permit;this.policy=policy;}
    @GetMapping("/reservation-capabilities") public Map<String,Object> capabilities(HttpServletRequest request) {
        noParams(request);var identity=d.call().currentIdentity(QueryController.session(request));
        var access=permit.getIfAvailable();boolean create=access!=null&&identity.permissions().contains(ReservationAccessPolicy.Action.CREATE.permission());
        return Map.of("stage",access==null?"CANDIDATE_ONLY":access.stage(),"productionReady",false,
                "canCreateDemoReservation",create&&access.stage().equals("PERSONAL_DEMO"),
                "canCreateReservation",create&&access.stage().equals("PERSONAL"),
                "canReadOwn",identity.permissions().contains(ReservationAccessPolicy.Action.READ_OWN.permission()),
                "uncheckedSources",List.of("M2","M3"),"advanceMode",policy.advanceMode(),"advanceDays",policy.advanceDays(),"durationHours",policy.durationHours());
    }
    @GetMapping("/reservations/{id}") public Map<String,Object> find(@PathVariable String id,HttpServletRequest request) {
        noParams(request);UUID uuid=uuid(id);var identity=d.call().identity(QueryController.session(request),ReservationAccessPolicy.Action.READ_OWN);
        return wire(store.findForUser(identity.userId(),uuid).orElseThrow(ReservationReadController::missing),stage());
    }
    @GetMapping("/reservations") public Map<String,Object> history(HttpServletRequest request) {
        for(var e:request.getParameterMap().entrySet())if(!Set.of("size","cursor").contains(e.getKey())||e.getValue().length!=1)throw QueryProblem.invalid();
        String raw=request.getParameter("size");int size=20;
        if(raw!=null){if(!raw.matches("[1-9][0-9]?"))throw QueryProblem.invalid();size=Integer.parseInt(raw);if(size>50)throw QueryProblem.invalid();}
        var identity=d.call().identity(QueryController.session(request),ReservationAccessPolicy.Action.READ_OWN);
        var page=store.historyForUser(identity.userId(),position(request.getParameter("cursor"),identity.userId()),size);
        String next=page.next()==null?"":Base64.getUrlEncoder().withoutPadding().encodeToString(
                (identity.userId()+"|"+page.next().createdAt()+"|"+page.next().id()).getBytes(StandardCharsets.UTF_8));
        return Map.of("items",page.items().stream().map(r->wire(r,stage())).toList(),"size",size,"nextCursor",next);
    }
    @GetMapping("/reservations/{id}/repeat-context") public Map<String,Object> repeat(@PathVariable String id,HttpServletRequest request) {
        noParams(request);UUID uuid=uuid(id);String session=QueryController.session(request);var call=d.call();
        var owner=call.identity(session,ReservationAccessPolicy.Action.READ_OWN);
        var original=store.findForUser(owner.userId(),uuid).orElseThrow(ReservationReadController::missing);
        var identity=call.identity(session,ReservationAccessPolicy.Action.QUERY);
        if(!owner.userId().equals(identity.userId()))throw QueryProblem.upstream();
        var room=call.room(original.roomId());var seat=call.seat(original.seatId());
        ReservationAccessPolicy.scopeFor(identity,ReservationAccessPolicy.Action.QUERY,room.room());
        if(!room.id().equals(seat.parentId())||!room.departmentId().equals(seat.departmentId()))throw QueryProblem.upstream();
        if(!room.effectiveActive()||!seat.effectiveActive())throw new QueryProblem(409,"RESOURCE_INACTIVE","原教室或座位已停用，请重新选择");
        return Map.of("reservationId",original.id(),"room",Map.of("id",room.id(),"code",room.code(),"name",room.name(),"bookingScope",room.scope(),"openingHours",room.hours()),
                "seat",Map.of("id",seat.id(),"code",seat.code()),"requiresNewTime",true,"canReserve",false);
    }
    private static ReservationStore.HistoryPosition position(String cursor,UUID userId) {
        if(cursor==null)return null;
        try{
            if(!cursor.matches("[A-Za-z0-9_-]{1,200}"))throw QueryProblem.invalid();
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=3||!uuid(parts[0]).equals(userId))throw QueryProblem.invalid();
            Instant instant=Instant.parse(parts[1]);
            if(!parts[1].endsWith("Z")||instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||instant.isAfter(Instant.parse("9999-12-31T23:59:59Z")))throw QueryProblem.invalid();
            return new ReservationStore.HistoryPosition(instant,uuid(parts[2]));
        }catch(IllegalArgumentException|java.time.DateTimeException e){throw QueryProblem.invalid();}
    }
    @GetMapping("/reservation-requests/{key}") public Map<String,Object> findRequest(@PathVariable String key,HttpServletRequest request) {
        noParams(request);key(key);var identity=d.call().identity(QueryController.session(request),ReservationAccessPolicy.Action.READ_OWN);
        return wire(store.findForRequest(identity.userId(),key).orElseThrow(ReservationReadController::missing),stage());
    }
    static QueryProblem missing(){return new QueryProblem(404,"RESERVATION_NOT_FOUND","未查询到本人的已提交记录；请求可能仍在处理中，请保留原请求编号");}
    static UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw QueryProblem.invalid();return id;}catch(IllegalArgumentException e){throw QueryProblem.invalid();}}
    static void key(String key){if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw QueryProblem.invalid();}
    static void noParams(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw QueryProblem.invalid();}
    private String stage(){var access=permit.getIfAvailable();return access==null?"PERSONAL":access.stage();}
    public static Map<String,Object> wire(ReservationRecord record){return wire(record,"PERSONAL_DEMO");}
    public static Map<String,Object> wire(ReservationRecord record,String stage){
        return Map.of("id",record.id(),"roomId",record.roomId(),"seatId",record.seatId(),"startAt",record.period().startAt(),
                "endAt",record.period().endAt(),"status",record.status(),"version",record.version(),"resourceVersion",record.resourceVersion(),
                "stage",stage,"productionReady",false);
    }
}
