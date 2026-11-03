package io.campusflow.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class DClient {
    public record Actor(UUID id, UUID departmentId, boolean admin) {}
    public record Room(UUID id, UUID departmentId, long version) {}
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper; private final String base,key;
    public DClient(ObjectMapper mapper,@Value("${maintenance.d-url}") String base,@Value("${maintenance.service-key}") String key) {
        this.mapper=mapper;this.base=base.replaceAll("/+$","");this.key=key;
        if(key.length()<32) throw new IllegalArgumentException("M3 service key must contain at least 32 characters");
    }
    private JsonNode call(String path,Object body) {
        try {
            var builder=HttpRequest.newBuilder(URI.create(base+"/internal/v1"+path)).timeout(Duration.ofSeconds(3))
                .header("Authorization","Bearer "+key).header("Accept","application/json");
            if(body!=null) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==404 && path.startsWith("/resources/")) throw new Failure(400,"INVALID_ROOM");
            if(response.statusCode()!=200) throw new Failure(503,"D_UNAVAILABLE");
            return mapper.readTree(response.body());
        } catch(Failure e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new Failure(503,"D_UNAVAILABLE"); }
        catch(Exception e) { throw new Failure(503,"D_UNAVAILABLE"); }
    }
    public Actor authorize(String token,String permission,UUID department) {
        if(token==null || token.isBlank() || token.length()>128) throw new Failure(401,"UNAUTHENTICATED");
        var body=new LinkedHashMap<String,Object>();body.put("sessionToken",token);body.put("permission",permission);
        if(department!=null) body.put("departmentId",department);
        var response=call("/authorization",body);
        if(!response.path("allowed").isBoolean() || !response.path("allowed").booleanValue())
            throw new Failure(response.hasNonNull("identity")?403:401,"ACCESS_DENIED");
        try {
            var identity=response.required("identity");
            if(!identity.path("active").isBoolean() || !identity.path("active").booleanValue()) throw new Failure(401,"UNAUTHENTICATED");
            UUID id=UUID.fromString(identity.required("id").asText());
            UUID dep=UUID.fromString(identity.required("departmentId").asText());
            boolean admin=false;
            if(!identity.path("roles").isArray()) throw new IllegalArgumentException();
            for(var role:identity.path("roles")) if(role.asText().equals("ADMIN")) admin=true;
            if(department!=null && !admin && !department.equals(dep)) throw new Failure(403,"ACCESS_DENIED");
            return new Actor(id,dep,admin);
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure(503,"D_INVALID_RESPONSE"); }
    }
    public Room room(UUID id) {
        var node=call("/resources/"+id,null);
        if(!node.path("type").asText().equals("ROOM") || !node.path("effectiveActive").isBoolean() || !node.path("effectiveActive").booleanValue()) throw new Failure(400,"INVALID_ROOM");
        try {
            if(!id.equals(UUID.fromString(node.required("id").asText())) || !node.path("version").isIntegralNumber() || node.path("version").asLong()<1) throw new IllegalArgumentException();
            return new Room(id,UUID.fromString(node.required("departmentId").asText()),node.path("version").asLong());
        } catch(Exception e) { throw new Failure(503,"D_INVALID_RESPONSE"); }
    }
}
