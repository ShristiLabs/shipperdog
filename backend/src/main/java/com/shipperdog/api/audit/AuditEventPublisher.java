package com.shipperdog.api.audit;

/**
 * The audit module's public emission surface. Modules report security- and
 * business-relevant events through this interface; the current binding logs
 * them and republishes them as Spring application events. The audit
 * framework story (S1-06) adds durable persistence without changing any
 * caller.
 */
public interface AuditEventPublisher {

    void publish(AuditEvent event);
}
