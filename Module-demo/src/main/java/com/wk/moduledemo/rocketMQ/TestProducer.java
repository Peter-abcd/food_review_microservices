package com.wk.moduledemo.rocketMQ;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.Resource;

@RestController
public class TestProducer {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @GetMapping("/test/send")
    public String send() {
        // 发送到 TestTopic，消息内容是 "Hello RocketMQ!"
        rocketMQTemplate.convertAndSend("TestTopic", "Hello RocketMQ!");
        return "发送成功";
    }
}