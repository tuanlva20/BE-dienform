package com.dienform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.Transport;

@org.springframework.context.annotation.Configuration
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


