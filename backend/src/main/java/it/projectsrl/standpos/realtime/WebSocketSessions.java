package it.projectsrl.standpos.realtime;

import it.projectsrl.standpos.auth.StandposPrincipal;
import it.projectsrl.standpos.device.DeviceEvents;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Open WebSocket sessions, so that revoking a device closes its connections at once instead of when the device next
 * sends a frame.
 */
@Component
class WebSocketSessions {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessions.class);

    private final Map<String, WebSocketSession> open = new ConcurrentHashMap<>();

    WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                open.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                open.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    @TransactionalEventListener
    void onDeviceRevoked(DeviceEvents.Revoked event) {
        close(event.deviceId());
    }

    void close(UUID deviceId) {
        open.values().stream()
                .filter(s -> StandposPrincipal.from(s.getPrincipal())
                        .map(p -> deviceId.equals(p.deviceId()))
                        .orElse(false))
                .forEach(s -> {
                    try {
                        s.close(CloseStatus.POLICY_VIOLATION.withReason("device revoked"));
                    } catch (IOException e) {
                        log.debug("Closing session {} failed", s.getId(), e);
                    }
                });
    }
}
