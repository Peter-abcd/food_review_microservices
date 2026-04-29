package com.hmdp.shop.config;

import com.hmdp.utils.MqConstants;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
    // 1. 定义交换机 (Fanout 模式)
    @Bean
    public FanoutExchange shopCacheExchange() {
        return new FanoutExchange(MqConstants.SHOP_CACHE_EXCHANGE);
    }

    // 2. 定义匿名队列：每个微服务实例启动都会创建一个名字随机、自动删除的独立队列
    @Bean
    public Queue shopCacheQueue() {
        return new AnonymousQueue();
    }

    // 3. 将自己的匿名队列绑定到交换机上
    @Bean
    public Binding shopCacheBinding(Queue shopCacheQueue, FanoutExchange shopCacheExchange) {
        return BindingBuilder.bind(shopCacheQueue).to(shopCacheExchange);
    }
}