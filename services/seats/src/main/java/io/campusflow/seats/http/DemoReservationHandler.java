package io.campusflow.seats.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.campusflow.seats.ReservationStore;
import org.springframework.web.servlet.function.*;

/** Backward-compatible test-fixture assembly, sharing the ordinary booking implementation. */
public final class DemoReservationHandler {
    private final ReservationHandler delegate;
    public DemoReservationHandler(DQueryClient d,ReservationStore store,DemoWritePermit permit,ObjectMapper json){delegate=new ReservationHandler(d,store,permit,json);}
    public RouterFunction<ServerResponse> routes(){return delegate.routes();}
}
