package io.campusflow.seats.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.campusflow.seats.*;
import java.util.*;
import org.springframework.web.servlet.function.*;

/** Protected CF-M1-007 browser boundary. No code, secret or session value is logged. */
public final class CheckInHandler {
    private final DQueryClient d;
    private final ReservationStore store;
    private final ReservationWriteAccess access;
    private final DailyCheckInCode codes;
    private final ObjectMapper json;

    CheckInHandler(DQueryClient d, ReservationStore store, ReservationWriteAccess access,
                   DailyCheckInCode codes, ObjectMapper json) {
        this.d = d; this.store = store; this.access = access; this.codes = codes; this.json = json;
    }

    RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
                .POST("/api/m1/v1/reservations/{id}/check-in", this::safeCheckIn)
                .GET("/api/m1/v1/reservations/{id}/usage", this::safeUsage)
                .POST("/api/m1/v1/reservations/{id}/leave-seat", this::safeLeaveSeat)
                .GET("/api/m1/v1/rooms/{id}/check-in-code", this::safeDisplay)
                .build();
    }

    private ServerResponse safeCheckIn(ServerRequest request) {
        try { return checkIn(request); }
        catch (Exception exception) { return error(exception, request); }
    }

    private ServerResponse safeDisplay(ServerRequest request) {
        try { return display(request); }
        catch (Exception exception) { return error(exception, request); }
    }

    private ServerResponse safeUsage(ServerRequest request) {
        try { return usage(request); }
        catch (Exception exception) { return error(exception, request); }
    }

    private ServerResponse safeLeaveSeat(ServerRequest request) {
        try { return leaveSeat(request); }
        catch (Exception exception) { return error(exception, request); }
    }

    private ServerResponse checkIn(ServerRequest request) throws Exception {
        var servlet = request.servletRequest();
        ReservationReadController.noParams(servlet);
        UUID reservationId = ReservationReadController.uuid(request.pathVariable("id"));
        String session = QueryController.session(servlet);
        access.verify(servlet, session);
        JsonNode body = body(servlet);
        if (body.size() != 1 || !body.has("code") || !body.path("code").isTextual()) {
            throw QueryProblem.invalid();
        }
        String code = body.path("code").textValue();
        var call = d.call();
        var identity = call.identity(session, ReservationAccessPolicy.Action.CHECK_IN);
        var result = store.checkIn(identity.userId(), reservationId, code, codes, () -> {
            var latest = call.identity(session, ReservationAccessPolicy.Action.CHECK_IN);
            if (!latest.userId().equals(identity.userId())) throw QueryProblem.upstream();
        });
        var response = new LinkedHashMap<>(usageWire(store.usageForUser(identity.userId(), reservationId), null));
        response.put("idempotent", result.idempotent());
        return ServerResponse.ok().body(response);
    }

    private ServerResponse usage(ServerRequest request) {
        var servlet = request.servletRequest();
        ReservationReadController.noParams(servlet);
        UUID reservationId = ReservationReadController.uuid(request.pathVariable("id"));
        var identity = d.call().identity(QueryController.session(servlet), ReservationAccessPolicy.Action.READ_OWN);
        return ServerResponse.ok().body(usageWire(store.usageForUser(identity.userId(), reservationId), null));
    }

    private ServerResponse leaveSeat(ServerRequest request) {
        var servlet = request.servletRequest();
        ReservationReadController.noParams(servlet);
        UUID reservationId = ReservationReadController.uuid(request.pathVariable("id"));
        String session = QueryController.session(servlet);
        access.verify(servlet, session);
        var call = d.call();
        var identity = call.identity(session, ReservationAccessPolicy.Action.LEAVE_SEAT);
        var result = store.leaveSeat(identity.userId(), reservationId, () -> {
            var latest = call.identity(session, ReservationAccessPolicy.Action.LEAVE_SEAT);
            if (!latest.userId().equals(identity.userId())) throw QueryProblem.upstream();
        });
        return ServerResponse.ok().body(usageWire(store.usageForUser(identity.userId(), reservationId), result.idempotent()));
    }

    private Map<String,Object> usageWire(ReservationStore.UsageSession usage, Boolean idempotent) {
        var response = new LinkedHashMap<String,Object>(ReservationReadController.wire(usage.reservation(), access.stage()));
        response.put("checkedInAt", usage.checkedInAt());
        response.put("completedAt", usage.completedAt());
        response.put("serverTime", usage.serverTime());
        if (idempotent != null) response.put("idempotent", idempotent);
        return response;
    }

    private ServerResponse display(ServerRequest request) {
        var servlet = request.servletRequest();
        ReservationReadController.noParams(servlet);
        UUID roomId = ReservationReadController.uuid(request.pathVariable("id"));
        String session = QueryController.session(servlet);
        var call = d.call();
        var identity = call.identity(session, ReservationAccessPolicy.Action.DISPLAY_CHECK_IN_CODE);
        var room = call.room(roomId);
        if (!identity.departmentId().equals(room.departmentId())) {
            throw new QueryProblem(403, "FORBIDDEN", "只能展示本院系教室的签到码");
        }
        if (!room.effectiveActive()) {
            throw new QueryProblem(409, "RESOURCE_INACTIVE", "教室已停用，不能展示签到码");
        }
        var daily = codes.current(roomId);
        return ServerResponse.ok().body(Map.of("roomId", roomId, "roomCode", room.code(),
                "roomName", room.name(), "code", daily.code(), "validDate", daily.validDate(),
                "expiresAt", daily.expiresAt(), "timeZone", DailyCheckInCode.CAMPUS_ZONE.getId()));
    }

    private JsonNode body(jakarta.servlet.http.HttpServletRequest request) throws Exception {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?")) {
            throw new QueryProblem(415, "JSON_REQUIRED", "请使用JSON提交");
        }
        byte[] bytes = request.getInputStream().readNBytes(1025);
        if (bytes.length > 1024) throw new QueryProblem(413, "BODY_TOO_LARGE", "提交内容过长");
        try {
            JsonNode value = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if (value == null || !value.isObject()) throw QueryProblem.invalid();
            return value;
        } catch (QueryProblem problem) { throw problem; }
        catch (Exception exception) { throw QueryProblem.invalid(); }
    }

    private static ServerResponse error(Exception exception, ServerRequest request) {
        var result = QueryErrors.response(exception, request.servletRequest());
        return ServerResponse.status(result.getStatusCode()).body(result.getBody());
    }
}
