package com.cricket.platform.identity;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ActiveWebSocketSessions {
    private final ConcurrentMap<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> socketsByUserSession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> userSessionBySocket = new ConcurrentHashMap<>();

    public void register(WebSocketSession socket) {
        sockets.put(socket.getId(), socket);
    }

    public void bind(String userSessionId, String socketId) {
        if (userSessionId == null || socketId == null || !sockets.containsKey(socketId)) return;
        socketsByUserSession.computeIfAbsent(userSessionId, ignored -> ConcurrentHashMap.newKeySet()).add(socketId);
        userSessionBySocket.put(socketId, userSessionId);
    }

    public void unregister(WebSocketSession socket) {
        sockets.remove(socket.getId());
        String userSessionId = userSessionBySocket.remove(socket.getId());
        if (userSessionId == null) return;
        Set<String> ids = socketsByUserSession.get(userSessionId);
        if (ids != null) {
            ids.remove(socket.getId());
            if (ids.isEmpty()) socketsByUserSession.remove(userSessionId, ids);
        }
    }

    public void closeByUserSession(String userSessionId) {
        Set<String> ids = socketsByUserSession.remove(userSessionId);
        if (ids == null) return;
        for (String id : ids) {
            userSessionBySocket.remove(id);
            WebSocketSession socket = sockets.remove(id);
            if (socket != null && socket.isOpen()) {
                try {
                    socket.close(CloseStatus.POLICY_VIOLATION);
                } catch (IOException ignored) {
                    // The revoked session is already blocked at the REST/STOMP authorization layer.
                }
            }
        }
    }
}
