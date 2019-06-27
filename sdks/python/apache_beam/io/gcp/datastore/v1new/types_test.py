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

"""Unit tests for types module."""

from __future__ import absolute_import

import logging
import unittest

import mock

# Protect against environments where datastore library is not available.
try:
  from google.cloud.datastore import client
  from apache_beam.io.gcp.datastore.v1new.types import Entity
  from apache_beam.io.gcp.datastore.v1new.types import Key
  from apache_beam.io.gcp.datastore.v1new.types import Query
  from apache_beam.options.value_provider import StaticValueProvider
# TODO(BEAM-4543): Remove TypeError once googledatastore dependency is removed.
except (ImportError, TypeError):
  client = None


@unittest.skipIf(client is None, 'Datastore dependencies are not installed')
class TypesTest(unittest.TestCase):
  _PROJECT = 'project'
  _NAMESPACE = 'namespace'

  def setUp(self):
    self._test_client = client.Client(
        project=self._PROJECT, namespace=self._NAMESPACE,
        # Don't do any network requests.
        _http=mock.MagicMock())

  def testEntityToClientEntity(self):
    k = Key(['kind', 1234], project=self._PROJECT)
    kc = k.to_client_key()
    exclude_from_indexes = ('efi1', 'efi2')
    e = Entity(k, exclude_from_indexes=exclude_from_indexes)
    e.set_properties({'efi1': 'value', 'property': 'value'})
    ec = e.to_client_entity()
    self.assertEqual(kc, ec.key)
    self.assertSetEqual(set(exclude_from_indexes), ec.exclude_from_indexes)
    self.assertEqual('kind', ec.kind)
    self.assertEqual(1234, ec.id)

  def testEntityFromClientEntity(self):
    k = Key(['kind', 1234], project=self._PROJECT)
    exclude_from_indexes = ('efi1', 'efi2')
    e = Entity(k, exclude_from_indexes=exclude_from_indexes)
    e.set_properties({'efi1': 'value', 'property': 'value'})
    efc = Entity.from_client_entity(e.to_client_entity())
    self.assertEqual(e, efc)

  def testKeyToClientKey(self):
    k = Key(['kind1', 'parent'],
            project=self._PROJECT, namespace=self._NAMESPACE)
    ck = k.to_client_key()
    self.assertEqual(self._PROJECT, ck.project)
    self.assertEqual(self._NAMESPACE, ck.namespace)
    self.assertEqual(('kind1', 'parent'), ck.flat_path)
    self.assertEqual('kind1', ck.kind)
    self.assertEqual('parent', ck.id_or_name)
    self.assertEqual(None, ck.parent)

    k2 = Key(['kind2', 1234], parent=k)
    ck2 = k2.to_client_key()
    self.assertEqual(self._PROJECT, ck2.project)
    self.assertEqual(self._NAMESPACE, ck2.namespace)
    self.assertEqual(('kind1', 'parent', 'kind2', 1234), ck2.flat_path)
    self.assertEqual('kind2', ck2.kind)
    self.assertEqual(1234, ck2.id_or_name)
    self.assertEqual(ck, ck2.parent)

  def testKeyFromClientKey(self):
    k = Key(['k1', 1234], project=self._PROJECT, namespace=self._NAMESPACE)
    kfc = Key.from_client_key(k.to_client_key())
    self.assertEqual(k, kfc)

    k2 = Key(['k2', 'adsf'], parent=k)
    kfc2 = Key.from_client_key(k2.to_client_key())
    # Converting a key with a parent to a client_key and back loses the parent:
    self.assertNotEqual(k2, kfc2)
    self.assertTupleEqual(('k1', 1234, 'k2', 'adsf'), kfc2.path_elements)
    self.assertIsNone(kfc2.parent)

    kfc3 = Key.from_client_key(kfc2.to_client_key())
    self.assertEqual(kfc2, kfc3)

  def testKeyFromClientKeyNoNamespace(self):
    k = Key(['k1', 1234], project=self._PROJECT)
    ck = k.to_client_key()
    self.assertEqual(None, ck.namespace)  # Test that getter doesn't croak.
    kfc = Key.from_client_key(ck)
    self.assertEqual(k, kfc)

  def testKeyToClientKeyMissingProject(self):
    k = Key(['k1', 1234], namespace=self._NAMESPACE)
    with self.assertRaisesRegexp(ValueError, r'project'):
      _ = Key.from_client_key(k.to_client_key())

  def testQuery(self):
    filters = [('property_name', '=', 'value')]
    projection = ['f1', 'f2']
    order = projection
    distinct_on = projection
    ancestor_key = Key(['kind', 'id'], project=self._PROJECT)
    q = Query(kind='kind', project=self._PROJECT, namespace=self._NAMESPACE,
              ancestor=ancestor_key, filters=filters, projection=projection,
              order=order, distinct_on=distinct_on)
    cq = q._to_client_query(self._test_client)
    self.assertEqual(self._PROJECT, cq.project)
    self.assertEqual(self._NAMESPACE, cq.namespace)
    self.assertEqual('kind', cq.kind)
    self.assertEqual(ancestor_key.to_client_key(), cq.ancestor)
    self.assertEqual(filters, cq.filters)
    self.assertEqual(projection, cq.projection)
    self.assertEqual(order, cq.order)
    self.assertEqual(distinct_on, cq.distinct_on)

    logging.info('query: %s', q)  # Test __repr__()

  def testValueProviderFilters(self):
    self.vp_filters = [[StaticValueProvider(tuple,
                                            ('property_name', '=', 'value'))],
                       [StaticValueProvider(tuple,
                                            ('property_name', '=', 'value')),
                        ('property_name', '=', 'value')],
                      ]
    self.expected_filters = [[('property_name', '=', 'value')],
                             [('property_name', '=', 'value'),
                              ('property_name', '=', 'value')],
                            ]

    for vp_filter, exp_filter in zip(self.vp_filters, self.expected_filters):
      q = Query(kind='kind', project=self._PROJECT, namespace=self._NAMESPACE,
                filters=vp_filter)
      cq = q._to_client_query(self._test_client)
      self.assertEqual(exp_filter, cq.filters)

      logging.info('query: %s', q)  # Test __repr__()

  def testQueryEmptyNamespace(self):
    # Test that we can pass a namespace of None.
    self._test_client.namespace = None
    q = Query(project=self._PROJECT, namespace=None)
    cq = q._to_client_query(self._test_client)
    self.assertEqual(self._test_client.project, cq.project)
    self.assertEqual(None, cq.namespace)


if __name__ == '__main__':
  unittest.main()
