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
import hamcrest as hc
import unittest

import apache_beam.internal.clients.dataflow as dataflow
import apache_beam.internal.clients.dataflow.message_matchers
from apache_beam.internal.json_value import to_json_value


class TestMatchers(unittest.TestCase):

  def test_structured_name_matcher_basic(self):
    metric_name = dataflow.MetricStructuredName()
    metric_name.name = 'metric1'
    metric_name.origin = 'origin2'

    matcher = dataflow.message_matchers.MetricStructuredNameMatcher(
        name='metric1',
        origin='origin2')
    hc.assert_that(metric_name, hc.is_(matcher))
    with self.assertRaises(AssertionError):
      matcher = dataflow.message_matchers.MetricStructuredNameMatcher(
          name='metric1',
          origin='origin1')
      hc.assert_that(metric_name, hc.is_(matcher))

  def test_metric_update_basic(self):
    metric_update = dataflow.MetricUpdate()
    metric_update.name = dataflow.MetricStructuredName()
    metric_update.name.name = 'metric1'
    metric_update.name.origin = 'origin1'

    metric_update.cumulative = False
    metric_update.kind = 'sum'
    metric_update.scalar = to_json_value(1, with_type=True)

    name_matcher = dataflow.message_matchers.MetricStructuredNameMatcher(
        name='metric1',
        origin='origin1')
    matcher = dataflow.message_matchers.MetricUpdateMatcher(
        name=name_matcher,
        kind='sum',
        scalar=1)

    hc.assert_that(metric_update, hc.is_(matcher))

    with self.assertRaises(AssertionError):
      matcher.kind = 'suma'
      hc.assert_that(metric_update, hc.is_(matcher))


if __name__ == '__main__':
  unittest.main()
