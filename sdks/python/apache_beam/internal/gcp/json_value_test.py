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

"""Unit tests for the json_value module."""

import unittest

from apache_beam.internal.gcp.json_value import from_json_value
from apache_beam.internal.gcp.json_value import to_json_value
from apache_beam.options.value_provider import StaticValueProvider
from apache_beam.options.value_provider import RuntimeValueProvider


# Protect against environments where apitools library is not available.
# pylint: disable=wrong-import-order, wrong-import-position
try:
  from apitools.base.py.extra_types import JsonValue
except ImportError:
  JsonValue = None
# pylint: enable=wrong-import-order, wrong-import-position


@unittest.skipIf(JsonValue is None, 'GCP dependencies are not installed')
class JsonValueTest(unittest.TestCase):

  def test_string_to(self):
    self.assertEquals(JsonValue(string_value='abc'), to_json_value('abc'))

  def test_true_to(self):
    self.assertEquals(JsonValue(boolean_value=True), to_json_value(True))

  def test_false_to(self):
    self.assertEquals(JsonValue(boolean_value=False), to_json_value(False))

  def test_int_to(self):
    self.assertEquals(JsonValue(integer_value=14), to_json_value(14))

  def test_float_to(self):
    self.assertEquals(JsonValue(double_value=2.75), to_json_value(2.75))

  def test_static_value_provider_to(self):
    svp = StaticValueProvider(str, 'abc')
    self.assertEquals(JsonValue(string_value=svp.value), to_json_value(svp))

  def test_runtime_value_provider_to(self):
    RuntimeValueProvider.runtime_options = None
    rvp = RuntimeValueProvider('arg', 123, int)
    self.assertEquals(JsonValue(is_null=True), to_json_value(rvp))

  def test_none_to(self):
    self.assertEquals(JsonValue(is_null=True), to_json_value(None))

  def test_string_from(self):
    self.assertEquals('WXYZ', from_json_value(to_json_value('WXYZ')))

  def test_true_from(self):
    self.assertEquals(True, from_json_value(to_json_value(True)))

  def test_false_from(self):
    self.assertEquals(False, from_json_value(to_json_value(False)))

  def test_int_from(self):
    self.assertEquals(-27, from_json_value(to_json_value(-27)))

  def test_float_from(self):
    self.assertEquals(4.5, from_json_value(to_json_value(4.5)))

  def test_with_type(self):
    rt = from_json_value(to_json_value('abcd', with_type=True))
    self.assertEquals('http://schema.org/Text', rt['@type'])
    self.assertEquals('abcd', rt['value'])

  def test_none_from(self):
    self.assertIsNone(from_json_value(to_json_value(None)))

  def test_large_integer(self):
    num = 1 << 35
    self.assertEquals(num, from_json_value(to_json_value(num)))
    self.assertEquals(long(num), from_json_value(to_json_value(long(num))))

  def test_long_value(self):
    self.assertEquals(long(27), from_json_value(to_json_value(long(27))))

  def test_too_long_value(self):
    with self.assertRaises(TypeError):
      to_json_value(long(1 << 64))


if __name__ == '__main__':
  unittest.main()
