package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
@Validated
public class InternalController {
    private final Access access; private final IdentityService identities; private final ResourceService resources; private final AuditService audit; private final NotificationService notifications;
    InternalController(Access access,IdentityService identities,ResourceService resources,AuditService audit,NotificationService notifications) { this.access=access;this.identities=identities;this.resources=resources;this.audit=audit;this.notifications=notifications; }
    @PostMapping("/authorization") Map<String,Object> authorize(@Valid @RequestBody Authorization input) {
        access.source(); var user=identities.findSession(input.sessionToken());
        if(user.isEmpty()) return Map.of("allowed",false);
        var identity=user.get(); boolean allowed=identity.permissions().contains(input.permission());
        if(input.departmentId()!=null) allowed=allowed&&(identity.admin()||input.departmentId().equals(identity.departmentId()));
        return Map.of("allowed",allowed,"identity",identity);
    }
    @GetMapping("/resources") Page<Resource> resources(@RequestParam(required=false) String type,@RequestParam(required=false) UUID parentId,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="100") @Min(1) @Max(100) int size) { access.source();return resources.list(type,parentId,null,page,size); }
    @GetMapping("/resources/{id}") Resource resource(@PathVariable UUID id) { access.source();return resources.get(id); }
    @PostMapping("/notifications") @ResponseStatus(HttpStatus.ACCEPTED) Delivery notification(@Valid @RequestBody NotificationEvent input) { return notifications.receive(access.source(),input); }
    @GetMapping("/notifications/{id}") Delivery receipt(@PathVariable UUID id) { return notifications.get(id,access.source()); }
    @PostMapping("/audit") Map<String,UUID> audit(@Valid @RequestBody AuditEvent input,HttpServletRequest req) { return Map.of("id",audit.receive(access.source(),input,ApiErrors.requestId(req))); }
}
