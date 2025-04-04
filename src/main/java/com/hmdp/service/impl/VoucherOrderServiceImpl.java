package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissionClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private IVoucherOrderService proxy;

    @PostConstruct
    public  void  init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }



    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 如果有订单，则开始下单.通知消息队列消息已被处理（ACK确认）
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), false);
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList(){
            while(true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        break;
                    }
                    // 如果有订单，则开始下单.通知消息队列消息已被处理（ACK确认）
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), false);
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending_list异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }


//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    //1.获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //2.创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常",e);
//                }
//            }
//        }
//    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                String.valueOf(userId),
                String.valueOf(orderId)
        );
        //2.判断结果是否为0（不为0代表没有购买资格）
        int r = result.intValue();
        if(r!=0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //为0，有购买资格，把下单信息保存到阻塞队列
        log.info("用户:"+UserHolder.getUser().getId().toString()+"正在下单");
        //获取代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                UserHolder.getUser().getId().toString()
//        );
//        //2.判断结果是否为0（不为0代表没有购买资格）
//        int r = result.intValue();
//        if(r!=0){
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        //为0，有购买资格，把下单信息保存到阻塞队列
//        long orderId = redisIdWorker.nextId("order");
//        log.info("用户:"+UserHolder.getUser().getId().toString()+"正在下单");
//        // TODO 保存到阻塞队列中
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        voucherOrder.setVoucherId(voucherId);
//        // 创建阻塞队列
//        orderTasks.add(voucherOrder);
//        //获取代理对象
//        proxy = (IVoucherOrderService)AopContext.currentProxy();
//
//        return Result.ok(orderId);
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //创建锁对象
        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        Long userId = voucherOrder.getUserId();
        RLock lock = redissionClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();

        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        // 获取代理对象
        // Transactional事物注解由SpringBoot代理实现，所以无法直接使用this.[method]需要先获取代理对象再调用方法
        try{
            proxy.createVouchOrder(voucherOrder);
        }finally {
            lock.unlock();
        }

    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2. 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        // 3. 判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        // 4. 判断库存是否充足 - 扣减库存
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0)
//                .update();
//        if (!success) {
//            return Result.fail("库存不足");
//        }
//        //一人一单
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
//        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissionClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//
//        if (!isLock) {
//            return Result.fail("不允许重复下单");
//        }
//        // 获取代理对象
//        // Transactional事物注解由SpringBoot代理实现，所以无法直接使用this.[method]需要先获取代理对象再调用方法
//        try{
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVouchOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//
//    }



    @Transactional
    public void createVouchOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            log.error("用户已经购买过一次！");
            return;
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }
        log.info("用户:"+userId+"下单成功");
        // 5. 创建订单
        save(voucherOrder);


    }
}
