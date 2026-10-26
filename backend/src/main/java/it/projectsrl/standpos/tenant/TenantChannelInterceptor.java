package it.projectsrl.standpos.tenant;

import it.projectsrl.standpos.auth.StandposPrincipal;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Keeps the tenant context while a STOMP frame is handled: the WebSocket counterpart of {@link TenantContextFilter}.
 * It runs on the thread that handles the message, so {@code @MessageMapping} methods and the transactions they open
 * see the sender's tenant, and the context is cleared afterwards.
 */
@Component
public class TenantChannelInterceptor implements ExecutorChannelInterceptor {

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        StandposPrincipal.from(SimpMessageHeaderAccessor.getUser(message.getHeaders()))
                .ifPresent(p -> TenantContext.set(p.tenantId()));
        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
            Exception ex) {
        TenantContext.clear();
    }
}
