package com.hmdp.agent;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tool 执行器
 * 将 Python Agent 的 tool 调用映射到 Spring Boot 业务服务
 */
@Slf4j
@Component
public class ToolExecutor {

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IBlogService blogService;

    @Resource
    private IShopTypeService shopTypeService;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 执行工具调用
     *
     * @param toolName 工具名称
     * @param argumentsJson JSON 参数字符串
     * @return JSON 结果字符串
     */
    public String execute(String toolName, String argumentsJson) {
        log.info("执行工具: {} 参数: {}", toolName, argumentsJson);
        try {
            JsonNode args = mapper.readTree(argumentsJson);
            return switch (toolName) {
                case "search_shop" -> executeSearchShop(args);
                case "query_voucher_of_shop" -> executeQueryVoucherOfShop(args);
                case "create_blog" -> executeCreateBlog(args);
                default -> throw new IllegalArgumentException("未知工具: " + toolName);
            };
        } catch (Exception e) {
            log.error("工具执行失败: {} - {}", toolName, e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ========== search_shop ==========

    private String executeSearchShop(JsonNode args) throws Exception {
        String name = args.has("name") ? args.get("name").asText("") : "";
        String area = args.has("area") ? args.get("area").asText("") : "";
        String type = args.has("type") ? args.get("type").asText("") : "";

        // 按分类名称查找 typeId
        Long typeId = null;
        if (StrUtil.isNotBlank(type)) {
            ShopType shopType = shopTypeService.query().eq("name", type).one();
            if (shopType != null) {
                typeId = shopType.getId();
                log.debug("分类 '{}' → typeId={}", type, typeId);
            } else {
                log.warn("未找到分类: {}", type);
            }
        }

        // 动态构建查询条件
        List<Shop> shops = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .eq(typeId != null, "type_id", typeId)
                .like(StrUtil.isNotBlank(area), "area", area)
                .orderByDesc("score")
                .last("LIMIT 20")
                .list();

        // 转为简洁的列表
        List<Map<String, Object>> result = shops.stream().map(shop -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", shop.getId());
            m.put("name", shop.getName());
            m.put("area", shop.getArea());
            m.put("address", shop.getAddress());
            m.put("avgPrice", shop.getAvgPrice());
            m.put("score", shop.getScore() != null ? shop.getScore() / 10.0 : 0);
            m.put("sold", shop.getSold());
            m.put("openHours", shop.getOpenHours());
            return m;
        }).collect(Collectors.toList());

        return mapper.writeValueAsString(result);
    }

    // ========== query_voucher_of_shop ==========

    private String executeQueryVoucherOfShop(JsonNode args) throws Exception {
        String shopName = args.has("shop_name") ? args.get("shop_name").asText("") : "";

        if (StrUtil.isBlank(shopName)) {
            return "{\"error\":\"缺少 shop_name 参数\"}";
        }

        // 根据店铺名称查找
        List<Shop> shops = shopService.query().like("name", shopName).list();
        if (shops.isEmpty()) {
            return "{\"message\":\"未找到匹配的店铺\", \"shops\":[]}";
        }

        List<Map<String, Object>> result = shops.stream().map(shop -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("shopId", shop.getId());
            entry.put("shopName", shop.getName());

            // 查询该店铺的优惠券
            com.hmdp.dto.Result voucherResult = voucherService.queryVoucherOfShop(shop.getId());
            if (voucherResult.getData() != null) {
                @SuppressWarnings("unchecked")
                List<Voucher> vouchers = (List<Voucher>) voucherResult.getData();
                entry.put("vouchers", vouchers.stream().map(v -> {
                    Map<String, Object> vMap = new HashMap<>();
                    vMap.put("id", v.getId());
                    vMap.put("title", v.getTitle());
                    vMap.put("subTitle", v.getSubTitle());
                    vMap.put("payValue", v.getPayValue());
                    vMap.put("actualValue", v.getActualValue());
                    vMap.put("type", v.getType() == 0 ? "普通券" : "秒杀券");
                    vMap.put("rules", v.getRules());
                    return vMap;
                }).collect(Collectors.toList()));
            } else {
                entry.put("vouchers", List.of());
                entry.put("message", "该店铺暂无可用优惠券");
            }
            return entry;
        }).collect(Collectors.toList());

        return mapper.writeValueAsString(result);
    }

    // ========== create_blog ==========

    private String executeCreateBlog(JsonNode args) throws Exception {
        long shopId = args.get("shop_id").asLong();
        String title = args.get("title").asText();
        String content = args.get("content").asText();

        // 验证店铺是否存在
        Shop shop = shopService.getById(shopId);
        if (shop == null) {
            return "{\"error\":\"店铺不存在, shopId=" + shopId + "\"}";
        }

        // 构建 Blog 实体并保存
        Blog blog = new Blog();
        blog.setShopId(shopId);
        blog.setTitle(title);
        blog.setContent(content);
        blog.setImages("");

        // 注意：Agent 操作使用默认测试用户（在实际使用中应从会话上下文获取）
        // UserHolder 在 gRPC 上下文中没有值，所以直接设置 userId
        blog.setUserId(SystemConstants.AGENT_USER_ID);

        boolean isSuccess = blogService.save(blog);
        if (!isSuccess) {
            return "{\"error\":\"创建笔记失败\"}";
        }

        log.info("Agent 创建笔记成功: blogId={}, title={}", blog.getId(), title);

        Map<String, Object> result = new HashMap<>();
        result.put("blogId", blog.getId());
        result.put("shopName", shop.getName());
        result.put("title", title);
        result.put("message", "笔记创建成功");
        return mapper.writeValueAsString(result);
    }
}
