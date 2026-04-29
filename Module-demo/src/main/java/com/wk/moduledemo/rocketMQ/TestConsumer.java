package com.wk.moduledemo.rocketMQ;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "TestTopic",                  // 订阅的主题
        consumerGroup = "test-consumer-group" // 消费者组
)
public class TestConsumer implements RocketMQListener<String> {

    @Override
    public void onMessage(String message) {
        System.out.println("【测试消费】收到消息: " + message);
    }
}