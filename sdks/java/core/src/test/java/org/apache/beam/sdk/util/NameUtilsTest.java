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

package org.apache.beam.sdk.util;

import static org.junit.Assert.assertEquals;

import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PDone;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link NameUtils}.
 */
@RunWith(JUnit4.class)
public class NameUtilsTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  /**
   * Inner class for simple name test.
   */
  private class EmbeddedOldDoFn {

    private class DeeperEmbeddedOldDoFn extends EmbeddedOldDoFn {}

    private EmbeddedOldDoFn getEmbedded() {
      return new DeeperEmbeddedOldDoFn();
    }
  }

  private class EmbeddedPTransform extends PTransform<PBegin, PDone> {
    @Override
    public PDone expand(PBegin begin) {
      throw new IllegalArgumentException("Should never be applied");
    }

    private class Bound extends PTransform<PBegin, PDone> {
      @Override
      public PDone expand(PBegin begin) {
        throw new IllegalArgumentException("Should never be applied");
      }
    }

    private Bound getBound() {
      return new Bound();
    }
  }

  private interface AnonymousClass {
    Object getInnerClassInstance();
  }

  @Test
  public void testSimpleName() {
    assertEquals("Embedded", NameUtils.approximateSimpleName(EmbeddedOldDoFn.class));
  }

  @Test
  public void testAnonSimpleName() throws Exception {
    thrown.expect(IllegalArgumentException.class);

    EmbeddedOldDoFn anon = new EmbeddedOldDoFn(){};

    NameUtils.approximateSimpleName(anon.getClass());
  }

  @Test
  public void testNestedSimpleName() {
    EmbeddedOldDoFn fn = new EmbeddedOldDoFn();
    EmbeddedOldDoFn inner = fn.getEmbedded();

    assertEquals("DeeperEmbedded", NameUtils.approximateSimpleName(inner.getClass()));
  }

  @Test
  public void testPTransformName() {
    EmbeddedPTransform transform = new EmbeddedPTransform();
    assertEquals(
        "NameUtilsTest.EmbeddedPTransform",
        NameUtils.approximatePTransformName(transform.getClass()));
    assertEquals(
        "NameUtilsTest.EmbeddedPTransform",
        NameUtils.approximatePTransformName(transform.getBound().getClass()));
    assertEquals("TextIO.Write", NameUtils.approximatePTransformName(TextIO.Write.Bound.class));
  }

  @Test
  public void testPTransformNameWithAnonOuterClass() throws Exception {
    AnonymousClass anonymousClassObj = new AnonymousClass() {
      class NamedInnerClass extends PTransform<PBegin, PDone> {
        @Override
        public PDone expand(PBegin begin) {
          throw new IllegalArgumentException("Should never be applied");
        }
      }

      @Override
      public Object getInnerClassInstance() {
        return new NamedInnerClass();
      }
    };

    assertEquals("NamedInnerClass",
        NameUtils.approximateSimpleName(anonymousClassObj.getInnerClassInstance().getClass()));
    assertEquals("NameUtilsTest.NamedInnerClass",
        NameUtils.approximatePTransformName(
            anonymousClassObj.getInnerClassInstance().getClass()));
  }
}
