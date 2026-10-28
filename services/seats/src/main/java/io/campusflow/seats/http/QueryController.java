package io.campusflow.seats.http;

import io.campusflow.seats.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/m1/v1")
public final class QueryController {
    private final DQueryClient d;
    private final SeatAvailabilityQuery query;
    private final ReservationPolicy policy;
    public QueryController(DQueryClient d,SeatAvailabilityQuery query,ReservationPolicy policy) { this.d=d;this.query=query;this.policy=policy; }

    @GetMapping("/rooms") public Map<String,Object> rooms(HttpServletRequest request) {
        Params p=new Params(request,Set.of("page","size")); int page=p.page(),size=p.size();
        var call=d.call(); var identity=call.identity(session(request));
        var rooms=call.resources("ROOM",null).stream().filter(DQueryClient.Resource::effectiveActive)
                .filter(r -> r.scope()==ReservationAccessPolicy.BookingScope.CAMPUS || r.departmentId().equals(identity.departmentId()))
                .map(r -> Map.<String,Object>of("id",r.id(),"code",r.code(),"name",r.name(),
                        "bookingScope",r.scope(),"openingHours",r.hours())).toList();
        return Map.of("items",slice(rooms,page,size),"page",page,"size",size,"total",rooms.size());
    }
    @GetMapping("/seat-candidates") public Map<String,Object> candidates(HttpServletRequest request) {
        Params p=new Params(request,Set.of("roomId","startAt","endAt","requiresPower","page","size"));
        UUID roomId=p.id("roomId"); var period=new TimeRange(p.instant("startAt"),p.instant("endAt"));
        boolean power=p.bool("requiresPower"); int page=p.page(),size=p.size();
        var call=d.call(); var identity=call.identity(session(request));
        policy.validate(period);
        var room=call.room(roomId);
        ReservationAccessPolicy.scopeFor(identity,ReservationAccessPolicy.Action.QUERY,room.room());
        var seats=call.resources("SEAT",roomId);
        // A mismatch between D pages and the room is an upstream fault, not bad browser input.
        if (seats.stream().anyMatch(s -> !s.departmentId().equals(room.departmentId()))) throw QueryProblem.upstream();
        var result=query.queryForIdentity(identity,room.room(),seats.stream().map(DQueryClient.Resource::seat).toList(),period,power);
        Set<UUID> outlets=new HashSet<>();
        seats.stream().filter(s -> s.features().contains("POWER_OUTLET")).forEach(s -> outlets.add(s.id()));
        var items=result.seats().stream().map(s -> Map.<String,Object>of("id",s.id(),"code",s.code(),"hasPowerOutlet",outlets.contains(s.id()))).toList();
        return Map.of("roomId",roomId,"startAt",period.startAt(),"endAt",period.endAt(),"reason",result.reason(),
                "items",slice(items,page,size),"page",page,"size",size,"total",items.size(),"stage","CANDIDATE_ONLY","canReserve",false);
    }
    private static <T> List<T> slice(List<T> list,int page,int size) {
        int from=(int)Math.min((long)page*size,list.size()); return list.subList(from,Math.min(from+size,list.size()));
    }
    private static String session(HttpServletRequest request) {
        String session=null;
        if(request.getCookies()!=null) for(Cookie c:request.getCookies()) if(c.getName().equals("CF_SESSION")) {
            if(session!=null) throw new QueryProblem(401,"SESSION_REQUIRED","请重新登录"); session=c.getValue();
        }
        return session;
    }
    private record Params(HttpServletRequest request,Set<String> allowed) {
        Params {
            for(var e:request.getParameterMap().entrySet()) if(!allowed.contains(e.getKey()) || e.getValue().length!=1) throw QueryProblem.invalid();
        }
        String value(String name,String fallback) { String v=request.getParameter(name); return v==null?fallback:v; }
        int integer(String name,int fallback,int min,int max) {
            try { String s=value(name,""+fallback); if(!s.matches("0|[1-9][0-9]{0,5}")) throw QueryProblem.invalid();
                int n=Integer.parseInt(s); if(n<min||n>max) throw QueryProblem.invalid(); return n;
            } catch(IllegalArgumentException e) { throw QueryProblem.invalid(); }
        }
        int page() { return integer("page",0,0,100000); }
        int size() { return integer("size",20,1,100); }
        UUID id(String name) { try { String s=value(name,""); UUID id=UUID.fromString(s); if(!id.toString().equalsIgnoreCase(s)) throw QueryProblem.invalid(); return id; } catch(IllegalArgumentException e) { throw QueryProblem.invalid(); } }
        Instant instant(String name) { try { String s=value(name,""); if(!s.endsWith("Z")) throw QueryProblem.invalid(); return Instant.parse(s); } catch(java.time.DateTimeException e) { throw QueryProblem.invalid(); } }
        boolean bool(String name) { String v=value(name,"false"); if(!v.equals("true")&&!v.equals("false")) throw QueryProblem.invalid(); return v.equals("true"); }
    }
}
