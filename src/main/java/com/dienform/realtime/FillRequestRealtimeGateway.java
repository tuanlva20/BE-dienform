package com.dienform.realtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.listener.DataListener;
import com.dienform.common.entity.User;
import com.dienform.common.repository.UserRepository;
import com.dienform.common.service.JwtTokenProvider;
import com.dienform.realtime.dto.FillRequestBulkStateEvent;
import com.dienform.realtime.dto.FillRequestUpdateEvent;
import com.dienform.realtime.dto.JoinLeavePayload;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class FillRequestRealtimeGateway {
  private static class Meter {
    int count;
    long windowStart;
  }

  private final SocketIOServer server;

  private final FillRequestRepository fillRequestRepository;
  private final FormRepository formRepository;
  private final JwtTokenProvider jwtTokenProvider;
  private final UserRepository userRepository;
  @Value("${realtime.socket.progress-emit-interval-ms:1000}")
  private long intervalMs;

  @Value("${realtime.socket.progress-emit-delta:1}")
  private int delta;
  @Value("${realtime.socket.burst-max-per-sec:10}")
  private int burstMax;

  private final Map<String, Meter> meters = new ConcurrentHashMap<>();
  private final Map<String, String> lastEmittedEvents = new ConcurrentHashMap<>();

  @PostConstruct
  public void init() {
    // Schedule cleanup of old events every 5 minutes
    scheduleEventCleanup();

    // Identify user on socket connect using token query or cookie
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
            log.debug("Socket client {} authenticated as user {}", client.getSessionId(), uid);
          });
        }
      } catch (Exception e) {
        log.debug("Socket auth failed: {}", e.getMessage());
      }
    });

    server.addEventListener("join_form_room", JoinLeavePayload.class, onJoin());
    server.addEventListener("leave_form_room", JoinLeavePayload.class, onLeave());
  }

  public void emitBulkState(String formId, FillRequestBulkStateEvent payload) {
    String roomId = "form:" + formId;
    // Send to room-specific listeners
    server.getRoomOperations(roomId).sendEvent("fill_request_bulk_state", payload);
    // Also broadcast globally so list pages not in a room can receive updates
    try {
      server.getBroadcastOperations().sendEvent("fill_request_bulk_state", payload);
      log.debug("Emitted bulk state to room {} and broadcast for form {} ({} requests)", roomId,
          formId, payload.getRequests() == null ? 0 : payload.getRequests().size());
    } catch (Exception ignore) {
    }
  }

  /**
   * Centralized method to emit fill request updates with deduplication
   */
  public void emitUpdate(String formId, FillRequestUpdateEvent payload) {
    String roomId = "form:" + formId;
    String eventKey = formId + ":" + payload.getRequestId() + ":" + payload.getStatus() + ":"
        + payload.getCompletedSurvey() + ":" + payload.getSurveyCount();

    // Check if this exact event was recently emitted
    String lastEvent = lastEmittedEvents.get(roomId);
    if (eventKey.equals(lastEvent)) {
      log.debug("Skipping duplicate event for room: {} - request={} status={}", roomId,
          payload.getRequestId(), payload.getStatus());
      return;
    }

    if (canEmit(roomId)) {
      // Store this event as last emitted
      lastEmittedEvents.put(roomId, eventKey);

      // Send to room-specific listeners
      server.getRoomOperations(roomId).sendEvent("fill_request_update", payload);
      // Also broadcast globally so clients that didn't join a room still get updates
      try {
        server.getBroadcastOperations().sendEvent("fill_request_update", payload);
        log.debug("Emitted update to room {} and broadcast: request={} status={} {}/{}", roomId,
            payload.getRequestId(), payload.getStatus(), payload.getCompletedSurvey(),
            payload.getSurveyCount());
      } catch (Exception ignore) {
      }
    }
  }

  /**
   * Centralized method to emit updates to both form room and user room with deduplication
   */
  public void emitUpdateWithUser(String formId, FillRequestUpdateEvent payload, String userId) {
    // Emit to form room (with deduplication)
    emitUpdate(formId, payload);

    // Emit to user room (with deduplication)
    if (userId != null && !userId.trim().isEmpty()) {
      emitUpdateForUser(userId, formId, payload);
    }
  }

  // --- User specific rooms: <userId>:<formId> ---
  public void ensureUserJoinedFormRoom(String userId, String formId) {
    String roomId = userId + ":" + formId;
    for (SocketIOClient c : server.getAllClients()) {
      try {
        String uid = c.get("userId");
        if (userId.equals(uid)) {
          c.joinRoom(roomId);
        }
      } catch (Exception ignore) {
      }
    }
  }

  public void emitBulkStateForUser(String userId, String formId) {
    ensureUserJoinedFormRoom(userId, formId);
    try {
      UUID formUuid = UUID.fromString(formId);
      var form = formRepository.findById(formUuid).orElse(null);
      if (form == null)
        return;
      List<FillRequest> requests = fillRequestRepository.findByForm(form);
      List<FillRequestUpdateEvent> reqs = requests.stream()
          .map(fr -> FillRequestUpdateEvent.builder().formId(formId)
              .requestId(fr.getId().toString()).status(fr.getStatus().name())
              .completedSurvey(fr.getCompletedSurvey()).surveyCount(fr.getSurveyCount())
              .updatedAt(java.time.Instant.now().toString()).build())
          .collect(Collectors.toList());
      FillRequestBulkStateEvent payload = FillRequestBulkStateEvent.builder().formId(formId)
          .requests(reqs).updatedAt(java.time.Instant.now().toString()).build();
      String room = userId + ":" + formId;
      server.getRoomOperations(room).sendEvent("fill_request_bulk_state", payload);
      log.debug("Emitted user bulk state to room {} ({} requests)", room, reqs.size());
    } catch (Exception e) {
      log.debug("emitBulkStateForUser failed: {}", e.getMessage());
    }
  }

  public void emitUpdateForUser(String userId, String formId, FillRequestUpdateEvent payload) {
    ensureUserJoinedFormRoom(userId, formId);
    String room = userId + ":" + formId;
    String eventKey = userId + ":" + formId + ":" + payload.getRequestId() + ":"
        + payload.getStatus() + ":" + payload.getCompletedSurvey() + ":" + payload.getSurveyCount();

    // Check if this exact event was recently emitted for this user
    String lastEvent = lastEmittedEvents.get(room);
    if (eventKey.equals(lastEvent)) {
      log.debug("Skipping duplicate user event for room: {} - request={} status={}", room,
          payload.getRequestId(), payload.getStatus());
      return;
    }

    // Store this event as last emitted for this user room
    lastEmittedEvents.put(room, eventKey);

    server.getRoomOperations(room).sendEvent("fill_request_update", payload);
    log.debug("Emitted user update to room {}: request={} status={}", room, payload.getRequestId(),
        payload.getStatus());
  }

  public void leaveUserFormRoom(String userId, String formId) {
    String room = userId + ":" + formId;
    try {
      for (SocketIOClient c : server.getAllClients()) {
        try {
          String uid = c.get("userId");
          if (userId.equals(uid)) {
            c.leaveRoom(room);
          }
        } catch (Exception ignore) {
        }
      }
      log.debug("Left room {} for user {}", room, userId);
    } catch (Exception e) {
      log.debug("leaveUserFormRoom failed: {}", e.getMessage());
    }
  }

  /**
   * Debug method to check current deduplication state
   */
  public void debugDeduplicationState() {
    log.info("=== Deduplication State Debug ===");
    log.info("Total cached events: {}", lastEmittedEvents.size());
    log.info("Total meters: {}", meters.size());

    // Log some sample events
    int count = 0;
    for (Map.Entry<String, String> entry : lastEmittedEvents.entrySet()) {
      if (count < 5) { // Only log first 5
        log.info("Event cache: {} -> {}", entry.getKey(), entry.getValue());
        count++;
      }
    }

    // Log meter states
    count = 0;
    for (Map.Entry<String, Meter> entry : meters.entrySet()) {
      if (count < 5) { // Only log first 5
        Meter meter = entry.getValue();
        log.info("Meter: {} -> count={}, windowStart={}", entry.getKey(), meter.count,
            meter.windowStart);
        count++;
      }
    }
    log.info("=== End Deduplication State Debug ===");
  }

  private boolean canEmit(String roomId) {
    long now = System.currentTimeMillis();
    Meter m = meters.computeIfAbsent(roomId, k -> {
      Meter mm = new Meter();
      mm.windowStart = now;
      return mm;
    });

    // Reset counter if window expired
    if (now - m.windowStart >= 1000) {
      m.count = 0;
      m.windowStart = now;
    }

    // Allow emit only if under burst limit
    if (m.count < burstMax) {
      m.count++;
      return true;
    }

    log.debug("Rate limited emit for room: {} (count: {})", roomId, m.count);
    return false;
  }

  private DataListener<JoinLeavePayload> onJoin() {
    return (client, data, ackSender) -> {
      String roomId = "form:" + data.getFormId();
      client.joinRoom(roomId);
      // Emit snapshot immediately
      try {
        UUID formUuid = UUID.fromString(data.getFormId());
        Form form = formRepository.findById(formUuid).orElse(null);
        if (form != null) {
          List<FillRequest> requests = fillRequestRepository.findByForm(form);
          List<FillRequestUpdateEvent> reqs = requests.stream()
              .map(fr -> FillRequestUpdateEvent.builder().formId(data.getFormId())
                  .requestId(fr.getId().toString()).status(fr.getStatus().name())
                  .completedSurvey(fr.getCompletedSurvey()).surveyCount(fr.getSurveyCount())
                  .updatedAt(java.time.Instant.now().toString()).build())
              .toList();

          FillRequestBulkStateEvent payload =
              FillRequestBulkStateEvent.builder().formId(data.getFormId()).requests(reqs)
                  .updatedAt(java.time.Instant.now().toString()).build();
          emitBulkState(data.getFormId(), payload);
        }
      } catch (Exception ignore) {
      }
    };
  }

  private DataListener<JoinLeavePayload> onLeave() {
    return (client, data, ackSender) -> {
      String roomId = "form:" + data.getFormId();
      client.leaveRoom(roomId);
    };
  }

  /**
   * Schedule cleanup of old events to prevent memory leaks
   */
  private void scheduleEventCleanup() {
    Thread cleanupThread = new Thread(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        try {
          Thread.sleep(5 * 60 * 1000); // 5 minutes
          cleanupOldEvents();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (Exception e) {
          log.warn("Error during event cleanup: {}", e.getMessage());
        }
      }
    });
    cleanupThread.setDaemon(true);
    cleanupThread.setName("EventCleanupThread");
    cleanupThread.start();
  }

  /**
   * Clean up old events and meters to prevent memory leaks
   */
  private void cleanupOldEvents() {
    try {
      long now = System.currentTimeMillis();
      long cutoff = now - (10 * 60 * 1000); // 10 minutes ago

      // Clean up old meters
      meters.entrySet().removeIf(entry -> {
        Meter meter = entry.getValue();
        return (now - meter.windowStart) > (5 * 60 * 1000); // 5 minutes
      });

      // Clean up old events (keep only recent ones)
      if (lastEmittedEvents.size() > 1000) { // If too many events stored
        lastEmittedEvents.clear(); // Simple cleanup - in production you might want more
                                   // sophisticated logic
        log.debug("Cleaned up old events cache");
      }

      log.debug("Event cleanup completed - meters: {}, events: {}", meters.size(),
          lastEmittedEvents.size());
    } catch (Exception e) {
      log.warn("Error during event cleanup: {}", e.getMessage());
    }
  }
}


