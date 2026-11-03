package io.campusflow.seats.http;

import io.campusflow.seats.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Department-scoped operational reads. Responses deliberately omit reservation owners. */
@RestController
@RequestMapping("/api/m1/v1/operations")
public final class ReservationOperationsController {
    private static final Set<String> PARAMETERS=Set.of("from","to","roomId","seatId","status","size","cursor");
    private final DQueryClient d;
    private final ReservationOperationsStore store;
    public ReservationOperationsController(DQueryClient d,ReservationOperationsStore store){this.d=d;this.store=store;}

    @GetMapping("/reservations") public Map<String,Object> reservations(HttpServletRequest request){
        Params p=new Params(request);Instant from=p.instant("from"),to=p.instant("to");
        if(!from.isBefore(to)||Duration.between(from,to).compareTo(ReservationOperationsStore.MAX_RANGE)>0)throw QueryProblem.invalid();
        UUID roomFilter=p.optionalId("roomId"),seatFilter=p.optionalId("seatId");ReservationStatus status=p.status();int size=p.size();
        var call=d.call();var identity=call.identity(QueryController.session(request),ReservationAccessPolicy.Action.OPERATIONS_READ);
        List<DQueryClient.Resource> rooms=call.resources("ROOM",null).stream().filter(r->r.departmentId().equals(identity.departmentId())).toList();
        Set<UUID> roomIds=new HashSet<>();rooms.forEach(r->roomIds.add(r.id()));
        List<DQueryClient.Resource> seats=call.resources("SEAT",null).stream()
                .filter(s->s.departmentId().equals(identity.departmentId())&&roomIds.contains(s.parentId())).toList();
        Map<UUID,DQueryClient.Resource> roomMap=index(rooms),seatMap=index(seats);
        if(roomFilter!=null&&!roomMap.containsKey(roomFilter))throw new QueryProblem(403,"OPERATIONS_SCOPE_DENIED","教室不在当前运营范围");
        if(seatFilter!=null&&!seatMap.containsKey(seatFilter))throw new QueryProblem(403,"OPERATIONS_SCOPE_DENIED","座位不在当前运营范围");
        if(roomFilter!=null&&seatFilter!=null&&!roomFilter.equals(seatMap.get(seatFilter).parentId()))throw QueryProblem.invalid();
        var filter=new ReservationOperationsStore.Filter(from,to,Set.copyOf(roomIds),roomFilter,seatFilter,status);
        String fingerprint=fingerprint(identity.departmentId(),filter);var result=store.query(filter,decode(p.cursor(),fingerprint),size);
        var criteria=new LinkedHashMap<String,Object>();criteria.put("from",from);criteria.put("to",to);
        if(roomFilter!=null)criteria.put("roomId",roomFilter);if(seatFilter!=null)criteria.put("seatId",seatFilter);if(status!=null)criteria.put("status",status);
        var resourceWire=Map.of("rooms",rooms.stream().map(ReservationOperationsController::roomWire).toList(),
                "seats",seats.stream().map(ReservationOperationsController::seatWire).toList());
        return Map.of("scope",Map.of("departmentId",identity.departmentId()),"criteria",criteria,"resources",resourceWire,
                "items",result.items().stream().map(e->entryWire(e,roomMap,seatMap)).toList(),"size",size,
                "nextCursor",result.next()==null?"":encode(result.next(),fingerprint),"total",result.summary().total(),
                "summary",summaryWire(result.summary(),roomMap,seatMap),"serverTime",result.serverTime());
    }

    private static Map<String,Object> summaryWire(ReservationOperationsStore.Summary summary,
                                                   Map<UUID,DQueryClient.Resource> rooms,Map<UUID,DQueryClient.Resource> seats){
        var byStatus=new LinkedHashMap<String,Long>();for(var state:ReservationStatus.values())byStatus.put(state.name(),summary.byStatus().getOrDefault(state,0L));
        var value=new LinkedHashMap<String,Object>();value.put("total",summary.total());value.put("byStatus",byStatus);
        value.put("checkedIn",summary.checkedIn());value.put("violations",summary.violations());value.put("eligible",summary.eligible());
        value.put("checkInRate",summary.checkInRate());value.put("reservedDurationSeconds",summary.reservedDurationSeconds());
        value.put("actualUsageSeconds",summary.actualUsageSeconds());value.put("utilizationRate",summary.utilizationRate());
        value.put("byRoom",summary.byRoom().stream().map(c->countWire(c,rooms.get(c.id()))).toList());
        value.put("bySeat",summary.bySeat().stream().map(c->countWire(c,seats.get(c.id()))).toList());return value;
    }
    private static Map<String,Object> entryWire(ReservationOperationsStore.Entry entry,Map<UUID,DQueryClient.Resource> rooms,
                                                 Map<UUID,DQueryClient.Resource> seats){
        var value=new LinkedHashMap<String,Object>();value.put("id",entry.id());value.put("roomId",entry.roomId());
        value.put("roomCode",code(rooms.get(entry.roomId())));value.put("seatId",entry.seatId());value.put("seatCode",code(seats.get(entry.seatId())));
        value.put("startAt",entry.startAt());value.put("endAt",entry.endAt());value.put("status",entry.status());
        value.put("checkedInAt",entry.checkedInAt());value.put("completedAt",entry.completedAt());value.put("usageDurationSeconds",entry.usageDurationSeconds());
        value.put("createdAt",entry.createdAt());value.put("updatedAt",entry.updatedAt());return value;
    }
    private static Map<String,Object> countWire(ReservationOperationsStore.Count count,DQueryClient.Resource resource){
        var value=new LinkedHashMap<String,Object>();value.put("id",count.id());value.put("code",code(resource));value.put("count",count.count());return value;
    }
    private static String code(DQueryClient.Resource resource){return resource==null?null:resource.code();}
    private static Map<String,Object> roomWire(DQueryClient.Resource room){return Map.of("id",room.id(),"code",room.code(),"name",room.name());}
    private static Map<String,Object> seatWire(DQueryClient.Resource seat){return Map.of("id",seat.id(),"roomId",seat.parentId(),"code",seat.code());}
    private static Map<UUID,DQueryClient.Resource> index(List<DQueryClient.Resource> resources){
        var values=new HashMap<UUID,DQueryClient.Resource>();for(var resource:resources)if(values.put(resource.id(),resource)!=null)throw QueryProblem.upstream();return Map.copyOf(values);
    }

    private static String fingerprint(UUID department,ReservationOperationsStore.Filter filter){
        try{String raw=department+"|"+filter.from()+"|"+filter.to()+"|"+filter.roomId()+"|"+filter.seatId()+"|"+filter.status();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception exception){throw new IllegalStateException(exception);}
    }
    private static String encode(ReservationOperationsStore.Position position,String fingerprint){
        String raw=fingerprint+"|"+position.startAt()+"|"+position.id();return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
    private static ReservationOperationsStore.Position decode(String cursor,String fingerprint){
        if(cursor==null)return null;
        try{if(!cursor.matches("[A-Za-z0-9_-]{1,400}"))throw QueryProblem.invalid();
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=3||!MessageDigest.isEqual(parts[0].getBytes(StandardCharsets.US_ASCII),fingerprint.getBytes(StandardCharsets.US_ASCII)))throw QueryProblem.invalid();
            Instant start=Instant.parse(parts[1]);UUID id=UUID.fromString(parts[2]);return new ReservationOperationsStore.Position(start,id);
        }catch(IllegalArgumentException|DateTimeException exception){throw QueryProblem.invalid();}
    }

    private record Params(HttpServletRequest request){
        Params{for(var entry:request.getParameterMap().entrySet())if(!PARAMETERS.contains(entry.getKey())||entry.getValue().length!=1)throw QueryProblem.invalid();}
        String raw(String name){String value=request.getParameter(name);if(value!=null&&value.isBlank())throw QueryProblem.invalid();return value;}
        Instant instant(String name){try{String value=raw(name);if(value==null||!value.endsWith("Z"))throw QueryProblem.invalid();return Instant.parse(value);}catch(DateTimeException e){throw QueryProblem.invalid();}}
        UUID optionalId(String name){String value=raw(name);if(value==null)return null;try{UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw QueryProblem.invalid();return id;}catch(IllegalArgumentException e){throw QueryProblem.invalid();}}
        ReservationStatus status(){String value=raw("status");if(value==null)return null;try{return ReservationStatus.valueOf(value);}catch(IllegalArgumentException e){throw QueryProblem.invalid();}}
        int size(){String value=raw("size");if(value==null)return 20;try{if(!value.matches("[1-9][0-9]{0,2}"))throw QueryProblem.invalid();int size=Integer.parseInt(value);if(size>100)throw QueryProblem.invalid();return size;}catch(NumberFormatException e){throw QueryProblem.invalid();}}
        String cursor(){return raw("cursor");}
    }
}
