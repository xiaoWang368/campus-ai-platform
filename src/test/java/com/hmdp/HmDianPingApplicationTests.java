package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;


import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@SpringBootTest
class HmDianPingApplicationTests {
@Resource
private StringRedisTemplate stringRedisTemplate;
@Resource
private ShopServiceImpl shopService;
    @Test
    public void TestHyperLogLog(){
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999)
            stringRedisTemplate.opsForHyperLogLog().add("hl1", values);
        }

        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl1");
        System.out.println(count);
    }

    @Test
    void loadShopData(){
        //查询店铺信息
        List<Shop> list = shopService.list();
        //将店铺进行分组,按照TypeId一致的放在一个集合里
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批写入redis里面
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //类型id
            Long typeId = entry.getKey();
            //同类型的店铺id
            List<Shop> value = entry.getValue();
            String key = "shop:geo:" + typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                //stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

}
