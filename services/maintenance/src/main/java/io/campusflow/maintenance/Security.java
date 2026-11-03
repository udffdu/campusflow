package io.campusflow.maintenance;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
@Configuration
class Security {
    @Bean SecurityFilterChain chain(HttpSecurity http,@Value("${maintenance.cookie-secure}") boolean secure) throws Exception {
        var csrf=CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieName("M3-XSRF-TOKEN");csrf.setHeaderName("X-M3-XSRF-TOKEN");
        csrf.setCookieCustomizer(c->c.path("/api/v1/maintenance").sameSite("Strict").secure(secure));
        http.sessionManagement(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a->a.anyRequest().permitAll())
            .exceptionHandling(e->e.accessDeniedHandler((req,res,ex)->{res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"code\":\"CSRF_INVALID\",\"message\":\"CSRF_INVALID\",\"requestId\":\""+java.util.UUID.randomUUID()+"\"}");}));
        return http.build();
    }
}
