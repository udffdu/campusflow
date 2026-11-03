package io.campusflow.seats.http;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DemoWritePermitTest {
    private final String session="s".repeat(43),origin="http://127.0.0.1:5274";
    private DemoWritePermit permit(Instant now,byte fill){byte[] key=new byte[32];java.util.Arrays.fill(key,fill);return new DemoWritePermit("127.0.0.1",origin,key,new byte[]{1},Clock.fixed(now,ZoneOffset.UTC),Duration.ofMinutes(15));}
    @Test void validTokensAreRandomAndBoundToSession(){var p=permit(Instant.EPOCH,(byte)1);String a=p.issue(session),b=p.issue(session);assertNotEquals(a,b);p.verifyToken(a,session);assertThrows(QueryProblem.class,()->p.verifyToken(a,"x".repeat(43)));assertFalse(a.contains(session));}
    @Test void expiryBoundaryAndRotationInvalidateTokens(){var p=permit(Instant.EPOCH,(byte)1);String token=p.issue(session);assertThrows(QueryProblem.class,()->permit(Instant.ofEpochSecond(900),(byte)1).verifyToken(token,session));assertThrows(QueryProblem.class,()->permit(Instant.EPOCH,(byte)2).verifyToken(token,session));}
    @Test void tamperedPayloadAndMalformedInputFail(){var p=permit(Instant.EPOCH,(byte)1);String token=p.issue(session);assertThrows(QueryProblem.class,()->p.verifyToken("901"+token.substring(token.indexOf('.')),session));for(String invalid:new String[]{"", "0.a.b","x".repeat(200)})assertThrows(QueryProblem.class,()->p.verifyToken(invalid,session));}
    @Test void crossSiteMetadataFailsEvenWithToken(){var p=permit(Instant.EPOCH,(byte)1);var r=new MockHttpServletRequest();r.addHeader("Origin",origin);r.addHeader("X-M1-CSRF-Token",p.issue(session));r.addHeader("Sec-Fetch-Site","cross-site");assertThrows(QueryProblem.class,()->p.verify(r,session));}
    @Test void duplicateOriginAndMissingOriginFail(){var p=permit(Instant.EPOCH,(byte)1);var r=new MockHttpServletRequest();r.addHeader("X-M1-CSRF-Token",p.issue(session));assertThrows(QueryProblem.class,()->p.verify(r,session));r.addHeader("Origin",origin);r.addHeader("Origin",origin);assertThrows(QueryProblem.class,()->p.verify(r,session));}
    @Test void externalBindAndSharedServiceSecretCannotEnableDemo(){assertThrows(IllegalArgumentException.class,()->new DemoWritePermit("0.0.0.0",origin,new byte[32],new byte[]{1},Clock.systemUTC(),Duration.ofMinutes(15)));assertThrows(IllegalArgumentException.class,()->new DemoWritePermit("127.0.0.1",origin,new byte[32],new byte[32],Clock.systemUTC(),Duration.ofMinutes(15)));}
}
