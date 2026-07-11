package com.campus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.campus.config.RedisIdWorker;
import com.campus.dto.Result;
import com.campus.entity.VoucherOrder;
import com.campus.mapper.VoucherOrderMapper;
import com.campus.service.ISeckillVoucherService;
import com.campus.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.utils.UserHolder;
import net.sf.jsqlparser.expression.StringValue;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 继晨
 * @since 2026-01-13
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final Logger log = LoggerFactory.getLogger(VoucherOrderServiceImpl.class);

    private static final DefaultRedisScript<Long> SECKILL_SCRRIPT;
    static{
        SECKILL_SCRRIPT = new DefaultRedisScript<Long>();
        SECKILL_SCRRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 后台线程运行标记，用于优雅关闭
     */
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherIdHandler());
    }
    @PreDestroy
    public void destroy(){
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdownNow();
        try{
            if(!SECKILL_ORDER_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)){
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            }
        }catch (InterruptedException e){
            SECKILL_ORDER_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    private class VoucherIdHandler implements Runnable {
        String queuename = "stream.orders";
        @Override
        public void run() {
            // 确保消费者组存在
            createConsumerGroupIfNotExists(queuename, "g1");
            while(running){
                try {
                    //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queuename, ReadOffset.lastConsumed()) // 使用 lastConsumed() 而不是 latest()
                    );
                    //判断是否获取成功
                    if(list == null || list.isEmpty()) {
                        //获取失败，说明没有消息，下次循环
                        continue;
                    }
                    //解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //获取成功，可以下单
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queuename, "g1", record.getId());
                    //创建订单
                    HandleVoucherOrder(voucherOrder);

                } catch (Exception e) {
                    if (!running) {
                        break;
                    }
                    log.error("处理订单异常",e);
                    handlePendinglist();
                }
            }
        }

        private void handlePendinglist(){
            createConsumerGroupIfNotExists(queuename, "g1");
            try {
                //获取pendinglist中的订单信息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(10),
                        StreamOffset.create(queuename, ReadOffset.from("0"))
                );
                //判断是否获取成功
                if(list == null || list.isEmpty()) {
                    //获取失败，说明没有消息
                    return;
                }
                //解析数据
                for (MapRecord<String, Object, Object> record : list) {
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //获取成功，可以下单

                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queuename, "g1", record.getId());
                    //创建订单
                    HandleVoucherOrder(voucherOrder);
                }
            } catch (Exception e) {
                log.error("处理pendinglist消息异常", e);
            }
        }


        private void HandleVoucherOrder(VoucherOrder voucherOrder) {
            Long id = voucherOrder.getUserId();
            //可以不加锁，因为lua脚本已经加了锁，这个是一个兜底
            //创建锁对象
            RLock lock = redissonClient.getLock("lock:order:" + id);
            boolean tryLock = lock.tryLock();
            if (!tryLock) {
                log.error("不允许重复下单");
            }
            //获取代理对象
            //通过代理对象调用方法才能使@Transactional注解生效
            try {
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                lock.unlock();
            }
        }
    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //用户id
        Long userId = UserHolder.getUser().getId();
        //订单id
        Long orderId = redisIdWorker.nextId("order");
        //执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");

        }
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
/*    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //执行Lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRRIPT,
                Collections.emptyList(),
                voucherId, userId);
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");

        }
        //创建订单，把订单信息保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列中
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }*/

/*   public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // This will give you the begin time of the voucher
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }

        //判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");

        }
        Long id = UserHolder.getUser().getId();
        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + id);
        RLock lock = redissonClient.getLock("lock:order:" + id);
        boolean tryLock = lock.tryLock();
        if (!tryLock) {
            return Result.fail("请勿重复下单");
        }
        //获取代理对象
        //通过代理对象调用方法才能使@Transactional注解生效
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }
    */


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long id = voucherOrder.getUserId();
        long count = query().eq("user_id", id).eq("voucher_id", voucherOrder.getVoucherId()).count();

        if (count > 0) {
            log.error("用户已经购买过该优惠券，不允许重复下单");
            return ;
        }

        //扣减库存
        boolean update = seckillVoucherService.update()
                .setSql("stock = stock - 1") //sql语句
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)//where 乐观锁
                .update();
        if (!update) {
            log.error("库存不足");
            return;
        }

        save(voucherOrder);

    }
    // 创建消费者组的方法
    private void createConsumerGroupIfNotExists(String streamKeyName, String groupName) {
        try {
            stringRedisTemplate.opsForStream().createGroup(streamKeyName, groupName);
        } catch (Exception e) {
            // 如果消费者组已存在，会抛出异常，这是正常情况
            // 我们只需要忽略这个异常即可
        }
    }

}
