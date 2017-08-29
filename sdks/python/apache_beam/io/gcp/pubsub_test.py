#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""Unit tests for PubSub sources and sinks."""

import logging
import unittest

import hamcrest as hc

from apache_beam.io.gcp.pubsub import (ReadStringsFromPubSub,
                                       WriteStringsToPubSub, _PubSubPayloadSink,
                                       _PubSubPayloadSource)
from apache_beam.testing.test_pipeline import TestPipeline
from apache_beam.transforms.display import DisplayData
from apache_beam.transforms.display_test import DisplayDataItemMatcher

# Protect against environments where the PubSub library is not available.
# pylint: disable=wrong-import-order, wrong-import-position
try:
  from google.cloud import pubsub
except ImportError:
  pubsub = None
# pylint: enable=wrong-import-order, wrong-import-position


@unittest.skipIf(pubsub is None, 'GCP dependencies are not installed')
class TestReadStringsFromPubSub(unittest.TestCase):
  def test_expand_with_topic(self):
    p = TestPipeline()
    pcoll = p | ReadStringsFromPubSub('projects/fakeprj/topics/a_topic',
                                      None, 'a_label')
    # Ensure that the output type is str
    self.assertEqual(unicode, pcoll.element_type)

    # Ensure that the properties passed through correctly
    source = pcoll.producer.transform._source
    self.assertEqual('a_topic', source.topic_name)
    self.assertEqual('a_label', source.id_label)

  def test_expand_with_subscription(self):
    p = TestPipeline()
    pcoll = p | ReadStringsFromPubSub(
        None, 'projects/fakeprj/subscriptions/a_subscription', 'a_label')
    # Ensure that the output type is str
    self.assertEqual(unicode, pcoll.element_type)

    # Ensure that the properties passed through correctly
    source = pcoll.producer.transform._source
    self.assertEqual('a_subscription', source.subscription_name)
    self.assertEqual('a_label', source.id_label)

  def test_expand_with_no_topic_or_subscription(self):
    with self.assertRaisesRegexp(
        ValueError, "Either a topic or subscription must be provided."):
      ReadStringsFromPubSub(None, None, 'a_label')

  def test_expand_with_both_topic_and_subscription(self):
    with self.assertRaisesRegexp(
        ValueError, "Only one of topic or subscription should be provided."):
      ReadStringsFromPubSub('a_topic', 'a_subscription', 'a_label')


@unittest.skipIf(pubsub is None, 'GCP dependencies are not installed')
class TestWriteStringsToPubSub(unittest.TestCase):
  def test_expand(self):
    p = TestPipeline()
    pdone = (p
             | ReadStringsFromPubSub('projects/fakeprj/topics/baz')
             | WriteStringsToPubSub('projects/fakeprj/topics/a_topic'))

    # Ensure that the properties passed through correctly
    self.assertEqual('a_topic', pdone.producer.transform.dofn.topic_name)


@unittest.skipIf(pubsub is None, 'GCP dependencies are not installed')
class TestPubSubSource(unittest.TestCase):
  def test_display_data_topic(self):
    source = _PubSubPayloadSource(
        'projects/fakeprj/topics/a_topic',
        None,
        'a_label')
    dd = DisplayData.create_from(source)
    expected_items = [
        DisplayDataItemMatcher(
            'topic', 'projects/fakeprj/topics/a_topic'),
        DisplayDataItemMatcher('id_label', 'a_label')]

    hc.assert_that(dd.items, hc.contains_inanyorder(*expected_items))

  def test_display_data_subscription(self):
    source = _PubSubPayloadSource(
        None,
        'projects/fakeprj/subscriptions/a_subscription',
        'a_label')
    dd = DisplayData.create_from(source)
    expected_items = [
        DisplayDataItemMatcher(
            'subscription', 'projects/fakeprj/subscriptions/a_subscription'),
        DisplayDataItemMatcher('id_label', 'a_label')]

    hc.assert_that(dd.items, hc.contains_inanyorder(*expected_items))

  def test_display_data_no_subscription(self):
    source = _PubSubPayloadSource('projects/fakeprj/topics/a_topic')
    dd = DisplayData.create_from(source)
    expected_items = [
        DisplayDataItemMatcher('topic', 'projects/fakeprj/topics/a_topic')]

    hc.assert_that(dd.items, hc.contains_inanyorder(*expected_items))


@unittest.skipIf(pubsub is None, 'GCP dependencies are not installed')
class TestPubSubSink(unittest.TestCase):
  def test_display_data(self):
    sink = _PubSubPayloadSink('projects/fakeprj/topics/a_topic')
    dd = DisplayData.create_from(sink)
    expected_items = [
        DisplayDataItemMatcher('topic', 'projects/fakeprj/topics/a_topic')]

    hc.assert_that(dd.items, hc.contains_inanyorder(*expected_items))


if __name__ == '__main__':
  logging.getLogger().setLevel(logging.INFO)
  unittest.main()
