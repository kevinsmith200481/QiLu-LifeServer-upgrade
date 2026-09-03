package com.qilu.config;

import com.qilu.utils.AdminRoleInterceptor;
import com.qilu.utils.Login_in_interceptor;
import com.qilu.utils.Refresh_interceptor;
import javax.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器
        registry.addInterceptor(new Login_in_interceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/ai/internal/tools/query",
                        "/ai/metrics/**",
                        "/actuator/prometheus",
                        "/ws/**"
                ).order(1);
        registry.addInterceptor(new AdminRoleInterceptor())
                .addPathPatterns("/admin/**")
                .order(2);
        //刷新token拦截器，负责将用户对网页的所有操作都可以拦截并刷新token，并且要用order控制在登录拦截器前执行
        registry.addInterceptor(new Refresh_interceptor(stringRedisTemplate)).order(0);
    }
}
