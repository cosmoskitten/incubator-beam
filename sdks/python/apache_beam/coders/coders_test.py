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


import base64
import logging
import unittest

from apache_beam import coders
from apache_beam.coders import proto2_coder_test_messages_pb2 as test_message


class PickleCoderTest(unittest.TestCase):

  def test_basics(self):
    v = ('a' * 10, 'b' * 90)
    pickler = coders.PickleCoder()
    self.assertEquals(v, pickler.decode(pickler.encode(v)))
    pickler = coders.Base64PickleCoder()
    self.assertEquals(v, pickler.decode(pickler.encode(v)))
    self.assertEquals(
        coders.Base64PickleCoder().encode(v),
        base64.b64encode(coders.PickleCoder().encode(v)))

  def test_equality(self):
    self.assertEquals(coders.PickleCoder(), coders.PickleCoder())
    self.assertEquals(coders.Base64PickleCoder(), coders.Base64PickleCoder())
    self.assertNotEquals(coders.Base64PickleCoder(), coders.PickleCoder())
    self.assertNotEquals(coders.Base64PickleCoder(), object())


class CodersTest(unittest.TestCase):

  def test_str_utf8_coder(self):
    real_coder = coders.registry.get_coder(str)
    expected_coder = coders.BytesCoder()
    self.assertEqual(
        real_coder.encode('abc'), expected_coder.encode('abc'))
    self.assertEqual('abc', real_coder.decode(real_coder.encode('abc')))

    real_coder = coders.registry.get_coder(bytes)
    expected_coder = coders.BytesCoder()
    self.assertEqual(
        real_coder.encode('abc'), expected_coder.encode('abc'))
    self.assertEqual('abc', real_coder.decode(real_coder.encode('abc')))


class ProtoCoderTest(unittest.TestCase):

  def test_proto_coder(self):
    ma = test_message.MessageA()
    mb = ma.field2.add()
    mb.field1 = True
    ma.field1 = u'hello world'
    expected_coder = coders.ProtoCoder(ma.__class__)
    real_coder = coders.registry.get_coder(ma.__class__)
    self.assertEqual(expected_coder, real_coder)
    self.assertEqual(real_coder.encode(ma), expected_coder.encode(ma))
    self.assertEqual(ma, real_coder.decode(real_coder.encode(ma)))


class DummyClass(object):
  "A class with no registered coder."
  def __init__(self):
    pass

  def __eq__(self, other):
    if isinstance(other, self.__class__):
      return True
    return False


class FallbackCoderTest(unittest.TestCase):

  def test_fallaback_path(self):
    coder = coders.registry.get_coder(DummyClass)
    self.assertEqual(coder, coders.FastPrimitivesCoder())
    self.assertEqual(DummyClass(), coder.decode(coder.encode(DummyClass())))

if __name__ == '__main__':
  logging.getLogger().setLevel(logging.INFO)
  unittest.main()
