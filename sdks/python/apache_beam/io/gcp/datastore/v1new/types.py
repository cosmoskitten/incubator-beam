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

"""
Beam Datastore types.

This module is experimental, no backwards compatibility guarantees.
"""

from __future__ import absolute_import

import copy

# Protect against environments where datastore library is not available.
try:
  from google.cloud.datastore import entity
  from google.cloud.datastore import key
  from google.cloud.datastore import query
except ImportError:
  pass


__all__ = ['Query', 'Key', 'Entity']


class Query(object):
  def __init__(self, kind=None, project=None, namespace=None, ancestor=None,
               filters=(), projection=(), order=(), distinct_on=(), limit=None):
    """Represents a Datastore query.

    Args:
      kind: (str) The kind to query.
      project: (str) Required. Project associated with query.
      namespace: (str) (Optional) Namespace to restrict results to.
      ancestor: (`Key`) (Optional) key of
        the ancestor to which this query's results are restricted.
      filters: (sequence of tuple[str, str, str]) Property filters applied by
        this query. The sequence is ``(property_name, operator, value)``.
      projection: (sequence of string) fields returned as part of query results.
      order: (sequence of string) field names used to order query results.
        Prepend ``-`` to a field name to sort it in descending order.
      distinct_on: (sequence of string) field names used to group query
        results.
      limit: (int) Maximum amount of results to return.
    """
    self.kind = kind
    self.project = project
    self.namespace = namespace
    self.ancestor = ancestor
    self.filters = filters or ()
    self.projection = projection
    self.order = order
    self.distinct_on = distinct_on
    self.limit = limit

  def _to_client_query(self, client):
    """
    Returns a ``google.cloud.datastore.query.Query`` instance that represents
    this query.

    Args:
      client: (``google.cloud.datastore.client.Client``) Datastore client
        instance to use.
    """
    ancestor_client_key = None
    if self.ancestor is not None:
      ancestor_client_key = self.ancestor.to_client_key()
    return query.Query(
        client, kind=self.kind, project=self.project, namespace=self.namespace,
        ancestor=ancestor_client_key, filters=self.filters,
        projection=self.projection, order=self.order,
        distinct_on=self.distinct_on)

  def clone(self):
    return copy.copy(self)


class Key(object):
  def __init__(self, path_elements, parent=None, project=None, namespace=None):
    """
    Represents a Datastore key.

    The partition ID is represented by its components: namespace and project.
    If key has a parent, project and namespace should either be unset or match
    the parent's.

    Args:
      path_elements: (list of str and int) Key path: an alternating sequence of
        kind and identifier. The kind must be of type ``str`` and identifier may
        be a ``str`` or an ``int``.
        If the last identifier is omitted this is an incomplete key, which is
        unsupported in ``WriteToDatastore`` and ``DeleteFromDatastore``.
        See ``google.cloud.datastore.key.Key`` for more details.
      parent: (`Key`) (optional) Parent for this key.
      project: (str) Project ID. Required unless set by parent.
      namespace: (str) (optional) Namespace ID
    """
    # Verification or arguments is delegated to to_client_key().
    self.path_elements = tuple(path_elements)
    self.parent = parent
    self.namespace = namespace
    self.project = project

  @staticmethod
  def from_client_key(client_key):
    return Key(client_key.flat_path, project=client_key.project,
               namespace=client_key.namespace)

  def to_client_key(self):
    """
    Returns a ``google.cloud.datastore.key.Key`` instance that represents
    this key.
    """
    parent = self.parent
    if parent is not None:
      parent = parent.to_client_key()
    return key.Key(*self.path_elements, parent=parent, namespace=self.namespace,
                   project=self.project)

  def __eq__(self, other):
    if not isinstance(other, Key):
      return False
    if self.path_elements != other.path_elements:
      return False
    if self.parent is not None and other.parent is not None:
      return self.parent == other.parent

    return self.parent is None and other.parent is None

  def __repr__(self):
    return '<%s(%s, parent=%s, project=%s, namespace=%s)>' % (
        self.__class__.__name__, str(self.path_elements), str(self.parent),
        self.project, self.namespace)


class Entity(object):
  def __init__(self, key, exclude_from_indexes=()):
    """
    Represents a Datastore entity.

    Does not support the property value "meaning" field.

    Args:
      key: (Key) A complete Key representing this Entity.
      exclude_from_indexes: (iterable of str) List of property keys whose values
        should not be indexed for this entity.
    """
    self.key = key
    self.exclude_from_indexes = set(exclude_from_indexes)
    self.properties = {}

  def set_properties(self, property_dict):
    """Sets a dictionary of properties on this entity.

    Args:
      property_dict: A map from property name to value. See
      ``google.cloud.datastore.entity.Entity`` documentation for allowed values.
    """
    self.properties.update(property_dict)

  @staticmethod
  def from_client_entity(client_entity):
    key = Key.from_client_key(client_entity.key)
    entity = Entity(
        key, exclude_from_indexes=set(client_entity.exclude_from_indexes))
    entity.set_properties(client_entity)
    return entity

  def to_client_entity(self):
    """
    Returns a ``google.cloud.datastore.entity.Entity`` instance that represents
    this entity.
    """
    key = self.key.to_client_key()
    res = entity.Entity(key=key,
                        exclude_from_indexes=tuple(self.exclude_from_indexes))
    res.update(self.properties)
    return res

  def __eq__(self, other):
    if not isinstance(other, Entity):
      return False
    return (self.key == other.key and
            self.exclude_from_indexes == other.exclude_from_indexes and
            self.properties == other.properties)

  def __repr__(self):
    return "<%s(key=%s, exclude_from_indexes=%s) properties=%s>" % (
        self.__class__.__name__, str(self.key),
        str(self.exclude_from_indexes), str(self.properties))
