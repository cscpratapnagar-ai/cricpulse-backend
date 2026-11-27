package com.cricket.platform.shared;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;
import static org.assertj.core.api.Assertions.assertThat;
class BrokerRelayHealthIndicatorTest {
 @Test void simpleBrokerModeDoesNotBlockReadiness() {
  var indicator=new BrokerRelayHealthIndicator(false);
  assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  assertThat(indicator.health().getDetails()).containsEntry("mode","simple-broker");
 }
 @Test void relayModeTracksBrokerAvailabilityAndRecovery() {
  var indicator=new BrokerRelayHealthIndicator(true);
  assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  indicator.onApplicationEvent(new BrokerAvailabilityEvent(true,this));
  assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  indicator.onApplicationEvent(new BrokerAvailabilityEvent(false,this));
  assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  indicator.onApplicationEvent(new BrokerAvailabilityEvent(true,this));
  assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
 }
}
