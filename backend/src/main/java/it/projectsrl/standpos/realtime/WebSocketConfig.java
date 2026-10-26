package it.projectsrl.standpos.realtime;

import it.projectsrl.standpos.auth.CurrentDevice;
import it.projectsrl.standpos.tenant.TenantChannelInterceptor;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * STOMP over plain WebSocket at {@code /ws} (docs/adr/0009-stomp-security.md).
 *
 * <ul>
 *   <li>The HTTP handshake passes through the security filter chain: a browser session (manager or cashier) or a
 *       device token (cookie or bearer) is required, and the WebSocket session takes that principal. Browser
 *       handshakes must come from the same origin.
 *   <li>Inbound frames are authorised by {@link StompAuthorization}; message handling runs in the caller's tenant
 *       ({@link TenantChannelInterceptor}). Frames of one session are processed in order.
 *   <li>Topics: {@code /topic/venues/{venueId}/stock|printers|print-jobs}; the agent's own queue
 *       {@code /user/queue/print-jobs}; agent messages to {@code /app/agent/heartbeat|ack}.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(WebSocketConfig.Properties.class)
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final String DEVICE_ATTRIBUTE = CurrentDevice.class.getName();

    /** Extra allowed browser origins, e.g. the Vite dev server; empty means same origin only. */
    @ConfigurationProperties("standpos.websocket")
    record Properties(List<String> allowedOrigins) {

        Properties {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }

    private final StompAuthorization authorization;
    private final TenantChannelInterceptor tenantInterceptor;
    private final WebSocketSessions sessions;
    private final Properties properties;
    private final TaskScheduler taskScheduler;

    WebSocketConfig(StompAuthorization authorization, TenantChannelInterceptor tenantInterceptor,
            WebSocketSessions sessions, Properties properties, @Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this.authorization = authorization;
        this.tenantInterceptor = tenantInterceptor;
        this.sessions = sessions;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        var endpoint = registry.addEndpoint("/ws").addInterceptors(new DeviceHandshakeInterceptor());
        if (!properties.allowedOrigins().isEmpty()) {
            endpoint.setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
        }
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(taskScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authorization, tenantInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessions::decorate);
    }

    /** Copies the device resolved by the HTTP filter chain into the WebSocket session (kind, venue, id). */
    static final class DeviceHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest servlet) {
                CurrentDevice.of(servlet.getServletRequest()).ifPresent(d -> attributes.put(DEVICE_ATTRIBUTE, d));
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                Exception exception) {}
    }
}
