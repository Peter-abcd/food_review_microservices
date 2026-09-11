package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    /** 已下单用户集合，key = seckill:order:{voucherId}，由 seckill.lua 写入 */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";

    /** 订单明细哈希，key = seckill:order:detail:{voucherId}，由 seckill.lua 写入（field = orderId） */
    public static final String SECKILL_ORDER_DETAIL_KEY = "seckill:order:detail:";

    /** 秒杀订单重试计数，key = seckill:retry:{orderId} */
    public static final String SECKILL_RETRY_COUNT_KEY = "seckill:retry:";

    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String SHOP_LIST_KEY = "shop:list:";

    // JetCache 配置（可根据需要调整）
    public static final Integer JETCACHE_LOCAL_LIMIT = 100;
    public static final Long JETCACHE_EXPIRE = 7200L; // 2小时
    public static final Long JETCACHE_REFRESH = 1800L; // 30分钟自动刷新

}
