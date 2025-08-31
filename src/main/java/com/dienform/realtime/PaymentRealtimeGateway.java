package com.dienform.realtime;

import java.time.Instant;
import org.springframework.stereotype.Component;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.listener.DataListener;
import com.dienform.common.entity.User;
import com.dienform.common.repository.UserRepository;
import com.dienform.common.service.JwtTokenProvider;
import com.dienform.realtime.dto.BalanceRoomPayload;
import com.dienform.tool.dienformtudong.payment.dto.BalanceUpdateEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentRealtimeGateway {

  private final SocketIOServer server;
  private final JwtTokenProvider jwtTokenProvider;
  private final UserRepository userRepository;

  @PostConstruct
  public void init() {
    // Join user to their balance room on connect
    server.addConnectListener(client -> {
      try {
        String token = client.getHandshakeData().getSingleUrlParam("token");
        if (token == null || token.isBlank()) {
          String cookie = client.getHandshakeData().getHttpHeaders().get("Cookie");
          if (cookie != null) {
            // naive parse for access_token
            for (String p : cookie.split(";")) {
              String s = p.trim();
              if (s.startsWith("access_token=")) {
                token = s.substring("access_token=".length());
                break;
              }
            }
          }
        }

        if (token != null && !token.isBlank()) {
          String email = jwtTokenProvider.validateToken(token).getSubject();
          userRepository.findByEmail(email).map(User::getId).ifPresent(uid -> {
            client.set("userId", uid.toString());
            // Join user to their balance room
            String balanceRoomId = "balance:" + uid;
            client.joinRoom(balanceRoomId);
            log.debug("User {} joined balance room: {}", uid, balanceRoomId);
          });
        }
      } catch (Exception e) {
        log.debug("Socket auth failed: {}", e.getMessage());
      }
    });

    // Listen for balance room join requests
    server.addEventListener("join_balance_room", BalanceRoomPayload.class, onJoinBalanceRoom());
  }

  /**
   * Emit balance update to specific user
   */
  public void emitBalanceUpdate(String userId, java.math.BigDecimal newBalance) {
    String roomId = "balance:" + userId;
    BalanceUpdateEvent event = BalanceUpdateEvent.builder().userId(userId).balance(newBalance)
        .updatedAt(Instant.now().toString()).build();

    server.getRoomOperations(roomId).sendEvent("balance_update", event);
    log.info("Emitted balance update to user {}: {}", userId, newBalance);
  }

  /**
   * Ensure user is joined to their balance room
   */
  public void ensureUserJoinedBalanceRoom(String userId) {
    String roomId = "balance:" + userId;
    for (SocketIOClient c : server.getAllClients()) {
      try {
        String uid = c.get("userId");
        if (userId.equals(uid)) {
          c.joinRoom(roomId);
          log.debug("Ensured user {} joined balance room: {}", userId, roomId);
        }
      } catch (Exception ignore) {
      }
    }
  }

  /**
   * Leave balance room for user
   */
  public void leaveBalanceRoom(String userId) {
    String roomId = "balance:" + userId;
    try {
      for (SocketIOClient c : server.getAllClients()) {
        try {
          String uid = c.get("userId");
          if (userId.equals(uid)) {
            c.leaveRoom(roomId);
            log.debug("User {} left balance room: {}", userId, roomId);
          }
        } catch (Exception ignore) {
        }
      }
    } catch (Exception e) {
      log.debug("leaveBalanceRoom failed: {}", e.getMessage());
    }
  }

  private DataListener<BalanceRoomPayload> onJoinBalanceRoom() {
    return (client, data, ackSender) -> {
      try {
        String uid = client.get("userId");
        if (uid != null) {
          String roomId = "balance:" + uid;
          client.joinRoom(roomId);
          log.debug("User {} joined balance room via event: {}", uid, roomId);
        } else {
          log.warn("User not authenticated, cannot join balance room");
        }
      } catch (Exception e) {
        log.error("Error joining balance room: {}", e.getMessage(), e);
      }
    };
  }
}
