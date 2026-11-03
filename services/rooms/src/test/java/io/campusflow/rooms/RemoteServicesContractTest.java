package io.campusflow.rooms;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteServicesContractTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private HttpServer upstream;
    private RemoteServices remote;
    private ObjectNode response;

    @BeforeEach void start()throws Exception{
        // Wire shape of D Identity after M1 adds email; no shared entity or global mapper relaxation.
        response=(ObjectNode)json.readTree("""
                {"allowed":true,"identity":{
                  "id":"20000000-0000-0000-0000-000000000001","username":"contract-user",
                  "displayName":"Contract fixture","email":"fixture@example.invalid",
                  "departmentId":"10000000-0000-0000-0000-000000000001","active":true,
                  "roles":["ROOM_APPLICANT"],"permissions":["rooms.read","rooms.apply"]}}
                """);
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/internal/v1/authorization",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] body=json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        upstream.start();String url="http://127.0.0.1:"+upstream.getAddress().getPort();
        remote=new RemoteServices(json,url,UUID.randomUUID().toString(),url,UUID.randomUUID().toString());
    }
    @AfterEach void stop(){if(upstream!=null)upstream.stop(0);}
    private ObjectNode identity(){return (ObjectNode)response.get("identity");}

    @Test void newProfileFieldsDoNotBreakLiveAuthorization(){
        identity().put("futureProfileField","additive");
        var user=remote.authenticate("test-session");
        assertThat(user.id()).isEqualTo(UUID.fromString("20000000-0000-0000-0000-000000000001"));
        assertThat(user.can("rooms.apply")).isTrue();assertThat(user.admin()).isFalse();
        assertThat(user.scope(UUID.fromString("10000000-0000-0000-0000-000000000002"))).isFalse();
    }
    @Test void nullEmailAndOlderIdentityRemainCompatible(){
        identity().putNull("email");assertThat(remote.authenticate("test-session").can("rooms.apply")).isTrue();
        identity().remove("email");assertThat(remote.authenticate("test-session").can("rooms.apply")).isTrue();
    }
    @Test void additionalProfileFieldsCannotOverrideDenialOrInactiveUser(){
        response.put("allowed",false);assertThatThrownBy(()->remote.authenticate("test-session")).isInstanceOf(ApiException.class);
        response.put("allowed",true);identity().put("active",false);
        assertThatThrownBy(()->remote.authenticate("test-session")).isInstanceOf(ApiException.class);
    }
    @ParameterizedTest @ValueSource(strings={"id","departmentId","roles","permissions"})
    void missingSecurityFieldsFailClosed(String field){
        identity().remove(field);
        assertThatThrownBy(()->remote.authenticate("test-session")).isInstanceOf(ApiException.class);
    }
    @Test void malformedIdentityAndUntrustedRequestFieldsRemainRejected(){
        identity().put("id","invalid-uuid");
        assertThatThrownBy(()->remote.authenticate("test-session")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->json.readValue("{\"forgedPermission\":\"rooms.approve\"}",Models.Create.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }
}
