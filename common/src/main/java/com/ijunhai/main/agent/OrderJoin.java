package com.ijunhai.main.agent;

import com.ijunhai.common.logsystem.Monitor;
import com.ijunhai.common.offset.redisRocketMQOffset;
import com.ijunhai.process.agent.AgentProcess;
import com.ijunhai.storage.greenplum.GreenPlumSink;
import com.ijunhai.storage.kafka.KafkaSink;
import com.ijunhai.storage.kafka.Save2Kafka;
import com.ijunhai.storage.redis.RedisSink;
import com.ijunhai.storage.redis.Save2Redis;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.spark.*;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * pull rocketMQ数据到kafka中
 * Created by Admin on 2017-08-09.
 */
public class OrderJoin implements Serializable {
    private static String GROUP = "OrderJoin";

    public static void main(String[] args) throws InterruptedException {
//        System.setProperty("hadoop.home.dir", PropertiesUtils.get(RocketMQConstants.HADOOP_DIR));

//        spark-submit --class com.ijunhai.kafka.mq2kafka.OrderJoin --num-executors 5 --conf "spark.driver.extraJavaOptions=-Xss100m"
// --conf "spark.executor.extraJavaOptions=-Xss100m" --conf "spark.kryoserializer.buffer.max=100m"
// --conf "spark.executor.extraJavaOptions=-XX:+UseConcMarkSweepGC"
// --master yarn --deploy-mode cluster /data/data-clean.jar
// bjc-apilog.ijunhai.net:19876 YouyunOrder 60 channel_id hdfs://Ucluster/tmp/ YouyunOrder

//        hdfs://slave-02:8022/tmp/
        System.setProperty("spark.scheduler.mode", "FAIR");
        Logger.getLogger("org.apache.spark.streaming").setLevel(Level.WARN);
        Logger.getLogger("org.apache.spark.sql").setLevel(Level.WARN);
        Logger.getLogger("org.apache.spark").setLevel(Level.WARN);
        if (args.length < 5) {
            System.err.println("<SERVER:PORT> <SRCTOPIC> <SECOND> <COLUMN> <HDFS_CACHE_PATH> [DESTOPIC]");
            System.exit(1);
        }
        final String nameServer = args[0];
        String topicsStr = args[1];
        String second = args[2];
        String column = args[3];
        String hdfsCachePath = args[4];
        String desTopic = topicsStr;
        String ipPath = args[6];

        if (args.length == 7)
            desTopic = args[5];

        if (nameServer == null || nameServer.equals("") || topicsStr == null || topicsStr.equals("") || second == null || second.equals("")) {
            System.err.println("<SERVER:PORT> <SRCTOPIC> <SECOND> <COLUMN> <HDFS_CACHE_PATH> [DESTOPIC]");
            System.exit(1);
        }


        Map<String, String> optionParams = new HashMap<String, String>();
        optionParams.put(RocketMQConfig.NAME_SERVER_ADDR, nameServer);
        optionParams.put(RocketMQConfig.PULL_TIMEOUT_MS, "60000");
//        optionParams.put(RocketMQConfig.CONSUMER_GROUP,"testOrder");
        String uuid = UUID.randomUUID().toString();
        String appName = nameServer + ":OrderJoin";
        SparkConf sparkConf = new SparkConf().setAppName(appName);
//                .setMaster("local[*]");

        JavaStreamingContext sc = new JavaStreamingContext(sparkConf, new Duration(Integer.parseInt(second) * 1000));
        Broadcast kafkaSinkBroadcast = sc.sparkContext().broadcast(KafkaSink.apply(Save2Kafka.brokers()));
        Broadcast GPSink = sc.sparkContext().broadcast(GreenPlumSink.apply(Monitor.database()));

        final List<String> topics = new ArrayList<String>();
        Collections.addAll(topics, topicsStr.split(","));
        LocationStrategy locationStrategy = LocationStrategy.PreferConsistent();
//        JedisCluster redisConn = RedisClient.getInstatnce().getJedis();
        Broadcast redisSinkCluster = sc.sparkContext().broadcast(RedisSink.apply());

//        assert redisConn != null;
        Map<MessageQueue, Object> queueToOffset = new HashMap<MessageQueue, Object>();
        for (String topic : topics) {
            queueToOffset = redisRocketMQOffset.readOffset(nameServer, topic, GROUP, redisSinkCluster);
        }


        JavaInputDStream<MessageExt> dStream;

        if (!queueToOffset.isEmpty()) {
            System.out.println(queueToOffset);
            System.out.println("offset");
            dStream = RocketMqUtils.createJavaMQPullStream(sc, GROUP, topics,
                    ConsumerStrategy.specificOffset(queueToOffset), false, true, true, locationStrategy, optionParams);
        } else {
            System.out.println("earliest");
            dStream = RocketMqUtils.createJavaMQPullStream(sc, GROUP, topics,
                    ConsumerStrategy.lastest(), false, false, true, locationStrategy, optionParams);
        }

        final AtomicReference<Map<TopicQueueId, OffsetRange[]>> offsetRanges = new AtomicReference<Map<TopicQueueId, OffsetRange[]>>();

        String finalDesTopic = desTopic;

        dStream.transform(new Function<JavaRDD<MessageExt>, JavaRDD<MessageExt>>() {
            public JavaRDD<MessageExt> call(JavaRDD<MessageExt> v1) throws Exception {
                Map<TopicQueueId, OffsetRange[]> offsets = ((HasOffsetRanges) v1.rdd()).offsetRanges();
                offsetRanges.set(offsets);
                ((CanCommitOffsets) dStream.inputDStream()).commitAsync(offsetRanges.get());
                return v1;
            }
        }).foreachRDD(new VoidFunction<JavaRDD<MessageExt>>() {
            public void call(JavaRDD<MessageExt> messageExtJavaRDD) throws Exception {
                if (Save2Redis.isRunning(appName, uuid, Integer.parseInt(second), redisSinkCluster)) {
                    System.out.println(appName + " is running and the uuid is " + uuid);
                    System.exit(1);
                }
                System.out.println(System.currentTimeMillis());
                AgentProcess.orderJoin(messageExtJavaRDD.rdd(), kafkaSinkBroadcast, GPSink, column, finalDesTopic, hdfsCachePath, offsetRanges.get(), nameServer, topics, GROUP, redisSinkCluster, ipPath);
            }
        });
        sc.start();
        sc.awaitTermination();
    }
}
