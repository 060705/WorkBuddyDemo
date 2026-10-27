package com.example.demo;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 修复 SSE 流式输出中文乱码。
 *
 * <p>根因：Spring MVC 处理 String 返回值/消息体的 {@link StringHttpMessageConverter}
 * 默认编码是 <b>ISO-8859-1</b>，中文经它一写就成了乱码。
 * SseEmitter 的 chunk 也是 String，走的正是它，所以浏览器看到「鍟嗘堡」这类乱码。
 *
 * <p>做法：注册一个 UTF-8 的 StringHttpMessageConverter 替换默认实现。
 * 影响面仅限「返回 String 的接口」（如 SSE 流、文本接口），Map/对象走 Jackson 不受影响。
 */
@Configuration
public class WebConfig {

    @Bean
    public StringHttpMessageConverter stringHttpMessageConverter() {
        return new StringHttpMessageConverter(StandardCharsets.UTF_8);
    }

    /**
     * 兜底：浏览器地址栏直接打开 /ai/stream 时，Chrome 对 text/event-stream 原始流
     * 默认按 GBK 渲染。这里强制该路径的响应带 charset=UTF-8，让直开原始流也不乱码。
     * （正常用法是访问首页 index.html，用 EventSource 渲染，天然 UTF-8 无此问题。）
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> sseUtf8Filter() {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                response.setCharacterEncoding("UTF-8");
                response.setContentType("text/event-stream;charset=UTF-8");
                filterChain.doFilter(request, response);
            }
        });
        bean.addUrlPatterns("/ai/stream");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
