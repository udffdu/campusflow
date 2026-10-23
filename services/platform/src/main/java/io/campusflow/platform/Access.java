package io.campusflow.platform;

import static io.campusflow.platform.Models.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class Access {
    public Identity user() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !(auth.getPrincipal() instanceof Identity user)) throw ApiException.denied();
        return user;
    }
    public Identity require(String permission) {
        var user=user(); if(!user.permissions().contains(permission)) throw ApiException.denied(); return user;
    }
    public void scope(Identity user,UUID department) { if(!user.admin() && (department==null || !department.equals(user.departmentId()))) throw ApiException.denied(); }
    public String source() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !(auth.getPrincipal() instanceof SecurityConfig.ServiceIdentity service)) throw ApiException.denied();
        return service.source();
    }
}
