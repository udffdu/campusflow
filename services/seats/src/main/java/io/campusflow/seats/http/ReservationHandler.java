package io.campusflow.seats.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.campusflow.seats.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.servlet.function.*;

/** One booking implementation; assembled with explicit ordinary or legacy-demo protection. */
public final class ReservationHandler {
    private final DQueryClient d;
    private final ReservationStore store;
    private final ReservationWriteAccess permit;
    private final ObjectMapper json;
    public ReservationHandler(DQueryClient d,ReservationStore store,ReservationWriteAccess permit,ObjectMapper json){this.d=d;this.store=store;this.permit=permit;this.json=json;}
    public RouterFunction<ServerResponse> routes(){return RouterFunctions.route().GET("/api/m1/v1/csrf",request->safe(request,true))
            .POST("/api/m1/v1/reservations",request->safe(request,false)).build();}
    private ServerResponse safe(ServerRequest request,boolean csrf) {
        try{return csrf?csrf(request):create(request);}
        catch(Exception e){var result=QueryErrors.response(e,request.servletRequest());return ServerResponse.status(result.getStatusCode()).body(result.getBody());}
    }
    private ServerResponse csrf(ServerRequest request){
        ReservationReadController.noParams(request.servletRequest());String session=QueryController.session(request.servletRequest());
        var identity=d.call().currentIdentity(session);
        if(!identity.permissions().contains(ReservationAccessPolicy.Action.CREATE.permission())
                && !identity.permissions().contains(ReservationAccessPolicy.Action.READ_OWN.permission()))
            throw new QueryProblem(403,"FORBIDDEN","缺少个人预约写入权限");
        return ServerResponse.ok().body(Map.of("token",permit.issue(session),"headerName","X-M1-CSRF-Token"));
    }
    private ServerResponse create(ServerRequest request)throws Exception {
        var servlet=request.servletRequest();ReservationReadController.noParams(servlet);String session=QueryController.session(servlet);
        permit.verify(servlet,session);String key=ReservationWriteAccess.single(servlet,"Idempotency-Key");ReservationReadController.key(key);
        String type=servlet.getContentType();if(type==null||!type.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?"))throw new QueryProblem(415,"JSON_REQUIRED","请使用JSON提交");
        byte[] bytes=servlet.getInputStream().readNBytes(4097);if(bytes.length>4096)throw new QueryProblem(413,"BODY_TOO_LARGE","提交内容过长");
        JsonNode body;
        try {body=json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);}
        catch(Exception e){throw QueryProblem.invalid();}
        if(body==null||!body.isObject()||body.size()!=4)throw QueryProblem.invalid();
        Set<String> names=new HashSet<>();body.fieldNames().forEachRemaining(names::add);
        if(!names.equals(Set.of("roomId","seatId","startAt","endAt")))throw QueryProblem.invalid();
        UUID room=ReservationReadController.uuid(text(body,"roomId")),seat=ReservationReadController.uuid(text(body,"seatId"));
        var period=new TimeRange(instant(body,"startAt"),instant(body,"endAt"));var call=d.call();
        var identity=call.identity(session,ReservationAccessPolicy.Action.CREATE);
        var current=new AtomicReference<>(identity);
        var result=store.createValidated(new ReservationIntent(identity.userId(),room,seat,period),key,()->{
            var latest=call.identity(session,ReservationAccessPolicy.Action.CREATE);
            if(!latest.userId().equals(identity.userId()))throw QueryProblem.upstream();current.set(latest);
        },()->{
            var roomData=call.room(room);var seatData=call.seat(seat);
            ReservationAccessPolicy.scopeFor(current.get(),ReservationAccessPolicy.Action.CREATE,roomData.room());
            if(!room.equals(seatData.parentId())||!roomData.departmentId().equals(seatData.departmentId()))throw QueryProblem.upstream();
            if(!roomData.effectiveActive()||!seatData.effectiveActive())throw new QueryProblem(409,"RESOURCE_INACTIVE","教室或座位已停用，请重新查询");
            if(!roomData.schedule().covers(period))throw new QueryProblem(409,"OUTSIDE_OPENING_HOURS","时段未完整覆盖在开放时间内");
            return seatData.version();
        });
        var response=new LinkedHashMap<>(ReservationReadController.wire(result.reservation(),permit.stage()));response.put("replayed",result.replayed());
        return ServerResponse.status(result.replayed()?200:201).body(response);
    }
    private static String text(JsonNode body,String name){if(!body.path(name).isTextual())throw QueryProblem.invalid();return body.path(name).textValue();}
    private static Instant instant(JsonNode body,String name){try{String value=text(body,name);if(!value.endsWith("Z"))throw QueryProblem.invalid();return Instant.parse(value);}catch(java.time.DateTimeException e){throw QueryProblem.invalid();}}
}
