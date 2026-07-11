package com.campus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.dto.Result;
import com.campus.entity.Shop;
import com.campus.mapper.ShopMapper;
import com.campus.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.utils.CacheClient;
import com.campus.utils.RedisConstants;
import com.campus.utils.RedisData;
import com.campus.utils.SystemConstants;
import org.apache.coyote.Response;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.campus.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author wjc
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private CacheClient cacheClient;

    private final RedisTemplate redisTemplate;

    private final StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(RedisTemplate redisTemplate, StringRedisTemplate stringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Shop queryById(Long id) {
        //缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class,
             // this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //互斥锁解除缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,
                id2 ->getById(id2), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //逻辑过期解除缓存击穿

        return shop;
    }

    /*
    缓存穿透代码
    */
    //缓存空值
    /*public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.LOGIN_CODE_KEY + id;
        //redis查询商铺缓存
        String shopJson = (String) redisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null) {
            //不是空值就是不存在
            return null;
        }
        //不存在，从数据库查询
        Shop shop = getById(id);
        if (shop == null) {
            //加空值
            stringRedisTemplate.opsForValue().set(key,
                    "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            //不存在，报错
            return null;
        }
        //存在，写入redis
        stringRedisTemplate.opsForValue().set(key,
                JSONUtil.toJsonStr(shopJson), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }*/

    /*
     * 互斥锁解决缓存穿透
     * */
   /* public Shop queryWithMutex(Long id) {
        String key = RedisConstants.LOGIN_CODE_KEY + id;
        //redis查询商铺缓存
        String shopJson = (String) redisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null) {
            //不是空值就是不存在
            return null;
        }
        //4，缓存重建
        //4.1 获取互斥锁
        String lockkey = LOCK_SHOP_KEY + id;
        Shop shop = new Shop();
        try {
            boolean lock = trylock(lockkey);
            //4.2 判断是否获取
            if (!lock) {
                //4.3 未获取，休眠
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4数据库查询
            shop = getById(id);
            if (shop == null) {
                //加空值
                stringRedisTemplate.opsForValue().set(key,
                        "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                //不存在，报错
                return null;
            }
            //存在，写入redis
            stringRedisTemplate.opsForValue().set(key,
                    JSONUtil.toJsonStr(shopJson), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unlock(lockkey);
        }
        return shop;
    }*/

    //上锁
    private boolean trylock(String key) {
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        boolean a = BooleanUtil.isTrue(flag);
        return a;
    }

    //解锁
    private void unlock(String key) {
        redisTemplate.delete(key);
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /*
     * 逻辑过期解决缓存穿透
     * */
    public Shop queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //redis查询商铺缓存
        String shopJson = (String) redisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //是空直接返回
            return null;
        }
        //命中，将json转换为序列化对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        //过期，缓存重建
        // 获取互斥锁
        boolean trylock = trylock(LOCK_SHOP_KEY + id);
        // 获取
        if (trylock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(LOCK_SHOP_KEY + id);
                }
            });
        }
        //获取不获取都要返回
        return shop;

    }


    private void saveShop2Redis(Long id, Long expireSeconds) {
        //查询商铺信息
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入reids
        redisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

    /*
     * 更新商铺
     * 写入数据库,删除缓存*/
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //判断id里是否为空
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        redisTemplate.delete(key);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands
                                .GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() < from){
            return Result.ok(Collections.emptyList());
        }
        //截取from~end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result ->{
            String shopIdStr = result.getContent().getName();
            Distance distance = result.getDistance();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr,distance);

        });
        //根据id查询店铺
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
