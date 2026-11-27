package com.cricket.platform.shared;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;
@Component("brokerRelay")
public class BrokerRelayHealthIndicator implements HealthIndicator, ApplicationListener<BrokerAvailabilityEvent> {
 private final boolean enabled; private final AtomicBoolean available;
 public BrokerRelayHealthIndicator(@Value("${app.websocket.broker-relay.enabled:false}") boolean enabled) { this.enabled=enabled; this.available=new AtomicBoolean(!enabled); }
 @Override public void onApplicationEvent(BrokerAvailabilityEvent event) { if(enabled) available.set(event.isBrokerAvailable()); }
 @Override public Health health() {
  if(!enabled) return Health.up().withDetail("mode","simple-broker").build();
  if(available.get()) return Health.up().withDetail("mode","stomp-relay").build();
  return Health.down().withDetail("mode","stomp-relay").withDetail("reason","STOMP broker system connection is unavailable").build();
 }
}
