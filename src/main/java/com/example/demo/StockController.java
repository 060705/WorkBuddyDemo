package com.example.demo;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 库存一致性实战：多线程并发扣库存，对比"非原子三步"与"Lua 原子"两种实现。
 *
 * 接口清单：
 * - GET /stock/init?total=100          初始化库存
 * - GET /stock/query                   查询当前库存
 * - GET /stock/deduct?num=1            Lua 原子扣减（单次）
 * - GET /stock/pressure?threads=100&mode=lua|bad   并发压测（服务端起 N 线程同时扣）
 *
 * 说明：库存用 StringRedisTemplate（纯字符串），避免 JSON 序列化把 "100" 存成带引号的
 * "100" 导致 DECRBY 报 not an integer。
 */
@RestController
@RequestMapping("/stock")
public class StockController {

    private static final String STOCK_KEY = "stock";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // Lua 脚本统一从注册中心 RedisScripts 取，不在业务代码里重复定义

    /** 初始化库存 */
    @GetMapping("/init")
    public Map<String, Object> init(@RequestParam(defaultValue = "100") int total) {
        stringRedisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(total));
        return result("init", 0, 0, total);
    }

    /** 查询当前库存 */
    @GetMapping("/query")
    public Map<String, Object> query() {
        long stock = currentStock();
        return result("query", 0, 0, stock);
    }

    /** Lua 原子扣减（单次，供手工调用体验） */
    @GetMapping("/deduct")
    public Map<String, Object> deduct(@RequestParam(defaultValue = "1") int num) {
        boolean ok = luaDeduct(num);
        return result(ok ? "deduct-lua-ok" : "deduct-lua-fail(库存不足)", ok ? 1 : 0, ok ? 0 : 1, currentStock());
    }

    /**
     * 并发压测：服务端起 threads 个线程同时扣 1 件。
     * mode=lua  -> Lua 原子扣减（期望：不超卖）
     * mode=bad  -> 非原子三步 GET-判断-SET（期望：超卖，错误示范）
     */
    @GetMapping("/pressure")
    public Map<String, Object> pressure(@RequestParam(defaultValue = "100") int threads,
                                        @RequestParam(defaultValue = "1") int num,
                                        @RequestParam(defaultValue = "lua") String mode) throws Exception {
        long before = currentStock();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);   // 所有线程就位
        CountDownLatch start = new CountDownLatch(1);         // 发令枪：尽量同一时刻开跑
        CountDownLatch done  = new CountDownLatch(threads);   // 全部完成
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail    = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();                            // 等发令
                    boolean ok = "bad".equals(mode) ? badDeduct(num) : luaDeduct(num);
                    if (ok) success.incrementAndGet(); else fail.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();                                        // 全部就位
        start.countDown();                                    // 开跑
        done.await();                                         // 等全部结束
        pool.shutdown();

        long after = currentStock();
        long sold = before - after;                           // 实际扣掉的库存
        long oversell = Math.max(0, success.get() - sold);    // 成功数 > 实扣数 = 超卖（虚假成功）

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode", mode);
        r.put("threads", threads);
        r.put("stockBefore", before);
        r.put("stockAfter", after);
        r.put("sold(actual)", sold);
        r.put("success(reported)", success.get());
        r.put("fail", fail.get());
        r.put("oversell", oversell > 0 ? "YES!!! 超卖 " + oversell + " 件" : "NO");
        return r;
    }

    /** 正确姿势：Lua 原子扣减（查+判断+扣 三步一次执行完） */
    private boolean luaDeduct(int num) {
        Long rest = stringRedisTemplate.execute(RedisScripts.DEDUCT, List.of(STOCK_KEY), String.valueOf(num));
        return rest != null && rest >= 0;                     // -1 = 库存不足
    }

    /** 错误示范：非原子三步（GET -> 判断 -> SET），并发下必然超卖 */
    private boolean badDeduct(int num) {
        String v = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        int stock = v == null ? 0 : Integer.parseInt(v);
        if (stock < num) {
            return false;
        }
        // 这两步之间，其他线程可能已经把库存扣走了 —— SET 会互相覆盖
        stringRedisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(stock - num));
        return true;
    }

    private long currentStock() {
        String v = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        return v == null ? 0 : Long.parseLong(v);
    }

    private Map<String, Object> result(String action, int success, int fail, long stock) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action", action);
        r.put("success", success);
        r.put("fail", fail);
        r.put("stock", stock);
        return r;
    }
}
