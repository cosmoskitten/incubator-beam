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

"""Unittest for GCP Bigtable testing."""
from __future__ import absolute_import

import datetime
import logging
import random
import string
import unittest
import uuid

import pytz

import apache_beam as beam
from apache_beam.io.gcp.bigtableio import _BigTableWriteFn
from apache_beam.metrics.metric import MetricsFilter
from apache_beam.options.pipeline_options import PipelineOptions
from apache_beam.runners.runner import PipelineState
from apache_beam.testing.test_pipeline import TestPipeline

# Protect against environments where bigtable library is not available.
# pylint: disable=wrong-import-order, wrong-import-position
try:
  from google.cloud._helpers import _datetime_from_microseconds
  from google.cloud._helpers import _microseconds_from_datetime
  from google.cloud._helpers import UTC
  from google.cloud.bigtable import row, column_family, Client
except ImportError:
  Client = None
  UTC = pytz.utc
  _microseconds_from_datetime = lambda label_stamp: label_stamp
  _datetime_from_microseconds = lambda micro: micro


EXISTING_INSTANCES = []
LABEL_KEY = u'python-bigtable-beam'
label_stamp = datetime.datetime.utcnow().replace(tzinfo=UTC)
label_stamp_micros = _microseconds_from_datetime(label_stamp)
LABELS = {LABEL_KEY: str(label_stamp_micros)}


def _retry_on_unavailable(exc):
  """Retry only errors whose status code is 'UNAVAILABLE'."""
  from grpc import StatusCode
  return exc.code() == StatusCode.UNAVAILABLE


class WriteToBigTable(beam.PTransform):
  """ Generates an iterator of DirectRow object to process on beam pipeline.

  """
  def __init__(self, number, project_id=None, instance_id=None,
               table_id=None):
    super(WriteToBigTable, self).__init__()
    self.number = number
    self.rand = random.choice(string.ascii_letters + string.digits)
    self.column_family_id = 'cf1'
    self.beam_options = {'project_id': project_id,
                         'instance_id': instance_id,
                         'table_id': table_id}

  def _generate(self):
    value = ''.join(self.rand for i in range(100))

    for index in range(self.number):
      key = "beam_key%s" % ('{0:07}'.format(index))
      direct_row = row.DirectRow(row_key=key)
      for column_id in range(10):
        direct_row.set_cell(self.column_family_id,
                            ('field%s' % column_id).encode('utf-8'),
                            value,
                            datetime.datetime.now())
      yield direct_row

  def expand(self, pvalue):
    return (pvalue
            | beam.Create(self._generate())
            | 'Write to BT' >> beam.ParDo(_BigTableWriteFn(self.beam_options)))


@unittest.skipIf(Client is None, 'GCP Bigtable dependencies are not installed')
class BigtableIOWriteIT(unittest.TestCase):
  """ Bigtable Write Connector Test

  """
  DEFAULT_TABLE_PREFIX = "python-test"
  instance_id = DEFAULT_TABLE_PREFIX + "-" + str(uuid.uuid4())[:8]
  cluster_id = DEFAULT_TABLE_PREFIX + "-" + str(uuid.uuid4())[:8]
  table_id = DEFAULT_TABLE_PREFIX + "-" + str(uuid.uuid4())[:8]
  number = 500
  LOCATION_ID = "us-east1-b"

  def setUp(self):
    try:
      from google.cloud.bigtable import enums
      self.STORAGE_TYPE = enums.StorageType.HDD
      self.INSTANCE_TYPE = enums.Instance.Type.DEVELOPMENT
    except ImportError:
      self.STORAGE_TYPE = 2
      self.INSTANCE_TYPE = 2

    self.test_pipeline = TestPipeline(is_integration_test=True)
    self.runner_name = type(self.test_pipeline.runner).__name__
    self.project = self.test_pipeline.get_option('project')
    self.client = Client(project=self.project, admin=True)

    self._delete_old_instances()

    self.instance = self.client.instance(self.instance_id,
                                         instance_type=self.INSTANCE_TYPE,
                                         labels=LABELS)

    if not self.instance.exists():
      cluster = self.instance.cluster(self.cluster_id,
                                      self.LOCATION_ID,
                                      default_storage_type=self.STORAGE_TYPE)
      self.instance.create(clusters=[cluster])
    self.table = self.instance.table(self.table_id)

    if not self.table.exists():
      max_versions_rule = column_family.MaxVersionsGCRule(2)
      column_family_id = 'cf1'
      column_families = {column_family_id: max_versions_rule}
      self.table.create(column_families=column_families)

  def _delete_old_instances(self):
    instances = self.client.list_instances()
    EXISTING_INSTANCES[:] = instances

    def age_in_hours(micros):
      return (datetime.datetime.utcnow().replace(tzinfo=UTC) - (
          _datetime_from_microseconds(micros))).total_seconds() // 3600
    CLEAN_INSTANCE = [i for instance in EXISTING_INSTANCES for i in instance if(
        LABEL_KEY in i.labels.keys() and
        (age_in_hours(int(i.labels[LABEL_KEY])) >= 2))]

    if CLEAN_INSTANCE:
      for instance in CLEAN_INSTANCE:
        instance.delete()

  def tearDown(self):
    if self.instance.exists():
      self.instance.delete()

  def test_bigtable_write(self):
    number = self.number
    pipeline_args = self.test_pipeline.options_list
    pipeline_options = PipelineOptions(pipeline_args)

    with beam.Pipeline(options=pipeline_options) as pipeline:
      config_data = {'project_id':self.project,
                     'instance_id':self.instance,
                     'table_id':self.table}
      _ = (
          pipeline
          | 'Generate Direct Rows' >> WriteToBigTable(number, **config_data))

      result = pipeline.run()
      result.wait_until_finish()

      assert result.state == PipelineState.DONE

      read_rows = self.table.read_rows()
      assert len([_ for _ in read_rows]) == number

      if not hasattr(result, 'has_job') or result.has_job:
        read_filter = MetricsFilter().with_name('Written Row')
        query_result = result.metrics().query(read_filter)
        if query_result['counters']:
          read_counter = query_result['counters'][0]

          logging.info('Number of Rows: %d', read_counter.committed)
          assert read_counter.committed == number


if __name__ == '__main__':
  logging.getLogger().setLevel(logging.INFO)
  unittest.main()
