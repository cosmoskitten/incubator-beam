/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.integration.nexmark;

import com.google.cloud.dataflow.sdk.io.PubsubGrpcClient;
import com.google.cloud.dataflow.sdk.options.GcpOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Helper for working with pubsub.
 */
public class PubsubHelper {
  /**
   * Underlying pub/sub client.
   */
  private final PubsubGrpcClient pubsubClient;

  /**
   * Project id.
   */
  private final String project;

  /**
   * Topics we should delete on close.
   */
  private final List<String> createdTopics;

  /**
   * Subscriptions we should delete on close.
   */
  private final List<String> createdSubscriptions;

  private PubsubHelper(PubsubGrpcClient pubsubClient, String project) {
    this.pubsubClient = pubsubClient;
    this.project = project;
    createdTopics = new ArrayList<>();
    createdSubscriptions = new ArrayList<>();
  }

  /**
   * Create a helper.
   */
  public static PubsubHelper create(GcpOptions options, String project) throws IOException {
    return new PubsubHelper(PubsubGrpcClient.newClient(null, null, options), project);
  }

  /**
   * Return full topic name corresponding to short topic name.
   */
  private String fullTopic(String shortTopic) {
    return String.format("projects/%s/topics/%s", project, shortTopic);
  }

  /**
   * Return full subscription name corresponding to short subscription name.
   */
  private String fullSubscription(String shortSubscription) {
    return String.format("projects/%s/subscriptions/%s", project, shortSubscription);
  }

  /**
   * Create a topic from short name. Delete it if it already exists. Ensure the topic will be
   * deleted on cleanup. Return full topic name.
   *
   * @throws InterruptedException
   */
  public String createTopic(String shortTopic) throws IOException, InterruptedException {
    String topic = fullTopic(shortTopic);
    if (topicExists(topic)) {
      NexmarkUtils.console(null, "attempting to cleanup topic %s", topic);
      pubsubClient.deleteTopic(topic);
    }
    NexmarkUtils.console(null, "create topic %s", topic);
    pubsubClient.createTopic(topic);
    createdTopics.add(topic);
    return topic;
  }

  /**
   * Create a topic from short name if it does not already exist. The topic will not be
   * deleted on cleanup. Return full topic name.
   *
   * @throws InterruptedException
   */
  public String createOrReuseTopic(String shortTopic) throws IOException, InterruptedException {
    String topic = fullTopic(shortTopic);
    if (topicExists(topic)) {
      NexmarkUtils.console(null, "topic %s already exists", topic);
      return topic;
    }
    NexmarkUtils.console(null, "create topic %s", topic);
    pubsubClient.createTopic(topic);
    return topic;
  }

  /**
   * Check a topic corresponding to short name exists, and throw exception if not. The
   * topic will not be deleted on cleanup. Return full topic name.
   */
  public String reuseTopic(String shortTopic) throws IOException {
    String topic = fullTopic(shortTopic);
    if (topicExists(shortTopic)) {
      NexmarkUtils.console(null, "reusing existing topic %s", topic);
      return topic;
    }
    throw new RuntimeException("topic '" + topic + "' does not already exist");
  }

  /**
   * Does topic corresponding to short name exist?
   */
  public boolean topicExists(String shortTopic) throws IOException {
    String topic = fullTopic(shortTopic);
    Collection<String> existingTopics = pubsubClient.listTopics("projects/" + project);
    return existingTopics.contains(topic);
  }

  /**
   * Create subscription from short name. Delete subscription if it already exists. Ensure the
   * subscription will be deleted on cleanup. Return full subscription name.
   *
   * @throws InterruptedException
   */
  public String createSubscription(String shortTopic, String shortSubscription)
      throws IOException, InterruptedException {
    String topic = fullTopic(shortTopic);
    String subscription = fullSubscription(shortSubscription);
    if (subscriptionExists(shortTopic, shortSubscription)) {
      NexmarkUtils.console(null, "attempting to cleanup subscription %s", subscription);
      pubsubClient.deleteSubscription(subscription);
    }
    NexmarkUtils.console(null, "create subscription %s", subscription);
    pubsubClient.createSubscription(subscription, topic, 60);
    createdSubscriptions.add(subscription);
    return subscription;
  }

  /**
   * Check a subscription corresponding to short name exists, and throw exception if not. The
   * subscription will not be deleted on cleanup. Return full topic name.
   */
  public String reuseSubscription(String shortTopic, String shortSubscription) throws IOException {
    String subscription = fullSubscription(shortSubscription);
    if (subscriptionExists(shortTopic, shortSubscription)) {
      NexmarkUtils.console(null, "reusing existing subscription %s", subscription);
      return subscription;
    }
    throw new RuntimeException("subscription'" + subscription + "' does not already exist");
  }

  /**
   * Does subscription corresponding to short name exist?
   */
  public boolean subscriptionExists(String shortTopic, String shortSubscription)
      throws IOException {
    String topic = fullTopic(shortTopic);
    String subscription = fullSubscription(shortSubscription);
    Collection<String> existingSubscriptions =
        pubsubClient.listSubscriptions("projects/" + project, topic);
    return existingSubscriptions.contains(subscription);
  }

  /**
   * Delete all the subscriptions and topics we created.
   */
  public void cleanup() {
    for (String subscription : createdSubscriptions) {
      try {
        NexmarkUtils.console(null, "delete subscription %s", subscription);
        pubsubClient.deleteSubscription(subscription);
      } catch (IOException ex) {
        NexmarkUtils.console(null, "could not delete subscription %s", subscription);
      }
    }
    for (String topic : createdTopics) {
      try {
        NexmarkUtils.console(null, "delete topic %s", topic);
        pubsubClient.deleteTopic(topic);
      } catch (IOException ex) {
        NexmarkUtils.console(null, "could not delete topic %s", topic);
      }
    }
  }
}
