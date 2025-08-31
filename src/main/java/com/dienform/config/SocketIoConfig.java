package com.dienform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.Transport;
import com.corundumstudio.socketio.listener.DefaultExceptionListener;
import lombok.extern.slf4j.Slf4j;

@org.springframework.context.annotation.Configuration
@Slf4j
public class SocketIoConfig {

  @Value("${realtime.socket.port:9092}")
  private int socketPort;

  @Value("${app.frontend.url:*}")
  private String frontendUrl;

  @Bean(destroyMethod = "stop")
  public SocketIOServer socketIOServer() {
    com.corundumstudio.socketio.Configuration config =
        new com.corundumstudio.socketio.Configuration();
    config.setHostname("0.0.0.0");
    config.setPort(socketPort);
    config.setTransports(Transport.WEBSOCKET, Transport.POLLING);
    config.setPingInterval(25000);
    config.setPingTimeout(60000);

    // Add custom exception listener for better error handling
    config.setExceptionListener(new DefaultExceptionListener() {
      @Override
      public void onEventException(Exception e, java.util.List<Object> args,
          com.corundumstudio.socketio.SocketIOClient client) {
        log.error("Socket.IO event exception: {}", e.getMessage(), e);
        super.onEventException(e, args, client);
      }

      @Override
      public void onDisconnectException(Exception e,
          com.corundumstudio.socketio.SocketIOClient client) {
        log.error("Socket.IO disconnect exception: {}", e.getMessage(), e);
        super.onDisconnectException(e, client);
      }

      @Override
      public void onConnectException(Exception e,
          com.corundumstudio.socketio.SocketIOClient client) {
        log.error("Socket.IO connect exception: {}", e.getMessage(), e);
        super.onConnectException(e, client);
      }

      @Override
      public void onPingException(Exception e, com.corundumstudio.socketio.SocketIOClient client) {
        log.error("Socket.IO ping exception: {}", e.getMessage(), e);
        super.onPingException(e, client);
      }
    });

    try {
      // CORS
      config.setOrigin(frontendUrl);
    } catch (Throwable ignore) {
    }
    SocketIOServer server = new SocketIOServer(config);
    server.start();
    return server;
  }
}


