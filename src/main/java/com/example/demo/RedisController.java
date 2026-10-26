package com.example.demo;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * Redis 读写测试接口：用于端到端验证 Spring Boot -> K8s Redis 链路。
 * - 写：GET/POST /redis/set?key=xxx&value=yyy
 * - 读：GET /redis/get?key=xxx
 * 注意：Spring 6 起，同一方法叠用 @GetMapping + @PostMapping 只有第一个生效
 * （启动日志会告警 "Multiple @RequestMapping annotations ... only the first will be used"），
 * 要同时支持 GET/POST 必须用 @RequestMapping 的 method 数组写法。
 */
@RestController
@RequestMapping("/redis")
public class RedisController {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    /** 写入 Redis（同时支持 GET/POST，方便浏览器与 curl 调试） */
    @RequestMapping(value = "/set", method = {RequestMethod.GET, RequestMethod.POST})
    public String set(@RequestParam String key, @RequestParam String value) {
        redisTemplate.opsForValue().set(key, value);
        return "SET OK -> key=" + key + ", value=" + value;
    }

    /** 从 Redis 读取 */
    @GetMapping("/get")
    public String get(@RequestParam String key) {
        Object value = redisTemplate.opsForValue().get(key);
        return "GET -> key=" + key + ", value=" + value;
    }
}
