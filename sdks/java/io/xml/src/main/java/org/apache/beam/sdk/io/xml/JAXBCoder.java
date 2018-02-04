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
package org.apache.beam.sdk.io.xml;

import com.google.common.io.ByteStreams;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.CustomCoder;
import org.apache.beam.sdk.coders.SkipCloseCoder;
import org.apache.beam.sdk.util.EmptyOnDeserializationThreadLocal;
import org.apache.beam.sdk.util.VarInt;
import org.apache.beam.sdk.values.TypeDescriptor;

/**
 * A coder for JAXB annotated objects. This coder uses JAXB marshalling/unmarshalling mechanisms
 * to encode/decode the objects. Users must provide the {@code Class} of the JAXB annotated object.
 *
 * @param <T> type of JAXB annotated objects that will be serialized.
 */
// todo: use org.apache.beam.sdk.coders.LengthAwareCoder, does it hurt if always set in of()?
public class JAXBCoder<T> extends CustomCoder<T> {

  private final Class<T> jaxbClass;
  private transient volatile JAXBContext jaxbContext;
  private final EmptyOnDeserializationThreadLocal<Marshaller> jaxbMarshaller;
  private final EmptyOnDeserializationThreadLocal<Unmarshaller> jaxbUnmarshaller;

  public Class<T> getJAXBClass() {
    return jaxbClass;
  }

  private JAXBCoder(Class<T> jaxbClass) {
    this.jaxbClass = jaxbClass;
    this.jaxbMarshaller = new EmptyOnDeserializationThreadLocal<Marshaller>() {
      @Override
      protected Marshaller initialValue() {
        try {
          JAXBContext jaxbContext = getContext();
          return jaxbContext.createMarshaller();
        } catch (JAXBException e) {
          throw new RuntimeException("Error when creating marshaller from JAXB Context.", e);
        }
      }
    };
    this.jaxbUnmarshaller = new EmptyOnDeserializationThreadLocal<Unmarshaller>() {
      @Override
      protected Unmarshaller initialValue() {
        try {
          JAXBContext jaxbContext = getContext();
          return jaxbContext.createUnmarshaller();
        } catch (Exception e) {
          throw new RuntimeException("Error when creating unmarshaller from JAXB Context.", e);
        }
      }
    };
  }

  /**
   * Create a coder for a given type of JAXB annotated objects.
   *
   * @param jaxbClass the {@code Class} of the JAXB annotated objects.
   */
  public static <T> Coder<T> of(Class<T> jaxbClass) {
    return new SkipCloseCoder<>(new JAXBCoder<>(jaxbClass));
  }

  @Override
  public void encode(T value, OutputStream outStream) throws CoderException, IOException {
    encode(value, outStream, Context.NESTED);
  }

  @Override
  public void encode(T value, OutputStream outStream, Context context)
      throws CoderException, IOException {
    if (context.isWholeStream) {
      try {
        jaxbMarshaller.get().marshal(value, outStream);
      } catch (JAXBException e) {
        throw new CoderException(e);
      }
    } else {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try {
        jaxbMarshaller.get().marshal(value, baos);
      } catch (JAXBException e) {
        throw new CoderException(e);
      }
      VarInt.encode(baos.size(), outStream);
      baos.writeTo(outStream);
    }
  }

  @Override
  public T decode(InputStream inStream) throws CoderException, IOException {
    return decode(inStream, Context.NESTED);
  }

  @Override
  public T decode(InputStream inStream, Context context) throws CoderException, IOException {
    try {
      if (!context.isWholeStream) {
        long limit = VarInt.decodeLong(inStream);
        inStream = ByteStreams.limit(inStream, limit);
      }
      @SuppressWarnings("unchecked")
      T obj = (T) jaxbUnmarshaller.get().unmarshal(inStream);
      return obj;
    } catch (JAXBException e) {
      throw new CoderException(e);
    }
  }

  private JAXBContext getContext() throws JAXBException {
    if (jaxbContext == null) {
      synchronized (this) {
        if (jaxbContext == null) {
          jaxbContext = JAXBContext.newInstance(jaxbClass);
        }
      }
    }
    return jaxbContext;
  }

  @Override
  public TypeDescriptor<T> getEncodedTypeDescriptor() {
    return TypeDescriptor.of(jaxbClass);
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof JAXBCoder)) {
      return false;
    }
    JAXBCoder<?> that = (JAXBCoder<?>) other;
    return Objects.equals(this.jaxbClass, that.jaxbClass);
  }

  @Override
  public int hashCode() {
    return jaxbClass.hashCode();
  }
}
