package io.campusflow.seats.http;

import java.net.URI;
import java.time.*;
import java.util.Set;

/** Only the test-classpath fixture installs this restricted legacy demonstration mode. */
public final class DemoWritePermit extends ReservationWriteAccess {
    DemoWritePermit(String bind,String origin,byte[] secret,byte[] serviceKey,Clock clock,Duration ttl) {
        super(origin,secret,serviceKey,clock,ttl,Mode.PERSONAL_DEMO);
        if(!Set.of("127.0.0.1","::1").contains(bind)||!Set.of("127.0.0.1","localhost","[::1]","::1").contains(URI.create(origin).getHost()))
            throw new IllegalArgumentException("隔离演示保护配置无效");
    }
}
