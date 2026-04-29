package com.wk.moduledemo.rocketMQ;

import java.io.IOException;
import java.util.Collections;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//public class PushConsumerExample {
//    private static final Logger logger = LoggerFactory.getLogger(PushConsumerExample.class);
//
//    private PushConsumerExample() {
//    }
//
//    public static void main(String[] args) throws ClientException, IOException, InterruptedException {
//        final ClientServiceProvider provider = ClientServiceProvider.loadService();
//        // 接入点地址，需要设置成Proxy的地址和端口列表，一般是xxx:8080;xxx:8081
//        String endpoints = "localhost:9876";  // NameServer 地址
//        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
//                .setEndpoints(endpoints)
//                .build();
//        // 订阅消息的过滤规则，表示订阅所有Tag的消息。
//        String tag = "*";
//        FilterExpression filterExpression = new FilterExpression(tag, FilterExpressionType.TAG);
//        // 为消费者指定所属的消费者分组，Group需要提前创建。
//        String consumerGroup = "YourConsumerGroup";
//        // 指定需要订阅哪个目标Topic，Topic需要提前创建。
//        String topic = "TestTopic";
//        // 初始化PushConsumer，需要绑定消费者分组ConsumerGroup、通信参数以及订阅关系。
//        PushConsumer pushConsumer = provider.newPushConsumerBuilder()
//                .setClientConfiguration(
//                        ClientConfiguration.newBuilder()
//                                .setEndpoints("localhost:9876")  // 直连 NameServer
//                                .build()
//                )
//                .setConsumerGroup("YourConsumerGroup")
//                .setSubscriptionExpressions(Collections.singletonMap("TestTopic", new FilterExpression("*", FilterExpressionType.TAG)))
//                .setMessageListener(messageView -> {
//                    System.out.println("Consume message successfully, messageId=" + messageView.getMessageId());
//                    return ConsumeResult.SUCCESS;
//                })
//                .build();
//
//        Thread.sleep(Long.MAX_VALUE);
//        // 如果不需要再使用 PushConsumer，可关闭该实例。
//        // pushConsumer.close();
//    }
//}

public class PushConsumerExample {
    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("YourConsumerGroup");
        consumer.setNamesrvAddr("localhost:9876"); // 这里可以直接连 9876
        consumer.subscribe("TestTopic", "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            System.out.printf("%s Receive New Messages: %s %n", Thread.currentThread().getName(), msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        System.out.println("Consumer Started.");
    }
}
