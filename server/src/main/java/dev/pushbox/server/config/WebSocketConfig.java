package dev.pushbox.server.config;

import dev.pushbox.server.push.PushboxWsHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** §5.3：原生 WebSocket（不用 STOMP），一帧一条 JSON 文本消息。 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final PushboxWsHandler handler;

  public WebSocketConfig(PushboxWsHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, "/ws").setAllowedOriginPatterns("*");
  }

  @Bean
  ServletServerContainerFactoryBean webSocketContainer(PbProperties props) {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    // 两个缓冲都必须 >0：二进制缓冲设成 0 时 Tomcat 的读帧循环会静默停摆——
    // 握手与下行正常，但服务端永远收不到客户端的帧，日志里一个字都不会有（P0 排障时踩过）。
    container.setMaxTextMessageBufferSize(props.getWs().getFrameMaxBytes() * 2);
    container.setMaxBinaryMessageBufferSize(props.getWs().getFrameMaxBytes() * 2);
    return container;
  }
}
