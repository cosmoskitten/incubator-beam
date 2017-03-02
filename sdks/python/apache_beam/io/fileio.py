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
"""File-based sources and sinks."""

from __future__ import absolute_import

import logging
import os
import re
import time

from apache_beam.internal import util
from apache_beam.io import iobase
from apache_beam.io.filesystem import CompressionTypes
from apache_beam.io.filesystems_util import get_filesystem
from apache_beam.transforms.display import DisplayDataItem

MAX_BATCH_OPERATION_SIZE = 100
DEFAULT_SHARD_NAME_TEMPLATE = '-SSSSS-of-NNNNN'


class FileSink(iobase.Sink):
  """A sink to a GCS or local files.

  To implement a file-based sink, extend this class and override
  either ``write_record()`` or ``write_encoded_record()``.

  If needed, also overwrite ``open()`` and/or ``close()`` to customize the
  file handling or write headers and footers.

  The output of this write is a PCollection of all written shards.
  """

  # Max number of threads to be used for renaming.
  _MAX_RENAME_THREADS = 64

  def __init__(self,
               file_path_prefix,
               coder,
               file_name_suffix='',
               num_shards=0,
               shard_name_template=None,
               mime_type='application/octet-stream',
               compression_type=CompressionTypes.AUTO):
    """
     Raises:
      TypeError: if file path parameters are not a string or if compression_type
        is not member of CompressionTypes.
      ValueError: if shard_name_template is not of expected format.
    """
    if not isinstance(file_path_prefix, basestring):
      raise TypeError('file_path_prefix must be a string; got %r instead' %
                      file_path_prefix)
    if not isinstance(file_name_suffix, basestring):
      raise TypeError('file_name_suffix must be a string; got %r instead' %
                      file_name_suffix)

    if not CompressionTypes.is_valid_compression_type(compression_type):
      raise TypeError('compression_type must be CompressionType object but '
                      'was %s' % type(compression_type))

    if shard_name_template is None:
      shard_name_template = DEFAULT_SHARD_NAME_TEMPLATE
    elif shard_name_template is '':
      num_shards = 1
    self.file_path_prefix = file_path_prefix
    self.file_name_suffix = file_name_suffix
    self.num_shards = num_shards
    self.coder = coder
    self.shard_name_format = self._template_to_format(shard_name_template)
    self.compression_type = compression_type
    self.mime_type = mime_type
    self._file_system = get_filesystem(file_path_prefix)

  def display_data(self):
    return {'shards':
            DisplayDataItem(self.num_shards,
                            label='Number of Shards').drop_if_default(0),
            'compression':
            DisplayDataItem(str(self.compression_type)),
            'file_pattern':
            DisplayDataItem('{}{}{}'.format(self.file_path_prefix,
                                            self.shard_name_format,
                                            self.file_name_suffix),
                            label='File Pattern')}

  def open(self, temp_path):
    """Opens ``temp_path``, returning an opaque file handle object.

    The returned file handle is passed to ``write_[encoded_]record`` and
    ``close``.
    """
    return self._file_system.create(temp_path, self.mime_type,
                                    self.compression_type)

  def write_record(self, file_handle, value):
    """Writes a single record go the file handle returned by ``open()``.

    By default, calls ``write_encoded_record`` after encoding the record with
    this sink's Coder.
    """
    self.write_encoded_record(file_handle, self.coder.encode(value))

  def write_encoded_record(self, file_handle, encoded_value):
    """Writes a single encoded record to the file handle returned by ``open()``.
    """
    raise NotImplementedError

  def close(self, file_handle):
    """Finalize and close the file handle returned from ``open()``.

    Called after all records are written.

    By default, calls ``file_handle.close()`` iff it is not None.
    """
    if file_handle is not None:
      file_handle.close()

  def initialize_write(self):
    tmp_dir = self.file_path_prefix + self.file_name_suffix + time.strftime(
        '-temp-%Y-%m-%d_%H-%M-%S')
    self._file_system.mkdirs(tmp_dir)
    return tmp_dir

  def open_writer(self, init_result, uid):
    # A proper suffix is needed for AUTO compression detection.
    # We also ensure there will be no collisions with uid and a
    # (possibly unsharded) file_path_prefix and a (possibly empty)
    # file_name_suffix.
    suffix = (
        '.' + os.path.basename(self.file_path_prefix) + self.file_name_suffix)
    return FileSinkWriter(self, os.path.join(init_result, uid) + suffix)

  def finalize_write(self, init_result, writer_results):
    writer_results = sorted(writer_results)
    num_shards = len(writer_results)
    min_threads = min(num_shards, FileSink._MAX_RENAME_THREADS)
    num_threads = max(1, min_threads)

    rename_sources = []
    rename_destinations = []
    for shard_num, shard in enumerate(writer_results):
      final_name = ''.join([
          self.file_path_prefix, self.shard_name_format % dict(
              shard_num=shard_num, num_shards=num_shards), self.file_name_suffix
      ])
      rename_sources.append(shard)
      rename_destinations.append(final_name)

    source_batches = [rename_sources[i:i + MAX_BATCH_OPERATION_SIZE]
                      for i in xrange(0, len(rename_sources),
                                      MAX_BATCH_OPERATION_SIZE)]
    destination_batches = [rename_destinations[i:i + MAX_BATCH_OPERATION_SIZE]
                           for i in xrange(0, len(rename_destinations),
                                           MAX_BATCH_OPERATION_SIZE)]

    logging.info(
        'Starting finalize_write threads with num_shards: %d, '
        'batches: %d, num_threads: %d',
        num_shards, len(source_batches), num_threads)
    start_time = time.time()

    # Use a thread pool for renaming operations.
    def _rename_batch(batch):
      """_rename_batch executes batch rename operations."""
      sources, destinations = batch
      exceptions = []
      exception_infos = self._file_system.rename(sources, destinations)
      for src, dest, exception in exception_infos:
        if exception:
          logging.warning('Rename not successful: %s -> %s, %s', src, dest,
                          exception)
          should_report = True
          if isinstance(exception, IOError):
            # May have already been copied.
            try:
              if self._file_system.exists(dest):
                should_report = False
            except Exception as exists_e:  # pylint: disable=broad-except
              logging.warning('Exception when checking if file %s exists: '
                              '%s', dest, exists_e)
          if should_report:
            logging.warning(('Exception in _rename_batch. src: %s, '
                             'dest: %s, err: %s'), src, dest, exception)
            exceptions.append(exception)
        else:
          logging.debug('Rename successful: %s -> %s', src, dest)
      return exceptions

    exception_batches = util.run_using_threadpool(
        _rename_batch, zip(source_batches, destination_batches), num_threads)

    all_exceptions = [e for exception_batch in exception_batches
                      for e in exception_batch]
    if all_exceptions:
      raise Exception('Encountered exceptions in finalize_write: %s',
                      all_exceptions)

    for final_name in rename_destinations:
      yield final_name

    logging.info('Renamed %d shards in %.2f seconds.', num_shards,
                 time.time() - start_time)

    try:
      self._file_system.delete_directory(init_result)
    except IOError:
      # May have already been removed.
      pass

  @staticmethod
  def _template_to_format(shard_name_template):
    if not shard_name_template:
      return ''
    m = re.search('S+', shard_name_template)
    if m is None:
      raise ValueError("Shard number pattern S+ not found in template '%s'" %
                       shard_name_template)
    shard_name_format = shard_name_template.replace(
        m.group(0), '%%(shard_num)0%dd' % len(m.group(0)))
    m = re.search('N+', shard_name_format)
    if m:
      shard_name_format = shard_name_format.replace(
          m.group(0), '%%(num_shards)0%dd' % len(m.group(0)))
    return shard_name_format

  def __eq__(self, other):
    # TODO: Clean up workitem_test which uses this.
    # pylint: disable=unidiomatic-typecheck
    return type(self) == type(other) and self.__dict__ == other.__dict__


class FileSinkWriter(iobase.Writer):
  """The writer for FileSink.
  """

  def __init__(self, sink, temp_shard_path):
    self.sink = sink
    self.temp_shard_path = temp_shard_path
    self.temp_handle = self.sink.open(temp_shard_path)

  def write(self, value):
    self.sink.write_record(self.temp_handle, value)

  def close(self):
    self.sink.close(self.temp_handle)
    return self.temp_shard_path
