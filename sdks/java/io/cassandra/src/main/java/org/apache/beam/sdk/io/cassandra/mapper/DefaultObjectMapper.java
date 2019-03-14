/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.cassandra.mapper;

import com.datastax.driver.core.ResultSet;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Iterator;

/**
 * Default Object mapper implementation that uses the <a
 * href="https://docs.datastax.com/en/developer/java-driver/3.1/manual/object_mapper"/>Cassandra
 * Object Mapper</a> for mapping POJOs to CRUD events in Cassandra.
 *
 * @see org.apache.beam.sdk.io.cassandra.mapper.DefaultObjectMapperFactory
 */
public class DefaultObjectMapper<T> implements Mapper<T> {

  com.datastax.driver.mapping.Mapper<T> datastaxMapper;

  public DefaultObjectMapper(com.datastax.driver.mapping.Mapper mapper) {
    this.datastaxMapper = mapper;
  }

  @Override
  public Iterator<T> map(ResultSet resultSet) {
    return datastaxMapper.map(resultSet).iterator();
  }

  @Override
  public ListenableFuture<Void> deleteAsync(T entity) {
    return datastaxMapper.deleteAsync(entity);
  }

  @Override
  public ListenableFuture<Void> saveAsync(T entity) {
    return datastaxMapper.saveAsync(entity);
  }
}
