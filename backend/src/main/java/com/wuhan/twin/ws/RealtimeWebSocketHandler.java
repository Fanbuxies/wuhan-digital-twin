package com.wuhan.twin.ws;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuhan.twin.ws.vo.PushMessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 实时数据推送处理器，只做服务端单向广播，不处理客户端上行消息
 *
 * @author lvfan
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    /**
     * 在线连接。写少读多，用 CopyOnWriteArraySet 免去遍历时加锁
     */
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.debug("实时通道建立连接 {}，当前连接数 {}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.debug("实时通道关闭连接 {}，剩余连接数 {}", session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session);
        log.warn("实时通道传输异常，已移除连接 {}", session.getId(), exception);
    }

    /**
     * 向所有在线连接广播一条消息，无连接时直接返回
     *
     * @param type 消息类型，取 PushMessageVO 的类型常量
     * @param data 消息体
     */
    public void broadcast(String type, Object data) {
        if (sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new PushMessageVO(type, data));
        } catch (JsonProcessingException e) {
            log.error("推送体序列化失败，type={}", type, e);
            return;
        }
        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            sendQuietly(session, message);
        }
    }

    /**
     * 单连接发送。失败只摘除该连接，不影响其余连接
     */
    private void sendQuietly(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return;
        }
        try {
            // WebSocketSession 并非线程安全，同一 session 的发送必须串行
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (IOException | IllegalStateException e) {
            sessions.remove(session);
            log.warn("实时推送失败，已移除连接 {}", session.getId(), e);
        }
    }
}
