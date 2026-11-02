package io.campusflow.rooms;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http,RemoteServices remote,ObjectMapper mapper,@Value("${rooms.cookie-secure}") boolean secure) throws Exception {
        var csrf=CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(cookie->cookie.sameSite("Lax").secure(secure).path("/"));
        http.sessionManagement(AbstractHttpConfigurer::disable)
            .securityContext(c->c.securityContextRepository(new NullSecurityContextRepository()))
            .requestCache(c->c.requestCache(new NullRequestCache()))
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a->a.requestMatchers("/api/v1/room-applications/health").permitAll().anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->ApiErrors.write(req,res,mapper,401,"UNAUTHENTICATED","请先登录"))
                .accessDeniedHandler((req,res,ex)->ApiErrors.write(req,res,mapper,403,"FORBIDDEN","没有权限，或安全令牌已失效，请刷新后重试")))
            .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                    String id=req.getHeader("X-Request-Id");if(id==null||!id.matches("[A-Za-z0-9-]{1,64}"))id=UUID.randomUUID().toString();
                    req.setAttribute("requestId",id);res.setHeader("X-Request-Id",id);res.setHeader("Cache-Control","no-store");
                    if(!req.getRequestURI().equals("/api/v1/room-applications/health")) {
                        String token=req.getCookies()==null?null:Arrays.stream(req.getCookies()).filter(c->c.getName().equals("CF_SESSION")).map(Cookie::getValue).findFirst().orElse(null);
                        if(token==null||token.length()>128) {ApiErrors.write(req,res,mapper,401,"UNAUTHENTICATED","请先登录");return;}
                        try {
                            var user=remote.authenticate(token);
                            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
                        } catch(ApiException ex) {ApiErrors.write(req,res,mapper,ex.status.value(),ex.code,ex.getMessage());return;}
                    }
                    chain.doFilter(req,res);
                }
            },CsrfFilter.class);
        return http.build();
    }
    static Models.Identity user() { return (Models.Identity)SecurityContextHolder.getContext().getAuthentication().getPrincipal(); }
}
