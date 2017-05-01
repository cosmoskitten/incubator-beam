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

package org.apache.beam.sdk.io.gcp.bigquery;

import com.google.api.services.bigquery.model.TableSchema;
import java.io.Serializable;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.ValueInSingleWindow;

/**
 * This class provides the most general way of specifying dynamic BigQuery table destinations.
 * Destinations can be extracted from the input element, and stored as a custom type. Mappings
 * are provided to convert the destination into a BigQuery table reference and a BigQuery schema.
 * The class can read a side input from another PCollection while performing these mappings.
 *
 * <p>For example, consider a PCollection of events, each containing a user-id field. You want to
 * write each user's events to a separate table with a separate schema per user. Since the user-id
 * field is a string, you will represent the destination as a string.
 *<pre>{@code
 *events.apply(BigQueryIO.<UserEvent>write()
 *  .to(new DynamicDestinations<UserEvent, String>() {
 *        public String getDestination(ValueInSingleWindow<String> element) {
 *          return element.getValue().getUserId();
 *        }
 *        public TableDestination getTable(String user) {
 *          return new TableDestination(tableForUser(user), "Table for user " + user);
 *        }
 *        public TableSchema getSchema(String user) {
 *          return tableSchemaForUser(user);
 *        }
 *      })
 *  .withFormatFunction(new SerializableFunction<UserEvent, TableRow>() {
 *     public TableRow apply(UserEvent event) {
 *       return convertUserEventToTableRow(event);
 *     }
 *   }));
 *}</pre>
 *
 * <p>An instance of {@link DynamicDestinations} can also request a side input value that can be
 *  examined from inside {@link #getTable} and {@link #getSchema}. The side input is requested by
 *  calling {@link #setSideInputRequired} on the base class. The value can be examined by calling
 *  {@link #getSideInputValue()}; the side input value will only be available from within calls to
 *  {@link #getTable} or {@link #getSchema}.
 *
 * <p>{@code DestinationT} is expected to provide proper hash and equality members.
 */
public abstract class DynamicDestinations<T, DestinationT> implements Serializable {
  private PCollectionView<?> sideInput;
  private Object materialized;

  /**
   * Specifies that this object needs access to a side input.
   */
  public DynamicDestinations setSideInputRequired(PCollectionView<?> sideInput) {
    this.sideInput = sideInput;
    return this;
  }

  /**
   * Returns an object that represents at a high level which table is being written to.
   */
  public abstract DestinationT getDestination(ValueInSingleWindow<T> element);

  /**
   * Returns the coder for {@link DestinationT}. If this is not overridden, then
   * {@link BigQueryIO} will look in the coder registry for a suitable coder.
   */
  public @Nullable Coder<DestinationT> getDestinationCoder() {
    return null;
  }

  /**
   * Returns a {@link TableDestination} object for the destination.
   */
  public abstract TableDestination getTable(DestinationT destination);

  /**
   * Returns the table schema for the destination.
   */
  public abstract TableSchema getSchema(DestinationT destination);

  /**
   * This returns the unmaterialized side input used by this transform.
   */
  <SideInputT> PCollectionView<SideInputT> getSideInput() {
    return (PCollectionView<SideInputT>) sideInput;
  }

  /**
   * Returns the materialized value of the side input. Can be called by concrete
   * {@link DynamicDestinations} instances in {@link #getSchema} or {@link #getTable}.
   */
  protected <SideInputT> SideInputT getSideInputValue() {
    return (SideInputT) materialized;
  }

  <SideInputT> void setSideInputValue(SideInputT value) {
    materialized = value;
  }

  // Gets the destination coder. If the user does not provide one, try to find one in the coder
  // registry. If no coder can be found, throws CannotProvideCoderException.
  Coder<DestinationT> getDestinationCoderWithDefault(CoderRegistry registry)
      throws CannotProvideCoderException {
    Coder<DestinationT> destinationCoder = getDestinationCoder();
    if (destinationCoder == null) {
      // If dynamicDestinations doesn't provide a coder, try to find it in the coder registry.
      destinationCoder = registry.getDefaultCoder(new TypeDescriptor<DestinationT>() {});
    }
    return destinationCoder;
  }
}
