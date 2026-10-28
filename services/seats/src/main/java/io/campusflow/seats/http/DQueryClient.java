package io.campusflow.seats.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.campusflow.seats.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Own wire mapping, not D models or tables. No redirects, retries or authorization cache. */
@Component
public final class DQueryClient {
    static final String PERMISSION = "seats.availability.read";
    private final URI base;
    private final String key;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration readTimeout, budget;
    private final int maxPages;

    public DQueryClient(ObjectMapper json, @Value("${m1.d-base-url}") URI base,
            @Value("${m1.service-key}") String key, @Value("${m1.connect-timeout}") Duration connect,
            @Value("${m1.read-timeout}") Duration read, @Value("${m1.call-budget}") Duration budget,
            @Value("${m1.max-pages}") int maxPages) {
        if (!Set.of("http","https").contains(base.getScheme()) || base.getHost()==null
                || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")) || key.length()<32
                || key.chars().anyMatch(c -> c<33 || c>126) || connect.isNegative() || connect.isZero()
                || read.isNegative() || read.isZero() || budget.isNegative() || budget.isZero()
                || maxPages<1 || maxPages>1000) throw new IllegalArgumentException("M1 D连接配置无效");
        this.base=base; this.key=key; this.json=json; this.readTimeout=read; this.budget=budget; this.maxPages=maxPages;
        this.http=HttpClient.newBuilder().connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @jakarta.annotation.PreDestroy void shutdown() { http.shutdownNow(); }
    public Call call() { return new Call(System.nanoTime()+budget.toNanos()); }

    public final class Call {
        private final long deadline;
        private Call(long deadline) { this.deadline=deadline; }

        public ReservationAccessPolicy.IdentitySnapshot identity(String session) {
            if (session==null || !session.matches("[A-Za-z0-9_-]{43}"))
                throw new QueryProblem(401,"SESSION_REQUIRED","请重新登录");
            try {
                JsonNode auth=request("/internal/v1/authorization", json.writeValueAsBytes(
                        Map.of("sessionToken",session,"permission",PERMISSION)),false);
                boolean allowed=bool(auth,"allowed");
                JsonNode identity=auth.get("identity");
                if (identity==null || identity.isNull()) {
                    if (allowed) throw QueryProblem.upstream();
                    throw new QueryProblem(401,"SESSION_REQUIRED","请重新登录");
                }
                UUID user=id(identity,"id");
                if (!bool(identity,"active")) throw QueryProblem.upstream();
                Set<String> permissions=strings(identity,"permissions");
                if (!allowed) throw new QueryProblem(403,"FORBIDDEN","缺少座位查询权限");
                if (!permissions.contains(PERMISSION)) throw QueryProblem.upstream();
                if (identity.path("departmentId").isNull())
                    throw new QueryProblem(403,"FORBIDDEN","座位查询需要有效的所属院系");
                return new ReservationAccessPolicy.IdentitySnapshot(user,id(identity,"departmentId"),permissions);
            } catch (QueryProblem e) { throw e; }
            catch (Exception e) { throw QueryProblem.upstream(); }
        }
        public Resource room(UUID id) {
            JsonNode raw=request("/internal/v1/resources/"+id,null,true);
            if (!id(raw,"id").equals(id)) throw QueryProblem.upstream();
            if (!text(raw,"type").equals("ROOM")) throw new QueryProblem(404,"ROOM_NOT_FOUND","教室不存在");
            Resource room=resource(raw);
            if (!room.id().equals(id)) throw QueryProblem.upstream();
            if (!room.type().equals("ROOM")) throw new QueryProblem(404,"ROOM_NOT_FOUND","教室不存在");
            return room;
        }
        public List<Resource> resources(String type, UUID parent) {
            List<Resource> result=new ArrayList<>(); Set<UUID> seen=new HashSet<>(); long total=-1;
            for (int page=0;page<maxPages;page++) {
                JsonNode response=request("/internal/v1/resources?type="+type
                        +(parent==null?"":"&parentId="+parent)+"&page="+page+"&size=100",null,false);
                long count=number(response,"total");
                if (number(response,"page")!=page || number(response,"size")!=100 || count<0
                        || count>(long)maxPages*100 || (total!=-1 && count!=total)) throw QueryProblem.upstream();
                total=count;
                JsonNode items=array(response,"items");
                long expected=Math.min(100,Math.max(0,total-(long)page*100));
                if (items.size()!=expected) throw QueryProblem.upstream();
                for (JsonNode item:items) {
                    Resource r=resource(item);
                    if (!type.equals(r.type()) || (parent!=null&&!parent.equals(r.parentId())) || !seen.add(r.id()))
                        throw QueryProblem.upstream();
                    result.add(r);
                }
                if (result.size()==total) return List.copyOf(result);
            }
            throw QueryProblem.upstream();
        }
        private JsonNode request(String path, byte[] body, boolean missingRoom) {
            long remaining=deadline-System.nanoTime();
            if (remaining<=0) throw QueryProblem.upstream();
            Duration timeout=Duration.ofNanos(Math.min(remaining,readTimeout.toNanos()));
            var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                    .header("Authorization","Bearer "+key).header("Accept","application/json");
            if (body==null) builder.GET();
            else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            CompletableFuture<HttpResponse<byte[]>> future=null;
            try {
                future=http.sendAsync(builder.build(), info -> new BoundedBody());
                var response=future.get(timeout.toNanos(),TimeUnit.NANOSECONDS);
                if (response.statusCode()==404 && missingRoom) throw new QueryProblem(404,"ROOM_NOT_FOUND","教室不存在");
                // D HTTP 401/403 concern the service credential, never the browser session.
                if (response.statusCode()!=200) throw QueryProblem.upstream();
                JsonNode value=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
                if (value==null || !value.isObject()) throw QueryProblem.upstream();
                return value;
            } catch (QueryProblem e) { throw e; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw QueryProblem.upstream(); }
            catch (Exception e) { throw QueryProblem.upstream(); }
            finally { if (future!=null && !future.isDone()) future.cancel(true); }
        }
    }
    /** Reject malformed or absent security-relevant fields; tolerate future extra fields. */
    private static Resource resource(JsonNode value) {
        try {
            UUID id=id(value,"id"), parent=id(value,"parentId"), dept=id(value,"departmentId");
            String type=text(value,"type"), code=text(value,"code"), name=text(value,"name");
            if (!Set.of("ROOM","SEAT","BUILDING","CAMPUS").contains(type)) throw QueryProblem.upstream();
            boolean effective=bool(value,"effectiveActive"); bool(value,"active");
            Set<String> features=strings(value,"features");
            List<OpeningSchedule.Interval> hours=new ArrayList<>();
            for (JsonNode h:array(value,"openingHours")) hours.add(new OpeningSchedule.Interval(
                    DayOfWeek.of(Math.toIntExact(number(h,"day"))),LocalTime.parse(text(h,"start")),LocalTime.parse(text(h,"end"))));
            var schedule=new OpeningSchedule(hours);
            var scope=type.equals("ROOM")?ReservationAccessPolicy.BookingScope.valueOf(text(value,"bookingScope")):null;
            return new Resource(id,type,parent,dept,code,name,effective,scope,schedule,value.get("openingHours").deepCopy(),features);
        } catch (Exception e) { throw QueryProblem.upstream(); }
    }
    public record Resource(UUID id,String type,UUID parentId,UUID departmentId,String code,String name,
            boolean effectiveActive,ReservationAccessPolicy.BookingScope scope,OpeningSchedule schedule,
            JsonNode hours,Set<String> features) {
        SeatAvailabilityQuery.RoomSnapshot room() {
            return new SeatAvailabilityQuery.RoomSnapshot(id,departmentId,effectiveActive,schedule,scope);
        }
        SeatAvailabilityQuery.SeatSnapshot seat() {
            return new SeatAvailabilityQuery.SeatSnapshot(id,parentId,departmentId,code,effectiveActive,features);
        }
    }
    private static String text(JsonNode n,String field) {
        JsonNode v=n.get(field); if(v==null||!v.isTextual()||v.textValue().isBlank()) throw QueryProblem.upstream(); return v.textValue();
    }
    private static UUID id(JsonNode n,String field) {
        try { String v=text(n,field); UUID id=UUID.fromString(v); if (!id.toString().equalsIgnoreCase(v)) throw QueryProblem.upstream(); return id; }
        catch (IllegalArgumentException e) { throw QueryProblem.upstream(); }
    }
    private static boolean bool(JsonNode n,String field) {
        JsonNode v=n.get(field); if(v==null||!v.isBoolean()) throw QueryProblem.upstream(); return v.booleanValue();
    }
    private static long number(JsonNode n,String field) {
        JsonNode v=n.get(field); if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()) throw QueryProblem.upstream(); return v.longValue();
    }
    private static JsonNode array(JsonNode n,String field) {
        JsonNode v=n.get(field); if(v==null||!v.isArray()) throw QueryProblem.upstream(); return v;
    }
    private static Set<String> strings(JsonNode n,String field) {
        Set<String> values=new HashSet<>();
        for(JsonNode v:array(n,field)) {
            if(!v.isTextual()||v.textValue().isBlank()||!values.add(v.textValue())) throw QueryProblem.upstream();
        }
        return Set.copyOf(values);
    }
    /** Bounds bytes while receiving, including chunked responses. Timeout also covers body completion. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int bytes;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription s) { subscription=s; delegate.onSubscribe(s); }
        public void onNext(List<ByteBuffer> buffers) {
            for(var b:buffers) { bytes+=b.remaining(); if(bytes>1_048_576) {
                subscription.cancel(); delegate.onError(new IllegalStateException("D response exceeds limit")); return;
            }}
            delegate.onNext(buffers);
        }
        public void onError(Throwable t) { delegate.onError(t); }
        public void onComplete() { delegate.onComplete(); }
    }
}
