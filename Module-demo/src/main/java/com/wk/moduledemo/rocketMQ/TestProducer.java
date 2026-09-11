package com.wk.moduledemo.rocketMQ;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.Resource;

@RestController
public class TestProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @GetMapping("/test/send")
    public String send() {
        // 发送到 TestTopic，消息内容是 "Hello RabbitMQ!"
        rabbitTemplate.convertAndSend("TestTopic", "", "Hello RabbitMQ!");
        return "发送成功";
    }
}
