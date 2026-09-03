package com.qilu.config;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.qilu.dto.UserDTO;
import com.qilu.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@Component
public class InboxWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String AUTH_FAILED_ATTRIBUTE = "inboxAuthFailed";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) {
            return false;
        }
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        String token = servletRequest.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            token = servletRequest.getParameter("token");
        }
        if (StrUtil.isBlank(token)) {
            attributes.put(AUTH_FAILED_ATTRIBUTE, true);
            return true;
        }
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        if (userMap.isEmpty()) {
            // Browsers cannot inspect a failed WebSocket handshake status. Let the handler send an
            // explicit AUTH_FAILED frame and close with 4401 so the client can permanently stop retries.
            attributes.put(AUTH_FAILED_ATTRIBUTE, true);
            return true;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        attributes.put("userId", userDTO.getId());
        if (userDTO.getId() == null) {
            attributes.put(AUTH_FAILED_ATTRIBUTE, true);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
