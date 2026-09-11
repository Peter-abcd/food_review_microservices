package com.wk.moduledemo.rocketMQ;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class TestConsumer {

    @RabbitListener(queues = "TestTopic")
    public void onMessage(String message) {
        System.out.println("【测试消费】收到消息: " + message);
    }
}
