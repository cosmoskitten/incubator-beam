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
"""File system abstraction for file based sources and sinks."""

from __future__ import absolute_import

import bz2
import cStringIO
import os
import zlib


DEFAULT_READ_BUFFER_SIZE = 16 * 1024 * 1024


class _CompressionType(object):
  """Object representing single compression type."""

  def __init__(self, identifier):
    self.identifier = identifier

  def __eq__(self, other):
    return (isinstance(other, _CompressionType) and
            self.identifier == other.identifier)

  def __hash__(self):
    return hash(self.identifier)

  def __ne__(self, other):
    return not self.__eq__(other)

  def __str__(self):
    return self.identifier

  def __repr__(self):
    return '_CompressionType(%s)' % self.identifier


class CompressionTypes(object):
  """Enum-like class representing known compression types."""

  # Detect compression based on filename extension.
  #
  # The following extensions are currently recognized by auto-detection:
  #   .bz2 (implies BZIP2 as described below).
  #   .gz  (implies GZIP as described below)
  # Any non-recognized extension implies UNCOMPRESSED as described below.
  AUTO = _CompressionType('auto')

  # BZIP2 compression.
  BZIP2 = _CompressionType('bzip2')

  # GZIP compression (deflate with GZIP headers).
  GZIP = _CompressionType('gzip')

  # Uncompressed (i.e., may be split).
  UNCOMPRESSED = _CompressionType('uncompressed')

  @classmethod
  def is_valid_compression_type(cls, compression_type):
    """Returns true for valid compression types, false otherwise."""
    return isinstance(compression_type, _CompressionType)

  @classmethod
  def mime_type(cls, compression_type, default='application/octet-stream'):
    mime_types_by_compression_type = {
        cls.BZIP2: 'application/x-bz2',
        cls.GZIP: 'application/x-gzip',
    }
    return mime_types_by_compression_type.get(compression_type, default)

  @classmethod
  def detect_compression_type(cls, file_path):
    """Returns the compression type of a file (based on its suffix)."""
    compression_types_by_suffix = {'.bz2': cls.BZIP2, '.gz': cls.GZIP}
    lowercased_path = file_path.lower()
    for suffix, compression_type in compression_types_by_suffix.iteritems():
      if lowercased_path.endswith(suffix):
        return compression_type
    return cls.UNCOMPRESSED


class CompressedFile(object):
  """Somewhat limited file wrapper for easier handling of compressed files."""

  # The bit mask to use for the wbits parameters of the zlib compressor and
  # decompressor objects.
  _gzip_mask = zlib.MAX_WBITS | 16  # Mask when using GZIP headers.

  def __init__(self,
               fileobj,
               compression_type=CompressionTypes.GZIP,
               read_size=DEFAULT_READ_BUFFER_SIZE):
    if not fileobj:
      raise ValueError('File object must be opened file but was at %s' %
                       fileobj)

    if not CompressionTypes.is_valid_compression_type(compression_type):
      raise TypeError('compression_type must be CompressionType object but '
                      'was %s' % type(compression_type))
    if compression_type in (CompressionTypes.AUTO, CompressionTypes.UNCOMPRESSED
                           ):
      raise ValueError(
          'Cannot create object with unspecified or no compression')

    self._file = fileobj
    self._compression_type = compression_type

    if self._file.tell() != 0:
      raise ValueError('File object must be at position 0 but was %d' %
                       self._file.tell())
    self._uncompressed_position = 0

    if self.readable():
      self._read_size = read_size
      self._read_buffer = cStringIO.StringIO()
      self._read_position = 0
      self._read_eof = False

      if self._compression_type == CompressionTypes.BZIP2:
        self._decompressor = bz2.BZ2Decompressor()
      else:
        assert self._compression_type == CompressionTypes.GZIP
        self._decompressor = zlib.decompressobj(self._gzip_mask)
    else:
      self._decompressor = None

    if self.writeable():
      if self._compression_type == CompressionTypes.BZIP2:
        self._compressor = bz2.BZ2Compressor()
      else:
        assert self._compression_type == CompressionTypes.GZIP
        self._compressor = zlib.compressobj(zlib.Z_DEFAULT_COMPRESSION,
                                            zlib.DEFLATED, self._gzip_mask)
    else:
      self._compressor = None

  def readable(self):
    mode = self._file.mode
    return 'r' in mode or 'a' in mode

  def writeable(self):
    mode = self._file.mode
    return 'w' in mode or 'a' in mode

  def write(self, data):
    """Write data to file."""
    if not self._compressor:
      raise ValueError('compressor not initialized')
    self._uncompressed_position += len(data)
    compressed = self._compressor.compress(data)
    if compressed:
      self._file.write(compressed)

  def _fetch_to_internal_buffer(self, num_bytes):
    """Fetch up to num_bytes into the internal buffer."""
    if (not self._read_eof and self._read_position > 0 and
        (self._read_buffer.tell() - self._read_position) < num_bytes):
      # There aren't enough number of bytes to accommodate a read, so we
      # prepare for a possibly large read by clearing up all internal buffers
      # but without dropping any previous held data.
      self._read_buffer.seek(self._read_position)
      data = self._read_buffer.read()
      self._read_position = 0
      self._read_buffer.seek(0)
      self._read_buffer.truncate(0)
      self._read_buffer.write(data)

    while not self._read_eof and (self._read_buffer.tell() - self._read_position
                                 ) < num_bytes:
      # Continue reading from the underlying file object until enough bytes are
      # available, or EOF is reached.
      buf = self._file.read(self._read_size)
      if buf:
        decompressed = self._decompressor.decompress(buf)
        del buf  # Free up some possibly large and no-longer-needed memory.
        self._read_buffer.write(decompressed)
      else:
        # EOF reached.
        # Verify completeness and no corruption and flush (if needed by
        # the underlying algorithm).
        if self._compression_type == CompressionTypes.BZIP2:
          # Having unused_data past end of stream would imply file corruption.
          assert not self._decompressor.unused_data, 'Possible file corruption.'
          try:
            # EOF implies that the underlying BZIP2 stream must also have
            # reached EOF. We expect this to raise an EOFError and we catch it
            # below. Any other kind of error though would be problematic.
            self._decompressor.decompress('dummy')
            assert False, 'Possible file corruption.'
          except EOFError:
            pass  # All is as expected!
        else:
          self._read_buffer.write(self._decompressor.flush())

        # Record that we have hit the end of file, so we won't unnecessarily
        # repeat the completeness verification step above.
        self._read_eof = True

  def _read_from_internal_buffer(self, read_fn):
    """Read from the internal buffer by using the supplied read_fn."""
    self._read_buffer.seek(self._read_position)
    result = read_fn()
    self._read_position += len(result)
    self._uncompressed_position += len(result)
    self._read_buffer.seek(0, os.SEEK_END)  # Allow future writes.
    return result

  def read(self, num_bytes):
    if not self._decompressor:
      raise ValueError('decompressor not initialized')

    self._fetch_to_internal_buffer(num_bytes)
    return self._read_from_internal_buffer(
        lambda: self._read_buffer.read(num_bytes))

  def readline(self):
    """Equivalent to standard file.readline(). Same return conventions apply."""
    if not self._decompressor:
      raise ValueError('decompressor not initialized')

    io = cStringIO.StringIO()
    while True:
      # Ensure that the internal buffer has at least half the read_size. Going
      # with half the _read_size (as opposed to a full _read_size) to ensure
      # that actual fetches are more evenly spread out, as opposed to having 2
      # consecutive reads at the beginning of a read.
      self._fetch_to_internal_buffer(self._read_size / 2)
      line = self._read_from_internal_buffer(
          lambda: self._read_buffer.readline())
      io.write(line)
      if line.endswith('\n') or not line:
        break  # Newline or EOF reached.

    return io.getvalue()

  def closed(self):
    return not self._file or self._file.closed()

  def close(self):
    if self.readable():
      self._read_buffer.close()

    if self.writeable():
      self._file.write(self._compressor.flush())

    self._file.close()

  def flush(self):
    if self.writeable():
      self._file.write(self._compressor.flush())
    self._file.flush()

  @property
  def seekable(self):
    # TODO: Add support for seeking to a file position.
    return False

  def tell(self):
    """Returns current position in uncompressed file."""
    return self._uncompressed_position

  def __enter__(self):
    return self

  def __exit__(self, exception_type, exception_value, traceback):
    self.close()


class FileMetadata(object):
  """Metadata about a file path that is the output of FileSystem.match
  """
  def __init__(self, path, size_in_bytes):
    self.path = path
    self.size_in_bytes = size_in_bytes


class FileSystem(object):
  """A class that defines that can be performed on a filesystem.

  All methods are abstract and they are for file system providers to
  implement. Clients should use the FileSystemUtil class to interact with
  the correct file system based on the provided file pattern scheme.
  """

  @staticmethod
  def _get_compression_type(path, compression_type):
    if compression_type == CompressionTypes.AUTO:
      compression_type = CompressionTypes.detect_compression_type(path)
    elif not CompressionTypes.is_valid_compression_type(compression_type):
      raise TypeError('compression_type must be CompressionType object but '
                      'was %s' % type(compression_type))
    return compression_type

  @staticmethod
  def mkdir(path):
    raise NotImplementedError("Filesystem is an abstract class")

  @staticmethod
  def match(path, limit=None):
    raise NotImplementedError("Filesystem is an abstract class")

  @staticmethod
  def create(path, limit=None):
    raise NotImplementedError("Filesystem is an abstract class")

  @staticmethod
  def open(path, mode, mime_type='application/octet-stream',
           compression_type=CompressionTypes.AUTO):
    raise NotImplementedError("Filesystem is an abstract class")

  @staticmethod
  def open(file_handle):
    raise NotImplementedError("Filesystem is an abstract class")

  @staticmethod
  def copy(source, destination):
    raise NotImplementedError("Filesystem is an abstract class")

  @staticmethod
  def rename(sources, destinations):
    raise NotImplementedError("Filesystem is an abstract class")

  @staticmethod
  def exists(path):
    raise NotImplementedError("Filesystem is an abstract class")

  @staticmethod
  def delete(path):
    raise NotImplementedError("Filesystem is an abstract class")

  @staticmethod
  def delete_directory(path):
    raise NotImplementedError("Filesystem is an abstract class")
