package io.campusflow.rooms;

import static io.campusflow.rooms.Models.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/room-applications")
@Validated
public class RoomsController {
    private final ApplicationService applications;
    private final JdbcTemplate db;
    RoomsController(ApplicationService applications,JdbcTemplate db) {this.applications=applications;this.db=db;}
    @GetMapping("/health") Map<String,String> health() {db.queryForObject("select 1",Integer.class);return Map.of("status","UP");}
    @GetMapping("/policy") Map<String,Object> policy() {return applications.policy();}
    @GetMapping Page<Application> list(@RequestParam(defaultValue="mine") String view,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return applications.list(SecurityConfig.user(),view,status,page,size);}
    @GetMapping("/{id}") Detail detail(@PathVariable UUID id) {return applications.detail(id,SecurityConfig.user());}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) Detail create(@Valid @RequestBody Create input) {return applications.create(input,SecurityConfig.user());}
    @PostMapping("/{id}/decisions") Detail decision(@PathVariable UUID id,@Valid @RequestBody Decision input) {return applications.decide(id,input,SecurityConfig.user());}
    @PostMapping("/{id}/cancel") Detail cancel(@PathVariable UUID id,@Valid @RequestBody Change input) {return applications.cancel(id,input,SecurityConfig.user());}
    @PostMapping("/{id}/retry") Detail retry(@PathVariable UUID id,@Valid @RequestBody Change input) {return applications.retry(id,input,SecurityConfig.user());}
}
