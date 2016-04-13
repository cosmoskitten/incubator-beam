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
package org.apache.beam.sdk.testing;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import org.apache.beam.sdk.coders.Coder.Context;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.CustomCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import com.google.common.base.Strings;

import org.hamcrest.CoreMatchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Unit tests for {@link CoderProperties}. */
@RunWith(JUnit4.class)
public class CoderPropertiesTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void testGoodCoderIsDeterministic() throws Exception {
    CoderProperties.coderDeterministic(StringUtf8Coder.of(), "TestData", "TestData");
  }

  /** A coder that says it is not deterministic but actually is. */
  public static class NonDeterministicCoder extends CustomCoder<String> {
    @Override
    public void encode(String value, OutputStream outStream, Context context)
        throws CoderException, IOException {
      StringUtf8Coder.of().encode(value, outStream, context);
    }

    @Override
    public String decode(InputStream inStream, Context context)
        throws CoderException, IOException {
      return StringUtf8Coder.of().decode(inStream, context);
    }
  }

  @Test
  public void testNonDeterministicCoder() throws Exception {
    try {
      CoderProperties.coderDeterministic(new NonDeterministicCoder(), "TestData", "TestData");
      fail("Expected AssertionError");
    } catch (AssertionError error) {
      assertThat(error.getMessage(),
          CoreMatchers.containsString("Expected that the coder is deterministic"));
    }
  }

  @Test
  public void testPassingInNonEqualValuesWithDeterministicCoder() throws Exception {
    try {
      CoderProperties.coderDeterministic(StringUtf8Coder.of(), "AAA", "BBB");
      fail("Expected AssertionError");
    } catch (AssertionError error) {
      assertThat(error.getMessage(),
          CoreMatchers.containsString("Expected that the passed in values"));
    }
  }

  /** A coder that is non-deterministic because it adds a string to the value. */
  private static class BadDeterminsticCoder extends CustomCoder<String> {
    public BadDeterminsticCoder() {
    }

    @Override
    public void encode(String value, OutputStream outStream, Context context)
        throws IOException, CoderException {
      StringUtf8Coder.of().encode(value + System.nanoTime(), outStream, context);
    }

    @Override
    public String decode(InputStream inStream, Context context)
        throws CoderException, IOException {
      return StringUtf8Coder.of().decode(inStream, context);
    }

    @Override
    public void verifyDeterministic() throws NonDeterministicException { }
  }

  @Test
  public void testBadCoderIsNotDeterministic() throws Exception {
    try {
      CoderProperties.coderDeterministic(new BadDeterminsticCoder(), "TestData", "TestData");
      fail("Expected AssertionError");
    } catch (AssertionError error) {
      assertThat(error.getMessage(),
          CoreMatchers.containsString("<84>, <101>, <115>, <116>, <68>"));
    }
  }

  @Test
  public void testGoodCoderEncodesEqualValues() throws Exception {
    CoderProperties.coderDecodeEncodeEqual(StringUtf8Coder.of(), "TestData");
  }

  /** This coder changes state during encoding/decoding. */
  private static class StateChangingSerializingCoder extends CustomCoder<String> {
    private int changedState;

    public StateChangingSerializingCoder() {
      changedState = 10;
    }

    @Override
    public void encode(String value, OutputStream outStream, Context context)
        throws CoderException, IOException {
      changedState += 1;
      StringUtf8Coder.of().encode(value + Strings.repeat("A", changedState), outStream, context);
    }

    @Override
    public String decode(InputStream inStream, Context context)
        throws CoderException, IOException {
      String decodedValue = StringUtf8Coder.of().decode(inStream, context);
      return decodedValue.substring(0, decodedValue.length() - changedState);
    }
  }

  @Test
  public void testBadCoderThatDependsOnChangingState() throws Exception {
    try {
      CoderProperties.coderDecodeEncodeEqual(new StateChangingSerializingCoder(), "TestData");
      fail("Expected AssertionError");
    } catch (AssertionError error) {
      assertThat(error.getMessage(), CoreMatchers.containsString("TestData"));
    }
  }

  /** This coder loses information critical to its operation. */
  private static class ForgetfulSerializingCoder extends CustomCoder<String> {
    private transient int lostState;

    public ForgetfulSerializingCoder(int lostState) {
      this.lostState = lostState;
    }

    @Override
    public void encode(String value, OutputStream outStream, Context context)
        throws CoderException, IOException {
      if (lostState == 0) {
        throw new RuntimeException("I forgot something...");
      }
      StringUtf8Coder.of().encode(value, outStream, context);
    }

    @Override
    public String decode(InputStream inStream, Context context)
        throws CoderException, IOException {
      return StringUtf8Coder.of().decode(inStream, context);
    }
  }

  @Test
  public void testBadCoderThatDependsOnStateThatIsLost() throws Exception {
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage("I forgot something...");
    CoderProperties.coderDecodeEncodeEqual(new ForgetfulSerializingCoder(1), "TestData");
  }

  /** A coder which closes the underlying stream during encoding and decoding. */
  public static class ClosingCoder extends CustomCoder<String> {
    @Override
    public void encode(String value, OutputStream outStream, Context context) throws IOException {
      outStream.close();
    }

    @Override
    public String decode(InputStream inStream, Context context) throws IOException {
      inStream.close();
      return null;
    }
  }

  @Test
  public void testClosingCoderFailsWhenDecoding() throws Exception {
    expectedException.expect(UnsupportedOperationException.class);
    expectedException.expectMessage("Caller does not own the underlying");
    CoderProperties.decode(new ClosingCoder(), Context.NESTED, new byte[0]);
  }

  @Test
  public void testClosingCoderFailsWhenEncoding() throws Exception {
    expectedException.expect(UnsupportedOperationException.class);
    expectedException.expectMessage("Caller does not own the underlying");
    CoderProperties.encode(new ClosingCoder(), Context.NESTED, "test-value");
  }
}
