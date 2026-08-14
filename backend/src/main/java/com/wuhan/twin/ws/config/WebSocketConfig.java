package com.wuhan.twin.ws.config;

import com.wuhan.twin.ws.RealtimeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册
 *
 * @author lvfan
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * 实时数据推送端点
     */
    private static final String REALTIME_ENDPOINT = "/ws/realtime";

    private final RealtimeWebSocketHandler realtimeWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 放开来源限制，与 WebMvcConfig 的 CORS 策略保持一致
        registry.addHandler(realtimeWebSocketHandler, REALTIME_ENDPOINT)
                .setAllowedOriginPatterns("*");
    }
}
