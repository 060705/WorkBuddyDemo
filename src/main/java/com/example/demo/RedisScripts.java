package com.example.demo;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Lua 脚本注册中心（工程化关键：脚本不散落在业务代码里）
 *
 * 约定：
 * 1. 所有 Lua 脚本统一放 resources/lua/ 下，命名 {域}_{动作}.lua
 * 2. 新增脚本只在这个类里加一行静态常量，业务代码一律从这里引用
 * 3. 脚本必须参数化（KEYS/ARGV 传参），禁止业务侧字符串拼接
 *    —— 防注入，且能被 Redis 脚本缓存命中（EVALSHA 复用）
 * 4. DefaultRedisScript 是线程安全的，做成静态常量即可
 *
 * 典型系统里这类"原子原语"通常就 5~15 个，几十上百个业务接口复用它们，
 * 而不是每个接口写一个脚本。
 */
public final class RedisScripts {

    private RedisScripts() {
    }

    /**
     * 原子扣减：查 + 判断 + 扣 三步一次执行完（防超卖）
     * KEYS[1]=库存key  ARGV[1]=扣减数量
     * 返回：扣减后剩余库存；-1 表示库存不足
     */
    public static final DefaultRedisScript<Long> DEDUCT =
            build("lua/stock_deduct.lua", Long.class);

    /** 统一构建：注意 setScriptSource 需要 ScriptSource，用 ResourceScriptSource 包装 */
    private static <T> DefaultRedisScript<T> build(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(resultType);
        return script;
    }
}
