package com.cricket.platform.shared;
import com.cricket.platform.identity.ActiveWebSocketSessions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.config.annotation.*;
import java.util.List;
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
 private final WebSocketSecurityInterceptor interceptor; private final ActiveWebSocketSessions activeSockets;
 private final List<String> origins; private final boolean relayEnabled; private final String host, clientLogin, clientPasscode, systemLogin, systemPasscode, virtualHost; private final int port;
 @Autowired
 public WebSocketConfig(WebSocketSecurityInterceptor interceptor, ActiveWebSocketSessions activeSockets,
  @Value("${app.cors.allowed-origin-patterns:http://localhost:4200,https://*.app.github.dev,https://*.cricketpulse.app}") String origins,
  @Value("${app.websocket.broker-relay.enabled:false}") boolean relayEnabled,
  @Value("${app.websocket.broker-relay.host:localhost}") String host,
  @Value("${app.websocket.broker-relay.port:61613}") int port,
  @Value("${app.websocket.broker-relay.client-login:cricpulse}") String clientLogin,
  @Value("${app.websocket.broker-relay.client-passcode:local-cricpulse-broker-password}") String clientPasscode,
  @Value("${app.websocket.broker-relay.system-login:cricpulse}") String systemLogin,
  @Value("${app.websocket.broker-relay.system-passcode:local-cricpulse-broker-password}") String systemPasscode,
  @Value("${app.websocket.broker-relay.virtual-host:/}") String virtualHost) {
  this.interceptor=interceptor; this.activeSockets=activeSockets; this.origins=List.of(origins.split(","));
  this.relayEnabled=relayEnabled; this.host=host; this.port=port; this.clientLogin=clientLogin; this.clientPasscode=clientPasscode;
  this.systemLogin=systemLogin; this.systemPasscode=systemPasscode; this.virtualHost=virtualHost;
 }
 @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
  if (relayEnabled) {
   registry.enableStompBrokerRelay("/topic").setRelayHost(host).setRelayPort(port)
    .setClientLogin(clientLogin).setClientPasscode(clientPasscode).setSystemLogin(systemLogin)
    .setSystemPasscode(systemPasscode).setVirtualHost(virtualHost)
    .setSystemHeartbeatSendInterval(10000).setSystemHeartbeatReceiveInterval(10000)
    .setTaskScheduler(webSocketTaskScheduler());
  } else {
   registry.enableSimpleBroker("/topic").setHeartbeatValue(new long[]{10000,20000}).setTaskScheduler(webSocketTaskScheduler());
  }
  registry.setApplicationDestinationPrefixes("/app");
 }
 @Bean ThreadPoolTaskScheduler webSocketTaskScheduler() {
  ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
  scheduler.setThreadNamePrefix("cricpulse-ws-heartbeat-"); scheduler.initialize(); return scheduler;
 }
 @Override public void configureClientInboundChannel(ChannelRegistration registration) { registration.interceptors(interceptor); }
 @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
  registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
   @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception { activeSockets.register(session); super.afterConnectionEstablished(session); }
   @Override public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception { activeSockets.unregister(session); super.afterConnectionClosed(session,status); }
  });
 }
 @Override public void registerStompEndpoints(StompEndpointRegistry registry) { registry.addEndpoint("/ws").setAllowedOriginPatterns(origins.toArray(String[]::new)); }
}
