package io.campusflow.seats.http;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class ReservationWriteAccessTest {
    @Test void ordinaryHttpsOriginIsExactAndTokenRemainsSessionBound(){
        var p=new ReservationWriteAccess("https://campus.example",new byte[32],new byte[]{1},Clock.systemUTC(),Duration.ofMinutes(15),ReservationWriteAccess.Mode.PERSONAL);
        String session="s".repeat(43),token=p.issue(session);var r=new MockHttpServletRequest();r.addHeader("Origin","https://campus.example");r.addHeader("X-M1-CSRF-Token",token);r.addHeader("Sec-Fetch-Site","same-origin");p.verify(r,session);
        assertThrows(QueryProblem.class,()->p.verify(r,"x".repeat(43)));r.removeHeader("Origin");r.addHeader("Origin","https://campus.example.evil");assertThrows(QueryProblem.class,()->p.verify(r,session));
    }
    @Test void invalidOrdinaryOriginsAndWeakOrSharedSecretsAreRejected(){
        for(String origin:new String[]{"http://campus.example","https://*.example","https://campus.example/","https://user@campus.example","https://campus.example?x=1","null","https://campus.example:0"})
            assertThrows(IllegalArgumentException.class,()->new ReservationWriteAccess(origin,new byte[32],new byte[]{1},Clock.systemUTC(),Duration.ofMinutes(15),ReservationWriteAccess.Mode.PERSONAL));
        assertThrows(IllegalArgumentException.class,()->new ReservationWriteAccess("https://campus.example",new byte[12],new byte[]{1},Clock.systemUTC(),Duration.ofMinutes(15),ReservationWriteAccess.Mode.PERSONAL));
        String shared=Base64.getEncoder().encodeToString(new byte[32]);var env=new MockEnvironment().withProperty("m1.csrf-secret",shared).withProperty("m1.service-key",shared).withProperty("m1.browser-origin","https://campus.example");
        assertThrows(IllegalArgumentException.class,()->new ReservationWriteConfiguration().reservationWriteAccess(env,Clock.systemUTC()));
    }
}
