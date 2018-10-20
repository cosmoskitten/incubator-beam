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
from __future__ import absolute_import

import sys
import os
import json
import tempfile
import unittest

from apache_beam import Create
from apache_beam import Map
from apache_beam.io import filebasedsource
from apache_beam.io import source_test_utils
from apache_beam.io.parquetio import WriteToParquet
from apache_beam.io.parquetio import ReadFromParquet
from apache_beam.io.parquetio import ReadAllFromParquet
from apache_beam.io.parquetio import _create_parquet_source
from apache_beam.io.parquetio import _create_parquet_sink
from apache_beam.testing.test_pipeline import TestPipeline
from apache_beam.testing.util import assert_that
from apache_beam.testing.util import equal_to
from apache_beam.transforms.display import DisplayData
from apache_beam.transforms.display_test import DisplayDataItemMatcher

import pyarrow as pa
import pyarrow.parquet as pq
import hamcrest as hc


class TestParquet(unittest.TestCase):
  _temp_files = []

  @classmethod
  def setUpClass(cls):
    # Method has been renamed in Python 3
    if sys.version_info[0] < 3:
      cls.assertCountEqual = cls.assertItemsEqual

  def setUp(self):
    # Reducing the size of thread pools. Without this test execution may fail in
    # environments with limited amount of resources.
    filebasedsource.MAX_NUM_THREADS_FOR_SIZE_ESTIMATION = 2

  def tearDown(self):
    for path in self._temp_files:
      if os.path.exists(path):
        os.remove(path)
        parent = os.path.dirname(path)
        if not os.listdir(parent):
          os.rmdir(parent)
    self._temp_files = []

  RECORDS = [{'name': 'Thomas',
              'favorite_number': 1,
              'favorite_color': 'blue'}, {'name': 'Henry',
                                          'favorite_number': 3,
                                          'favorite_color': 'green'},
             {'name': 'Toby',
              'favorite_number': 7,
              'favorite_color': 'brown'}, {'name': 'Gordon',
                                           'favorite_number': 4,
                                           'favorite_color': 'blue'},
             {'name': 'Emily',
              'favorite_number': -1,
              'favorite_color': 'Red'}, {'name': 'Percy',
                                         'favorite_number': 6,
                                         'favorite_color': 'Green'}]

  SCHEMA = pa.schema([
      ('name', pa.binary()),
      ('favorite_number', pa.int64()),
      ('favorite_color', pa.binary())
  ])

  def _record_to_columns(self, records, schema):
    col_list = []
    for n in schema.names:
      column = []
      for r in records:
        column.append(r[n])
      col_list.append(column)
    return col_list

  def _write_data(self,
                  directory=None,
                  prefix=tempfile.template,
                  row_group_size=1000,
                  codec='none',
                  count=len(RECORDS)):

    with tempfile.NamedTemporaryFile(
        delete=False, dir=directory, prefix=prefix) as f:
      len_records = len(self.RECORDS)
      data = []
      for i in range(count):
        data.append(self.RECORDS[i % len_records])
      col_data = self._record_to_columns(data, self.SCHEMA)
      col_array = map(pa.array, col_data)
      table = pa.Table.from_arrays(col_array, self.SCHEMA.names)
      pq.write_table(table, f, row_group_size=row_group_size, compression=codec)

      self._temp_files.append(f.name)
      return f.name

  def _write_pattern(self, num_files):
    assert num_files > 0
    temp_dir = tempfile.mkdtemp()

    file_name = None
    for _ in range(num_files):
      file_name = self._write_data(directory=temp_dir, prefix='mytemp')

    assert file_name
    file_name_prefix = file_name[:file_name.rfind(os.path.sep)]
    return file_name_prefix + os.path.sep + 'mytemp*'

  def _run_parquet_test(self, pattern, expected_result):
    source = _create_parquet_source(pattern)
    read_records = source_test_utils.read_from_source(source, None, None)
    self.assertCountEqual(expected_result, read_records)

  def test_read(self):
    file_name = self._write_data()
    expected_result = self.RECORDS
    self._run_parquet_test(file_name, expected_result)

  def test_source_display_data(self):
    file_name = 'some_parquet_source'
    source = \
        _create_parquet_source(
            file_name,
            validate=False
        )
    dd = DisplayData.create_from(source)

    expected_items = [
        DisplayDataItemMatcher('compression', 'auto'),
        DisplayDataItemMatcher('file_pattern', file_name)]
    hc.assert_that(dd.items, hc.contains_inanyorder(*expected_items))

  def test_read_display_data(self):
    file_name = 'some_parquet_source'
    read = \
      ReadFromParquet(
          file_name,
          validate=False)
    dd = DisplayData.create_from(read)

    expected_items = [
        DisplayDataItemMatcher('compression', 'auto'),
        DisplayDataItemMatcher('file_pattern', file_name)]
    hc.assert_that(dd.items, hc.contains_inanyorder(*expected_items))

  def test_sink_display_data(self):
    file_name = 'some_parquet_sink'
    sink = _create_parquet_sink(
        file_name,
        self.SCHEMA,
        'none',
        1,
        '.end',
        0,
        None,
        'application/x-parquet')
    dd = DisplayData.create_from(sink)
    expected_items = [
        DisplayDataItemMatcher(
            'schema',
            str(self.SCHEMA)),
        DisplayDataItemMatcher(
            'file_pattern',
            'some_parquet_sink-%(shard_num)05d-of-%(num_shards)05d.end'),
        DisplayDataItemMatcher(
            'codec',
            'none'),
        DisplayDataItemMatcher(
            'compression',
            'uncompressed')]
    hc.assert_that(dd.items, hc.contains_inanyorder(*expected_items))

  def test_write_display_data(self):
    file_name = 'some_parquet_sink'
    write = WriteToParquet(file_name, self.SCHEMA)
    dd = DisplayData.create_from(write)
    expected_items = [
        DisplayDataItemMatcher(
            'schema',
            str(self.SCHEMA)),
        DisplayDataItemMatcher(
            'file_pattern',
            'some_parquet_sink-%(shard_num)05d-of-%(num_shards)05d'),
        DisplayDataItemMatcher(
            'codec',
            'none'),
        DisplayDataItemMatcher(
            'compression',
            'uncompressed')]
    hc.assert_that(dd.items, hc.contains_inanyorder(*expected_items))

  def test_sink_transform(self):
    with tempfile.NamedTemporaryFile() as dst:
      path = dst.name
      with TestPipeline() as p:
        # pylint: disable=expression-not-assigned
        p \
        | Create(self.RECORDS) \
        | WriteToParquet(
            path, self.SCHEMA, num_shards=1, shard_name_template='')
      with TestPipeline() as p:
        # json used for stable sortability
        readback = \
            p \
            | ReadFromParquet(path) \
            | Map(json.dumps)
        assert_that(readback, equal_to([json.dumps(r) for r in self.RECORDS]))

  def test_sink_transform_snappy(self):
    with tempfile.NamedTemporaryFile() as dst:
      path = dst.name
      with TestPipeline() as p:
        # pylint: disable=expression-not-assigned
        p \
        | Create(self.RECORDS) \
        | WriteToParquet(
            path, self.SCHEMA, codec='snappy',
            num_shards=1, shard_name_template='')
      with TestPipeline() as p:
        # json used for stable sortability
        readback = \
            p \
            | ReadFromParquet(path + '*') \
            | Map(json.dumps)
        assert_that(readback, equal_to([json.dumps(r) for r in self.RECORDS]))

  def test_read_reentrant(self):
    file_name = self._write_data()
    source = _create_parquet_source(file_name)
    source_test_utils.assert_reentrant_reads_succeed((source, None, None))

  def test_read_multiple_row_group(self):
    file_name = self._write_data(count=12000)
    expected_result = self.RECORDS * 2000
    self._run_parquet_test(file_name, expected_result)

  def test_sink_transform_multiple_row_group(self):
    with tempfile.NamedTemporaryFile() as dst:
      path = dst.name
      with TestPipeline() as p:
        # pylint: disable=expression-not-assigned
        p \
        | Create(self.RECORDS * 2000) \
        | WriteToParquet(
            path, self.SCHEMA, num_shards=1, codec='snappy',
            shard_name_template='', row_group_size=3000)
      self.assertEqual(pq.read_metadata(path).num_row_groups, 4)

  def test_read_all_from_parquet_single_file(self):
    path = self._write_data()
    with TestPipeline() as p:
      assert_that(
          p \
          | Create([path]) \
          | ReadAllFromParquet(),
          equal_to(self.RECORDS))

  def test_read_all_from_parquet_many_single_files(self):
    path1 = self._write_data()
    path2 = self._write_data()
    path3 = self._write_data()
    with TestPipeline() as p:
      assert_that(
          p \
          | Create([path1, path2, path3]) \
          | ReadAllFromParquet(),
          equal_to(self.RECORDS * 3))

  def test_read_all_from_parquet_file_pattern(self):
    file_pattern = self._write_pattern(5)
    with TestPipeline() as p:
      assert_that(
          p \
          | Create([file_pattern]) \
          | ReadAllFromParquet(),
          equal_to(self.RECORDS * 5))

  def test_read_all_from_parquet_many_file_patterns(self):
    file_pattern1 = self._write_pattern(5)
    file_pattern2 = self._write_pattern(2)
    file_pattern3 = self._write_pattern(3)
    with TestPipeline() as p:
      assert_that(
          p \
          | Create([file_pattern1, file_pattern2, file_pattern3]) \
          | ReadAllFromParquet(),
          equal_to(self.RECORDS * 10))
