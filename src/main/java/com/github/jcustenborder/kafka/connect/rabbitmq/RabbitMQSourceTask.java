/**
 * Copyright © 2017 Jeremy Custenborder (jcustenborder@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jcustenborder.kafka.connect.rabbitmq;

import com.github.jcustenborder.kafka.connect.utils.VersionUtil;
import com.github.jcustenborder.kafka.connect.utils.data.SourceRecordConcurrentLinkedDeque;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.RecoveryListener;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RabbitMQSourceTask extends SourceTask {
  private static final Logger log = LoggerFactory.getLogger(RabbitMQSourceTask.class);
  RabbitMQSourceConnectorConfig config;
  SourceRecordConcurrentLinkedDeque records;
  ConnectConsumer consumer;
  int bindAttempts = 100;

  @Override
  public String version() {
    return VersionUtil.version(this.getClass());
  }

  Channel channel;
  Connection connection;

  @Override
  public void start(Map<String, String> settings) {
    this.config = new RabbitMQSourceConnectorConfig(settings);
    this.records = new SourceRecordConcurrentLinkedDeque();
    this.consumer = new ConnectConsumer(this.records, this.config);

    ConnectionFactory connectionFactory = this.config.connectionFactory();
    try {
      log.info("Opening connection to {}:{}/{}", this.config.host, this.config.port, this.config.virtualHost);
      this.connection = connectionFactory.newConnection();
      if (connectionFactory.isAutomaticRecoveryEnabled()) {
        ((AutorecoveringConnection) connection).addRecoveryListener(new AutoRecovery());
        log.info("AutoRecover listener added for recovery...");
      }
    } catch (IOException | TimeoutException e) {
      throw new ConnectException(e);
    }
    configConsumer(false);
  }

  class AutoRecovery implements RecoveryListener {

    @Override
    public void handleRecovery(Recoverable recoverable) {
      log.info("Recovery being handled...");
      bindAttempts = 100;
      configConsumer(true);
    }

    @Override
    public void handleRecoveryStarted(Recoverable recoverable) {
      log.info("Start recovery being called...");
    }
  }

  private void configConsumer(boolean isRecovery) {
    while (this.bindAttempts > 0) {
      log.info("Bind attempts remaining {}", bindAttempts);

      if (!isRecovery) {
        try {
          log.info("Creating Channel");
          this.channel = this.connection.createChannel();
        } catch (IOException e) {
          throw new ConnectException(e);
        }
      }

      for (String queue : this.config.queues) {
        try {
          TimeUnit.SECONDS.sleep(1);
          log.info("Starting consumer");
          if (this.config.autoCreate) {
            this.channel.queueDeclare(queue, false, false, false, null);
          }
          if (!this.config.routingKey.isEmpty()) {
            log.info("Declaring exchange: {} and binding it to queue: {}", this.config.exchange, queue);
            this.channel.exchangeDeclarePassive(this.config.exchange);
            this.channel.queueBind(queue, this.config.exchange, this.config.routingKey);
            log.info("Queue {} bound to exchange {}", queue, this.config.exchange);
          }
          this.channel.basicConsume(queue, this.consumer);
          log.info("Setting channel.basicQos({}, {});", this.config.prefetchCount, this.config.prefetchGlobal);
          this.channel.basicQos(this.config.prefetchCount, this.config.prefetchGlobal);
          this.bindAttempts = 0;
        } catch (IOException ex) {
          this.bindAttempts--;
          if (this.bindAttempts == 0) throw new ConnectException(ex);
          else configConsumer(false);
        } catch (InterruptedException ie) {
          log.info("Thread Interrupted..");
        }
      }
    }
  }

  @Override
  public void commitRecord(SourceRecord record) throws InterruptedException {
    Long deliveryTag = (Long) record.sourceOffset().get("deliveryTag");
    try {
      this.channel.basicAck(deliveryTag, false);
    } catch (IOException e) {
      throw new RetriableException(e);
    }
  }

  @Override
  public List<SourceRecord> poll() throws InterruptedException {
    List<SourceRecord> batch = new ArrayList<>(4096);

    while (!this.records.drain(batch)) {
      Thread.sleep(1000);
    }

    return batch;
  }

  @Override
  public void stop() {
    try {
      this.connection.close();
    } catch (IOException e) {
      log.error("Exception thrown while closing connection.", e);
    }
  }
}
