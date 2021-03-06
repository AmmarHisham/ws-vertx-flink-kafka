package com.aman.kafkalink;

import com.aman.kafkalink.config.FlinkKafkaConsumerConfig;
import com.aman.kafkalink.config.FlinkKafkaProducerConfig;
import com.aman.kafkalink.entity.RegisterRequest;
import com.aman.kafkalink.entity.RegisterRequestSchema;
import com.aman.kafkalink.entity.RegisterResponse;
import com.aman.kafkalink.entity.RegisterResponseSerializer;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.async.AsyncFunction;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer010;
import org.apache.flink.util.Collector;
import org.apache.log4j.Logger;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class FlinkReadFromKafka {

	private static final Logger logger = Logger.getLogger(FlinkReadFromKafka.class);
	
	public static void main(String[] args) throws Exception {
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setParallelism(1);
		Properties consumerProp = FlinkKafkaConsumerConfig.getKafkaConsumerConfig();

		// Create a flink consumer from the topic with a custom serializer for "RegisterRequest"
		FlinkKafkaConsumer010<RegisterRequest> consumer = new FlinkKafkaConsumer010<>(consumerProp.getProperty(
				"topic"),
				new RegisterRequestSchema(), consumerProp);

		// Start reading partitions from the consumer group’s committed offsets in Kafka brokers
		consumer.setStartFromGroupOffsets();

		// Create a flink data stream from the consumer source i.e Kafka topic
		DataStream<RegisterRequest> messageStream = env.addSource(consumer);

		logger.info(messageStream.process(new ProcessFunction<RegisterRequest, Object>() {
			@Override
			public void processElement(RegisterRequest RegisterRequest, Context context, Collector<Object> collector) throws Exception {
				logger.info("Processing incoming request " + RegisterRequest);
			}
		}));

		//Set default timeout for the api. Ideally this should be fetched from a config server
		Integer apiTimeoutMs = 5000;

		//Function that defines how a datastream object would be transformed from within flink
		AsyncFunction<RegisterRequest, RegisterResponse> loginRestTransform =
				new AsyncRegisterApiInvocation(apiTimeoutMs);

		//Transform the datastream in parallel
		DataStream<RegisterResponse> result = AsyncDataStream
				.unorderedWait(messageStream, loginRestTransform, apiTimeoutMs, TimeUnit.MILLISECONDS, 1)
				.setParallelism(1);

		Properties producerProp = FlinkKafkaProducerConfig.getKafkaProduerConfig();

		//Write the result back to the Kafka sink i.e response topic
		result.addSink(new FlinkKafkaProducer010<>(producerProp.getProperty("topic"), new RegisterResponseSerializer(),
				producerProp));
		env.execute();
	}


}
