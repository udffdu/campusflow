package io.campusflow.rooms;

import static io.campusflow.rooms.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only network contracts cross the module boundary. Credentials never go to the browser. */
@Component
public class RemoteServices {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String platform,platformKey,occupancy,occupancyKey;
    RemoteServices(ObjectMapper json,@Value("${rooms.platform-url}") String platform,@Value("${rooms.platform-key}") String platformKey,
                   @Value("${rooms.occupancy-url}") String occupancy,@Value("${rooms.occupancy-key}") String occupancyKey) {
        this.json=json;this.platform=base(platform);this.platformKey=platformKey;
        this.occupancy=base(occupancy);this.occupancyKey=occupancyKey;
        for(String key:List.of(platformKey,occupancyKey)) if(!key.isEmpty()&&key.length()<32) throw new IllegalStateException("Service keys must contain at least 32 characters");
    }
    private String base(String url) {
        URI uri=URI.create(url);
        if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null) throw new IllegalStateException("Invalid service URL");
        return url.replaceAll("/+$","");
    }
    public Identity authenticate(String token) {
        var result=call(platform,platformKey,"POST","/internal/v1/authorization",Map.of("sessionToken",token,"permission","rooms.read"),Authorization.class,false);
        if(result==null||result.identity()==null||!result.identity().active()) throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","请先登录");
        if(!result.allowed()) throw ApiException.denied();
        return result.identity();
    }
    public Resource resource(UUID id) { return call(platform,platformKey,"GET","/internal/v1/resources/"+id,null,Resource.class,false); }
    public Occupancy getOccupancy(UUID id) { return call(occupancy,occupancyKey,"GET","/internal/v1/room-occupancies/"+id,null,Occupancy.class,true); }
    public Occupancy putOccupancy(UUID id,OccupancyWrite input) { return call(occupancy,occupancyKey,"PUT","/internal/v1/room-occupancies/"+id,input,Occupancy.class,false); }
    public void event(String kind,String payload) { call(platform,platformKey,"POST","/internal/v1/"+(kind.equals("AUDIT")?"audit":"notifications"),payload,Map.class,false); }
    private <T> T call(String base,String key,String method,String path,Object body,Class<T> type,boolean allowMissing) {
        if(key.isBlank()) throw ApiException.unavailable("INTEGRATION_NOT_CONFIGURED");
        try {
            var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+key).header("Accept","application/json");
            var publisher=body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body instanceof String s?s:json.writeValueAsString(body));
            if(body!=null)builder.header("Content-Type","application/json");
            var response=client.send(builder.method(method,publisher).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==404&&allowMissing)return null;
            if(response.statusCode()==404&&type==Resource.class)throw ApiException.bad("RESOURCE_NOT_FOUND","教室不存在");
            // A bare 409 or server error is not proof of a durable M1 conflict; reconcile by GET later.
            if(response.statusCode()<200||response.statusCode()>=300)throw ApiException.unavailable("DEPENDENCY_HTTP_"+response.statusCode());
            T result=json.readValue(response.body(),type);
            if(result==null)throw ApiException.unavailable("INVALID_DEPENDENCY_RESPONSE");
            return result;
        } catch(ApiException ex) {throw ex;}
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw ApiException.unavailable("DEPENDENCY_INTERRUPTED");}
        catch(Exception ex) {throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE");}
    }
}
