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
package org.apache.beam.sdk.options;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.util.List;
import org.apache.beam.sdk.options.ValueProvider.RuntimeValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ValueProvider}. */
@RunWith(JUnit4.class)
public class ValueProviderTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  /** A test interface. */
  public static interface TestOptions extends PipelineOptions {
    @Default.String("bar")
    ValueProvider<String> getBar();
    void setBar(ValueProvider<String> bar);

    ValueProvider<String> getFoo();
    void setFoo(ValueProvider<String> foo);

    ValueProvider<List<Integer>> getList();
    void setList(ValueProvider<List<Integer>> list);
  }

  @Test
  public void testCommandLineNoDefault() {
    TestOptions options = PipelineOptionsFactory.fromArgs(
      new String[]{"--foo=baz"}).as(TestOptions.class);
    ValueProvider<String> provider = options.getFoo();
    assertEquals("baz", provider.get());
    assertTrue(provider.shouldValidate());
  }

  @Ignore
  @Test
  public void testListValueProvider() {
    TestOptions options = PipelineOptionsFactory.fromArgs(
      new String[]{"--list=1,2,3"}).as(TestOptions.class);
    ValueProvider<List<Integer>> provider = options.getList();
    assertEquals("baz", provider.get());
    assertTrue(provider.shouldValidate());
  }

  @Test
  public void testCommandLineWithDefault() {
    TestOptions options = PipelineOptionsFactory.fromArgs(
      new String[]{"--bar=baz"}).as(TestOptions.class);
    ValueProvider<String> provider = options.getBar();
    assertEquals("baz", provider.get());
    assertTrue(provider.shouldValidate());
  }

  @Test
  public void testStaticValueProvider() {
    ValueProvider<String> provider = StaticValueProvider.of("foo");
    assertEquals("foo", provider.get());
    assertTrue(provider.shouldValidate());
  }

  @Test
  public void testNoDefaultRuntimeProvider() {
    TestOptions options = PipelineOptionsFactory.as(TestOptions.class);
    ValueProvider<String> provider = options.getFoo();
    assertFalse(provider.shouldValidate());

    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage("Not called from a runtime context");
    provider.get();
  }

  @Test
  public void testDefaultRuntimeProvider() {
    TestOptions options = PipelineOptionsFactory.as(TestOptions.class);
    ValueProvider<String> provider = options.getBar();
    assertTrue(provider.shouldValidate());
    assertEquals("bar", provider.get());
  }

  @Test
  public void testNoDefaultRuntimeProviderWithOverride() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    TestOptions runtime = mapper.readValue(
      "{ \"options\": { \"foo\": \"quux\" }}", PipelineOptions.class)
      .as(TestOptions.class);

    TestOptions options = PipelineOptionsFactory.as(TestOptions.class);
    runtime.setOptionsId(options.getOptionsId());
    RuntimeValueProvider.setRuntimeOptions(runtime);

    ValueProvider<String> provider = options.getFoo();
    assertFalse(provider.shouldValidate());
    assertEquals("quux", provider.get());
  }

  @Test
  public void testDefaultRuntimeProviderWithOverride() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    TestOptions runtime = mapper.readValue(
      "{ \"options\": { \"bar\": \"quux\" }}", PipelineOptions.class)
      .as(TestOptions.class);

    TestOptions options = PipelineOptionsFactory.as(TestOptions.class);
    runtime.setOptionsId(options.getOptionsId());
    RuntimeValueProvider.setRuntimeOptions(runtime);

    ValueProvider<String> provider = options.getBar();
    assertTrue(provider.shouldValidate());
    assertEquals("quux", provider.get());
  }

  /** A test interface. */
  public static interface BadOptionsRuntime extends PipelineOptions {
    RuntimeValueProvider<String> getBar();
    void setBar(RuntimeValueProvider<String> bar);
  }

  @Test
  public void testOptionReturnTypeRuntime() {
    BadOptionsRuntime options = PipelineOptionsFactory.as(BadOptionsRuntime.class);
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage(
      "Method getBar should not have return type "
      + "RuntimeValueProvider, use ValueProvider instead.");
    RuntimeValueProvider<String> provider = options.getBar();
  }

  /** A test interface. */
  public static interface BadOptionsStatic extends PipelineOptions {
    StaticValueProvider<String> getBar();
    void setBar(StaticValueProvider<String> bar);
  }

  @Test
  public void testOptionReturnTypeStatic() {
    BadOptionsStatic options = PipelineOptionsFactory.as(BadOptionsStatic.class);
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage(
      "Method getBar should not have return type "
      + "StaticValueProvider, use ValueProvider instead.");
    StaticValueProvider<String> provider = options.getBar();
  }

  class TestObject implements Serializable {
    public ValueProvider<String> vp;

    public TestObject() {}
  }

  @Test
  @Ignore
  public void testRunnerOverrideOfValue() throws Exception {
    TestOptions submitOptions = PipelineOptionsFactory.as(TestOptions.class);
    ObjectMapper mapper = new ObjectMapper();
    String serializedOptions = mapper.writeValueAsString(submitOptions);

    // Create a test object that contains the ValueProvider, and serialize.
    TestObject testObject = new TestObject();
    testObject.vp = submitOptions.getFoo();
    String serializedObject = mapper.writeValueAsString(testObject);

    // This is the expected behavior of the runner: deserialize and set the
    // the runtime options.
    String anchor = "\"appName\":\"ValueProviderTest\"";
    String runnerString = serializedOptions.replaceAll(
      anchor, anchor + ",\"foo\":\"quux\"");
    TestOptions runtime = mapper.readValue(serializedOptions, PipelineOptions.class)
      .as(TestOptions.class);
    RuntimeValueProvider.setRuntimeOptions(runtime);

    testObject = mapper.readValue(serializedObject, TestObject.class);
    assertFalse(testObject.vp.shouldValidate());
    assertEquals("quux", testObject.vp.get());
  }
}
