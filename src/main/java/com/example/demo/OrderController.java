package com.example.demo;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 幂等下单：SET NX EX 幂等去重 + Lua 原子扣库存 组合
 *
 * 接口：
 * - GET /order/submit?orderId=A&num=1      单笔下单（重复提交同一 orderId 只扣一次）
 * - GET /order/pressure?orders=50&repeat=2 并发压测：orders 个订单各提交 repeat 次
 * - GET /order/reset                       清理幂等标记（方便重复实验）
 *
 * 幂等原理（不需要 Lua，一条命令搞定）：
 *   SET idem:order:<orderId> 1 NX EX 3600   ->  OK = 首次请求；nil = 重复请求
 *
 * 关键边界：扣库存失败/异常时必须删除幂等标记，否则用户重试会被永久判定为"重复请求"。
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    private static final String STOCK_KEY  = "stock";
    private static final String IDEM_PREFIX = "idem:order:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 单笔下单：幂等占位 -> Lua 扣库存 */
    @GetMapping("/submit")
    public Map<String, Object> submit(@RequestParam String orderId,
                                      @RequestParam(defaultValue = "1") int num) {
        String idemKey = IDEM_PREFIX + orderId;

        // 1) 幂等占位：NX = 只有不存在才能设置成功，即"首次请求"
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(idemKey, "1", Duration.ofHours(1));
        if (!Boolean.TRUE.equals(first)) {
            return result("DUPLICATE", "重复请求已忽略", currentStock());
        }

        try {
            // 2) 原子扣库存（脚本来自注册中心 RedisScripts）
            Long rest = stringRedisTemplate.execute(
                    RedisScripts.DEDUCT, List.of(STOCK_KEY), String.valueOf(num));

            if (rest == null || rest < 0) {
                // 3) 库存不足：清掉幂等标记，允许用户稍后重试
                stringRedisTemplate.delete(idemKey);
                return result("NO_STOCK", "库存不足（已释放幂等标记，可重试）", currentStock());
            }
            return result("OK", "下单成功", rest);
        } catch (RuntimeException e) {
            // 异常同样要清标记，避免占着位子导致该订单永久下不了
            stringRedisTemplate.delete(idemKey);
            throw e;
        }
    }

    /**
     * 并发压测：orders 个不同订单，每个提交 repeat 次（模拟用户重复点击 + 高并发）
     * 期望：成功数 == orders，重复数 == orders*(repeat-1)，实扣库存 == orders
     */
    @GetMapping("/pressure")
    public Map<String, Object> pressure(@RequestParam(defaultValue = "50") int orders,
                                        @RequestParam(defaultValue = "2") int repeat) throws Exception {
        int total = orders * repeat;
        long before = currentStock();

        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(total);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger dup = new AtomicInteger();
        AtomicInteger noStock = new AtomicInteger();

        for (int i = 0; i < total; i++) {
            final String orderId = "order-" + (i % orders);   // 同一订单会被提交 repeat 次
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    Map<String, Object> r = submit(orderId, 1);
                    String status = String.valueOf(r.get("status"));
                    if ("OK".equals(status)) ok.incrementAndGet();
                    else if ("DUPLICATE".equals(status)) dup.incrementAndGet();
                    else noStock.incrementAndGet();
                } catch (Exception e) {
                    noStock.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        long after = currentStock();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("orders(不同订单数)", orders);
        r.put("repeat(每单重复次数)", repeat);
        r.put("totalRequests", total);
        r.put("stockBefore", before);
        r.put("stockAfter", after);
        r.put("sold(actual)", before - after);
        r.put("ok(下单成功)", ok.get());
        r.put("duplicate(重复被拦)", dup.get());
        r.put("noStock", noStock.get());
        r.put("idempotentOK", (before - after) == orders && ok.get() == orders
                ? "YES 重复提交只扣一次" : "NO 异常！");
        return r;
    }

    /** 清理幂等标记，方便反复实验 */
    @GetMapping("/reset")
    public Map<String, Object> reset() {
        Long deleted = 0L;
        var keys = stringRedisTemplate.keys(IDEM_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            deleted = stringRedisTemplate.delete(keys);
        }
        return result("RESET", "清理幂等标记 " + deleted + " 个", currentStock());
    }

    private long currentStock() {
        String v = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        return v == null ? 0 : Long.parseLong(v);
    }

    private Map<String, Object> result(String status, String msg, long stock) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("msg", msg);
        r.put("stock", stock);
        return r;
    }
}
