package io.campusflow.maintenance;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
@Component
class DAuditTransport implements AuditTransport {
 private final String url,key;private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
 DAuditTransport(@Value("${maintenance.d-url}") String url,@Value("${maintenance.service-key}") String key){this.url=url.replaceAll("/+$","");this.key=key;}
 public void send(String payload){try{var res=client.send(HttpRequest.newBuilder(URI.create(url+"/internal/v1/audit")).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.discarding());if(res.statusCode()!=200)throw new Failure(503,"AUDIT_REJECTED");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure(503,"AUDIT_INTERRUPTED");}catch(Failure e){throw e;}catch(Exception e){throw new Failure(503,"AUDIT_UNAVAILABLE");}}
}
