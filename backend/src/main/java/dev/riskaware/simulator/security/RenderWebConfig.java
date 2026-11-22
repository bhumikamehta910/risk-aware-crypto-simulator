package dev.riskaware.simulator.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@Profile("render")
class RenderWebConfig implements WebMvcConfigurer {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> publicEndpoint(
            @Value("${RENDER_EXTERNAL_HOSTNAME}") String hostname) {
        if (!hostname.matches("[A-Za-z0-9-]+(?:[.][A-Za-z0-9-]+)+")) {
            throw new IllegalArgumentException("RENDER_EXTERNAL_HOSTNAME must be a DNS hostname");
        }
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setScheme("https");
            connector.setSecure(true);
            connector.setProxyName(hostname);
            connector.setProxyPort(443);
        });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthLimit()).addPathPatterns("/api/auth/**");
    }

    // One free instance. Only admitted requests create entries, bounding the map
    // by the global cap. Use the transport peer without trusting client headers.
    static final class AuthLimit implements HandlerInterceptor {
        private final Map<String, Integer> attempts = new HashMap<>();
        private long window = System.nanoTime();
        private int total;

        @Override
        public synchronized boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (!"POST".equals(request.getMethod())) return true;
            long elapsed = System.nanoTime() - window;
            if (elapsed >= 60_000_000_000L) {
                attempts.clear();
                total = 0;
                window = System.nanoTime();
                elapsed = 0;
            }
            String peer = request.getRemoteAddr();
            int used = attempts.getOrDefault(peer, 0);
            if (used >= 10 || total >= 120) {
                response.setHeader("Retry-After", Long.toString(60 - elapsed / 1_000_000_000L));
                response.setStatus(429);
                return false;
            }
            attempts.put(peer, used + 1);
            total++;
            return true;
        }
    }
}
