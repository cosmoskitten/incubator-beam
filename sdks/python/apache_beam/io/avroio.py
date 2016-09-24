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
"""Implements a source for reading Avro files."""

import os
import StringIO
import zlib

from avro import datafile
from avro import io as avroio
from avro import schema

from apache_beam.io import filebasedsource
from apache_beam.io.iobase import Read
from apache_beam.transforms import PTransform

__all__ = ['ReadFromAvro']


class ReadFromAvro(PTransform):
  """A ``PTransform`` for reading avro files."""

  def __init__(self, label=None, file_pattern=None, min_bundle_size=0,
               **kwargs):
    """Initializes ``ReadFromAvro``.

    Uses source '_AvroSource' to read a set of Avro files defined by a given
    file pattern.
    If '/mypath/myavrofiles*' is a file-pattern that points to a set of Avro
    files, a ``PCollection`` for the records in these Avro files can be created
    in the following manner.
      p = df.Pipeline(argv=pipeline_args)
      records = p | 'Read' >> df.io.ReadFromAvro('/mypath/myavrofiles*')

    Each record of this ``PCollection`` will contain a single record read from a
    source. Records that are of simple types will be mapped into corresponding
    Python types. Records that are of Avro type 'RECORD' will be mapped to
    Python dictionaries that comply with the schema contained in the Avro file
    that contains those records. In this case, keys of each dictionary
    will contain the corresponding field names and will be of type ``string``
    while the values of the dictionary will be of the type defined in the
    corresponding Avro schema.
    For example, if schema of the Avro file is the following.
      {"namespace": "example.avro","type": "record","name": "User","fields":
      [{"name": "name", "type": "string"},
       {"name": "favorite_number",  "type": ["int", "null"]},
       {"name": "favorite_color", "type": ["string", "null"]}]}
    Then records generated by ``AvroSource`` will be dictionaries of the
    following form.
      {u'name': u'Alyssa', u'favorite_number': 256, u'favorite_color': None}).

    Args:
      label: label of the PTransform.
      file_pattern: the set of files to be read.
      min_bundle_size: the minimum size in bytes, to be considered when
                       splitting the input into bundles.
      **kwargs: Additional keyword arguments to be passed to the base class.
    """
    super(ReadFromAvro, self).__init__(label, **kwargs)

    self._file_pattern = file_pattern
    self._min_bundle_size = min_bundle_size

  def apply(self, pcoll):
    return pcoll.pipeline | Read(
        _AvroSource(
            file_pattern=self._file_pattern,
            min_bundle_size=self._min_bundle_size))


class _AvroUtils(object):

  @staticmethod
  def read_meta_data_from_file(f):
    """Reads metadata from a given Avro file.

    Args:
      f: Avro file to read.
    Returns:
      a tuple containing the codec, schema, and the sync marker of the Avro
      file.

    Raises:
      ValueError: if the file does not start with the byte sequence defined in
                  the specification.
    """
    if f.tell() > 0:
      f.seek(0)
    decoder = avroio.BinaryDecoder(f)
    header = avroio.DatumReader().read_data(datafile.META_SCHEMA,
                                            datafile.META_SCHEMA, decoder)
    if header.get('magic') != datafile.MAGIC:
      raise ValueError('Not an Avro file. File header should start with %s but'
                       'started with %s instead.', datafile.MAGIC,
                       header.get('magic'))

    meta = header['meta']

    if datafile.CODEC_KEY in meta:
      codec = meta[datafile.CODEC_KEY]
    else:
      codec = 'null'

    schema_string = meta[datafile.SCHEMA_KEY]
    sync_marker = header['sync']

    return codec, schema_string, sync_marker

  @staticmethod
  def read_block_from_file(f, codec, schema, expected_sync_marker):
    """Reads a block from a given Avro file.

    Args:
      f: Avro file to read.
    Returns:
      A single _AvroBlock.

    Raises:
      ValueError: If the block cannot be read properly because the file doesn't
        match the specification.
    """
    decoder = avroio.BinaryDecoder(f)
    num_records = decoder.read_long()
    block_size = decoder.read_long()
    block_bytes = decoder.read(block_size)
    sync_marker = decoder.read(len(expected_sync_marker))
    if sync_marker != expected_sync_marker:
      raise ValueError('Unexpected sync marker (actual "%s" vs expected "%s"). '
                       'Maybe the underlying avro file is corrupted?',
                       sync_marker, expected_sync_marker)
    return _AvroBlock(block_bytes, num_records, codec, schema)

  @staticmethod
  def advance_file_past_next_sync_marker(f, sync_marker):
    buf_size = 10000

    data = f.read(buf_size)
    while data:
      pos = data.find(sync_marker)
      if pos >= 0:
        # Adjusting the current position to the ending position of the sync
        # marker.
        backtrack = len(data) - pos - len(sync_marker)
        f.seek(-1 * backtrack, os.SEEK_CUR)
        return True
      else:
        if f.tell() >= len(sync_marker):
          # Backtracking in case we partially read the sync marker during the
          # previous read. We only have to backtrack if there are at least
          # len(sync_marker) bytes before current position. We only have to
          # backtrack (len(sync_marker) - 1) bytes.
          f.seek(-1 * (len(sync_marker) - 1), os.SEEK_CUR)
        data = f.read(buf_size)


class _AvroBlock(object):
  """Represents a block of an Avro file."""

  def __init__(self, block_bytes, num_records, codec, schema_string):
    self._block_bytes = block_bytes
    self._num_records = num_records
    self._codec = codec
    self._schema = schema.parse(schema_string)

  def _decompress_bytes(self, data):
    if self._codec == 'null':
      return data
    elif self._codec == 'deflate':
      # zlib.MAX_WBITS is the window size. '-' sign indicates that this is
      # raw data (without headers). See zlib and Avro documentations for more
      # details.
      return zlib.decompress(data, -zlib.MAX_WBITS)
    elif self._codec == 'snappy':
      # Snappy is an optional avro codec.
      # See Snappy and Avro documentation for more details.
      try:
        import snappy
      except ImportError:
        raise ValueError('Snappy does not seem to be installed.')

      # Compressed data includes a 4-byte CRC32 checksum which we verify.
      result = snappy.decompress(data[:-4])
      avroio.BinaryDecoder(StringIO.StringIO(data[-4:])).check_crc32(result)
      return result
    else:
      raise ValueError('Unknown codec: %r', self._codec)

  def num_records(self):
    return self._num_records

  def records(self):
    decompressed_bytes = self._decompress_bytes(self._block_bytes)
    decoder = avroio.BinaryDecoder(StringIO.StringIO(decompressed_bytes))
    reader = avroio.DatumReader(
        writers_schema=self._schema, readers_schema=self._schema)

    current_record = 0
    while current_record < self._num_records:
      yield reader.read(decoder)
      current_record += 1


class _AvroSource(filebasedsource.FileBasedSource):
  """A source for reading Avro files.

  ``_AvroSource`` is implemented using the file-based source framework available
  in module 'filebasedsource'. Hence please refer to module 'filebasedsource'
  to fully understand how this source implements operations common to all
  file-based sources such as file-pattern expansion and splitting into bundles
  for parallel processing.
  """

  def read_records(self, file_name, range_tracker):
    start_offset = range_tracker.start_position()
    if start_offset is None:
      start_offset = 0

    with self.open_file(file_name) as f:
      codec, schema_string, sync_marker = _AvroUtils.read_meta_data_from_file(f)

      # We have to start at current position if previous bundle ended at the
      # end of a sync marker.
      start_offset = max(0, start_offset - len(sync_marker))
      f.seek(start_offset)
      _AvroUtils.advance_file_past_next_sync_marker(f, sync_marker)

      while range_tracker.try_claim(f.tell()):
        block = _AvroUtils.read_block_from_file(f, codec, schema_string,
                                                sync_marker)
        for record in block.records():
          yield record
