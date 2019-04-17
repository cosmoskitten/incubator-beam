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

"""Unit tests for cross-language parquet io read/write."""

from __future__ import absolute_import
from __future__ import print_function

import logging
import os
import re
import unittest

from nose.plugins.attrib import attr

import apache_beam as beam
from apache_beam import coders
from apache_beam.coders.avro_record import AvroRecord
from apache_beam.options.pipeline_options import DebugOptions
from apache_beam.testing.test_pipeline import TestPipeline

PARQUET_WRITE_URN = "beam:transforms:xlang:parquet_write"


@attr('UsesCrossLanguageTransforms')
@unittest.skipUnless(
    os.environ.get('EXPANSION_JAR'),
    "EXPANSION_JAR environment variable is not set.")
@unittest.skipUnless(
    os.environ.get('EXPANSION_PORT'),
    "EXPANSION_PORT environment var is not provided.")
class XlangParquetIOTest(unittest.TestCase):
  # TODO: add verification for the file written by external transform
  #  after fixing BEAM-7612
  def test_write(self):
    expansion_jar = os.environ.get('EXPANSION_JAR')
    port = os.environ.get('EXPANSION_PORT')
    address = 'localhost:%s' % port
    try:
      test_pipeline = TestPipeline()
      test_pipeline.get_pipeline_options().view_as(
          DebugOptions).experiments.append('jar_packages='+expansion_jar)
      test_pipeline.not_use_test_runner_api = True
      with test_pipeline as p:
        _ = p \
          | beam.Create([
              AvroRecord({"name": "abc"}), AvroRecord({"name": "def"}),
              AvroRecord({"name": "ghi"})]) \
          | beam.ExternalTransform(
              PARQUET_WRITE_URN, b'/tmp/test.parquet', address)
    except RuntimeError as e:
      if re.search(PARQUET_WRITE_URN, str(e)):
        print("looks like URN not implemented in expansion service, skipping.")
      else:
        raise e


class AvroTestCoder(coders.AvroCoder):
  SCHEMA = """
  {
    "type": "record", "name": "testrecord",
    "fields": [ {"name": "name", "type": "string"} ]
  }
  """

  def __init__(self):
    super(AvroTestCoder, self).__init__(self.SCHEMA)


coders.registry.register_coder(AvroRecord, AvroTestCoder)

if __name__ == '__main__':
  logging.getLogger().setLevel(logging.INFO)
  unittest.main()
