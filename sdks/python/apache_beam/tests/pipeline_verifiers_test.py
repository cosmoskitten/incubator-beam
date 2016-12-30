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

"""Unit tests for the test pipeline verifiers"""

import logging
import unittest

from apache_beam.runners.runner import PipelineState
from apache_beam.runners.runner import PipelineResult
from apache_beam.tests.pipeline_verifiers import PipelineStateMatcher
from hamcrest import assert_that as hc_assert_that


class PipelineVerifiersTest(unittest.TestCase):

  def test_pipeline_state_matcher_success(self):
    """Test PipelineStateMatcher successes when using default expected state
    and job actually finished in DONE
    """
    pipeline_result = PipelineResult(PipelineState.DONE)
    hc_assert_that(pipeline_result, PipelineStateMatcher())

  def test_pipeline_state_matcher_given_state(self):
    """Test PipelineStateMatcher successes when matches given state"""
    pipeline_result = PipelineResult(PipelineState.FAILED)
    hc_assert_that(pipeline_result, PipelineStateMatcher(PipelineState.FAILED))

  def test_pipeline_state_matcher_fails(self):
    """Test PipelineStateMatcher fails when using default expected state
    and job actually finished in CANCELLED/DRAINED/FAILED/STOPPED/UNKNOWN
    """
    failed_state = [PipelineState.CANCELLED,
                    PipelineState.DRAINED,
                    PipelineState.FAILED,
                    PipelineState.STOPPED,
                    PipelineState.UNKNOWN]

    for state in failed_state:
      pipeline_result = PipelineResult(state)
      with self.assertRaises(AssertionError):
        hc_assert_that(pipeline_result, PipelineStateMatcher())

if __name__ == '__main__':
  logging.getLogger().setLevel(logging.INFO)
  unittest.main()
