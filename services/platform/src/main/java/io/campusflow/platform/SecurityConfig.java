package io.campusflow.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    public record ServiceIdentity(String source) {}
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean CsrfTokenRepository csrfRepository(@Value("${platform.cookie-secure}") boolean secure) {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(secure).path("/"));
        return csrf;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, IdentityService identities, ObjectMapper mapper, Environment env,CsrfTokenRepository csrf) throws Exception {
        var configuredKeys=new HashSet<String>();
        for(String source:List.of("M1","M2","M3")) {
            String key=env.getProperty("platform.service-keys."+source,"");
            if(!key.isEmpty()&&(key.length()<32||!configuredKeys.add(key))) throw new IllegalStateException("Service keys must be distinct and at least 32 characters: "+source);
        }
        // D owns opaque database sessions. Do not run HttpSession authentication strategies on every request.
        http.sessionManagement(AbstractHttpConfigurer::disable)
            .securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
            .requestCache(c -> c.requestCache(new NullRequestCache()))
            .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()).ignoringRequestMatchers("/internal/v1/**"))
            .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET,"/api/v1/health","/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/login").permitAll()
                .requestMatchers("/internal/v1/**").hasRole("SERVICE").anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> ApiErrors.write(req,res,mapper,401,"UNAUTHENTICATED","请先登录"))
                .accessDeniedHandler((req,res,ex) -> ApiErrors.write(req,res,mapper,403,"FORBIDDEN","没有权限，或安全令牌已失效，请刷新后重试")))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .addFilterBefore(new IdentityFilter(identities,env,mapper),CsrfFilter.class);
        return http.build();
    }
    static class IdentityFilter extends OncePerRequestFilter {
        private final IdentityService identities; private final Environment env; private final ObjectMapper mapper;
        IdentityFilter(IdentityService identities,Environment env,ObjectMapper mapper) { this.identities=identities; this.env=env; this.mapper=mapper; }
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
            String id=req.getHeader("X-Request-Id");
            if(id==null || !id.matches("[A-Za-z0-9-]{1,64}")) id=UUID.randomUUID().toString();
            req.setAttribute("requestId",id); res.setHeader("X-Request-Id",id);
            res.setHeader("Cache-Control","no-store");
            if(req.getRequestURI().startsWith("/internal/v1/")) {
                String header=req.getHeader("Authorization"); String source=null;
                if(header!=null && header.startsWith("Bearer ")) {
                    byte[] supplied=header.substring(7).getBytes(StandardCharsets.UTF_8);
                    for(String candidate:List.of("M1","M2","M3")) {
                        String expected=env.getProperty("platform.service-keys."+candidate,"");
                        if(expected.length()>=32 && MessageDigest.isEqual(supplied,expected.getBytes(StandardCharsets.UTF_8))) source=candidate;
                    }
                }
                if(source==null) { ApiErrors.write(req,res,mapper,401,"SERVICE_AUTH_REQUIRED","服务凭据无效"); return; }
                var auth=new UsernamePasswordAuthenticationToken(new ServiceIdentity(source),null,List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                String token=cookie(req,"CF_SESSION");
                if(token!=null) identities.findSession(token).ifPresent(user -> SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user,null,user.permissions().stream().map(SimpleGrantedAuthority::new).toList())));
            }
            chain.doFilter(req,res);
        }
    }
    static String cookie(HttpServletRequest req,String name) {
        if(req.getCookies()==null) return null;
        return Arrays.stream(req.getCookies()).filter(c -> c.getName().equals(name)).map(Cookie::getValue).findFirst().orElse(null);
    }
}
