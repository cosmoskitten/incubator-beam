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
package org.apache.beam.sdk.transforms.reflect;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.CustomCoder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.ExtraContextFactory;
import org.apache.beam.sdk.transforms.DoFn.ProcessContinuation;
import org.apache.beam.sdk.transforms.OldDoFn;
import org.apache.beam.sdk.transforms.reflect.testhelper.DoFnInvokersTestHelper;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.util.TimeDomain;
import org.apache.beam.sdk.util.Timer;
import org.apache.beam.sdk.util.TimerSpec;
import org.apache.beam.sdk.util.TimerSpecs;
import org.apache.beam.sdk.util.UserCodeException;
import org.apache.beam.sdk.util.WindowingInternals;
import org.apache.beam.sdk.util.state.StateSpec;
import org.apache.beam.sdk.util.state.StateSpecs;
import org.apache.beam.sdk.util.state.ValueState;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.AdditionalAnswers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Tests for {@link DoFnInvokers}. */
@RunWith(JUnit4.class)
public class DoFnInvokersTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Mock private DoFn<String, String>.ProcessContext mockContext;
  @Mock private IntervalWindow mockWindow;
  @Mock private DoFn.InputProvider<String> mockInputProvider;
  @Mock private DoFn.OutputReceiver<String> mockOutputReceiver;
  @Mock private WindowingInternals<String, String> mockWindowingInternals;
  @Mock private ExtraContextFactory<String, String> extraContextFactory;

  @Mock private OldDoFn<String, String> mockOldDoFn;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    when(extraContextFactory.window()).thenReturn(mockWindow);
    when(extraContextFactory.inputProvider()).thenReturn(mockInputProvider);
    when(extraContextFactory.outputReceiver()).thenReturn(mockOutputReceiver);
    when(extraContextFactory.windowingInternals()).thenReturn(mockWindowingInternals);
  }

  private ProcessContinuation invokeProcessElement(DoFn<String, String> fn) {
    return DoFnInvokers.invokerFor(fn).invokeProcessElement(mockContext, extraContextFactory);
  }

  @Test
  public void testDoFnInvokersReused() throws Exception {
    // Ensures that we don't create a new Invoker class for every instance of the DoFn.
    IdentityParent fn1 = new IdentityParent();
    IdentityParent fn2 = new IdentityParent();
    assertSame(
        "Invoker classes should only be generated once for each type",
        DoFnInvokers.invokerFor(fn1).getClass(),
        DoFnInvokers.invokerFor(fn2).getClass());
  }

  // ---------------------------------------------------------------------------------------
  // Tests for general invocations of DoFn methods.
  // ---------------------------------------------------------------------------------------

  @Test
  public void testDoFnWithNoExtraContext() throws Exception {
    class MockFn extends DoFn<String, String> {
      @ProcessElement
      public void processElement(ProcessContext c) throws Exception {}
    }
    MockFn mockFn = mock(MockFn.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(mockFn));
    verify(mockFn).processElement(mockContext);
  }

  interface InterfaceWithProcessElement {
    @DoFn.ProcessElement
    void processElement(DoFn<String, String>.ProcessContext c);
  }

  interface LayersOfInterfaces extends InterfaceWithProcessElement {}

  private class IdentityUsingInterfaceWithProcessElement extends DoFn<String, String>
      implements LayersOfInterfaces {
    @Override
    public void processElement(DoFn<String, String>.ProcessContext c) {}
  }

  @Test
  public void testDoFnWithProcessElementInterface() throws Exception {
    IdentityUsingInterfaceWithProcessElement fn =
        mock(IdentityUsingInterfaceWithProcessElement.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    verify(fn).processElement(mockContext);
  }

  private class IdentityParent extends DoFn<String, String> {
    @ProcessElement
    public void process(ProcessContext c) {}
  }

  private class IdentityChildWithoutOverride extends IdentityParent {}

  private class IdentityChildWithOverride extends IdentityParent {
    @Override
    public void process(DoFn<String, String>.ProcessContext c) {
      super.process(c);
    }
  }

  @Test
  public void testDoFnWithMethodInSuperclass() throws Exception {
    IdentityChildWithoutOverride fn = mock(IdentityChildWithoutOverride.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    verify(fn).process(mockContext);
  }

  @Test
  public void testDoFnWithMethodInSubclass() throws Exception {
    IdentityChildWithOverride fn = mock(IdentityChildWithOverride.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    verify(fn).process(mockContext);
  }

  @Test
  public void testDoFnWithWindow() throws Exception {
    class MockFn extends DoFn<String, String> {
      @DoFn.ProcessElement
      public void processElement(ProcessContext c, IntervalWindow w) throws Exception {}
    }
    MockFn fn = mock(MockFn.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    verify(fn).processElement(mockContext, mockWindow);
  }

  /**
   * Tests that the generated {@link DoFnInvoker} passes the state parameter that it
   * should.
   */
  @Test
  public void testDoFnWithState() throws Exception {
    ValueState<Integer> mockState = mock(ValueState.class);
    final String stateId = "my-state-id-here";
    when(extraContextFactory.state(stateId)).thenReturn(mockState);

    class MockFn extends DoFn<String, String> {
      @StateId(stateId)
      private final StateSpec<Object, ValueState<Integer>> spec =
          StateSpecs.value(VarIntCoder.of());

      @ProcessElement
      public void processElement(ProcessContext c, @StateId(stateId) ValueState<Integer> valueState)
          throws Exception {}
    }
    MockFn fn = mock(MockFn.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    verify(fn).processElement(mockContext, mockState);
  }

  /**
   * Tests that the generated {@link DoFnInvoker} passes the timer parameter that it
   * should.
   */
  @Test
  public void testDoFnWithTimer() throws Exception {
    Timer mockTimer = mock(Timer.class);
    final String timerId = "my-timer-id-here";
    when(extraContextFactory.timer(timerId)).thenReturn(mockTimer);

    class MockFn extends DoFn<String, String> {
      @TimerId(timerId)
      private final TimerSpec spec = TimerSpecs.timer(TimeDomain.EVENT_TIME);

      @ProcessElement
      public void processElement(ProcessContext c, @TimerId(timerId) Timer timer)
          throws Exception {}

      @OnTimer(timerId)
      public void onTimer() {}
    }
    MockFn fn = mock(MockFn.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    verify(fn).processElement(mockContext, mockTimer);
  }

  @Test
  public void testDoFnWithOutputReceiver() throws Exception {
    class MockFn extends DoFn<String, String> {
      @DoFn.ProcessElement
      public void processElement(ProcessContext c, OutputReceiver<String> o) throws Exception {}
    }
    MockFn fn = mock(MockFn.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    verify(fn).processElement(mockContext, mockOutputReceiver);
  }

  @Test
  public void testDoFnWithInputProvider() throws Exception {
    class MockFn extends DoFn<String, String> {
      @DoFn.ProcessElement
      public void processElement(ProcessContext c, InputProvider<String> o) throws Exception {}
    }
    MockFn fn = mock(MockFn.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    verify(fn).processElement(mockContext, mockInputProvider);
  }

  @Test
  public void testDoFnWithReturn() throws Exception {
    class MockFn extends DoFn<String, String> {
      @DoFn.ProcessElement
      public ProcessContinuation processElement(ProcessContext c, SomeRestrictionTracker tracker)
          throws Exception {
        return null;
      }

      @GetInitialRestriction
      public SomeRestriction getInitialRestriction(String element) {
        return null;
      }

      @NewTracker
      public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
        return null;
      }
    }
    MockFn fn = mock(MockFn.class);
    when(fn.processElement(mockContext, null)).thenReturn(ProcessContinuation.resume());
    assertEquals(ProcessContinuation.resume(), invokeProcessElement(fn));
  }

  @Test
  public void testDoFnWithStartBundleSetupTeardown() throws Exception {
    class MockFn extends DoFn<String, String> {
      @ProcessElement
      public void processElement(ProcessContext c) {}

      @StartBundle
      public void startBundle(Context c) {}

      @FinishBundle
      public void finishBundle(Context c) {}

      @Setup
      public void before() {}

      @Teardown
      public void after() {}
    }
    MockFn fn = mock(MockFn.class);
    DoFnInvoker<String, String> invoker = DoFnInvokers.invokerFor(fn);
    invoker.invokeSetup();
    invoker.invokeStartBundle(mockContext);
    invoker.invokeFinishBundle(mockContext);
    invoker.invokeTeardown();
    verify(fn).before();
    verify(fn).startBundle(mockContext);
    verify(fn).finishBundle(mockContext);
    verify(fn).after();
  }

  // ---------------------------------------------------------------------------------------
  // Tests for invoking Splittable DoFn methods
  // ---------------------------------------------------------------------------------------
  private static class SomeRestriction {}

  private abstract static class SomeRestrictionTracker
      implements RestrictionTracker<SomeRestriction> {}

  private static class SomeRestrictionCoder extends CustomCoder<SomeRestriction> {
    public static SomeRestrictionCoder of() {
      return new SomeRestrictionCoder();
    }

    @Override
    public void encode(SomeRestriction value, OutputStream outStream, Context context) {}

    @Override
    public SomeRestriction decode(InputStream inStream, Context context) {
      return null;
    }
  }

  /** Public so Mockito can do "delegatesTo()" in the test below. */
  public static class MockFn extends DoFn<String, String> {
    @ProcessElement
    public ProcessContinuation processElement(ProcessContext c, SomeRestrictionTracker tracker) {
      return null;
    }

    @GetInitialRestriction
    public SomeRestriction getInitialRestriction(String element) {
      return null;
    }

    @SplitRestriction
    public void splitRestriction(
        String element, SomeRestriction restriction, OutputReceiver<SomeRestriction> receiver) {}

    @NewTracker
    public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
      return null;
    }

    @GetRestrictionCoder
    public SomeRestrictionCoder getRestrictionCoder() {
      return null;
    }
  }

  @Test
  public void testSplittableDoFnWithAllMethods() throws Exception {
    MockFn fn = mock(MockFn.class);
    DoFnInvoker<String, String> invoker = DoFnInvokers.invokerFor(fn);
    final SomeRestrictionTracker tracker = mock(SomeRestrictionTracker.class);
    final SomeRestrictionCoder coder = mock(SomeRestrictionCoder.class);
    SomeRestriction restriction = new SomeRestriction();
    final SomeRestriction part1 = new SomeRestriction();
    final SomeRestriction part2 = new SomeRestriction();
    final SomeRestriction part3 = new SomeRestriction();
    when(fn.getRestrictionCoder()).thenReturn(coder);
    when(fn.getInitialRestriction("blah")).thenReturn(restriction);
    doAnswer(
            AdditionalAnswers.delegatesTo(
                new MockFn() {
                  @DoFn.SplitRestriction
                  @Override
                  public void splitRestriction(
                      String element,
                      SomeRestriction restriction,
                      DoFn.OutputReceiver<SomeRestriction> receiver) {
                    receiver.output(part1);
                    receiver.output(part2);
                    receiver.output(part3);
                  }
                }))
        .when(fn)
        .splitRestriction(
            eq("blah"), same(restriction), Mockito.<DoFn.OutputReceiver<SomeRestriction>>any());
    when(fn.newTracker(restriction)).thenReturn(tracker);
    when(fn.processElement(mockContext, tracker)).thenReturn(ProcessContinuation.resume());

    assertEquals(coder, invoker.invokeGetRestrictionCoder(new CoderRegistry()));
    assertEquals(restriction, invoker.invokeGetInitialRestriction("blah"));
    final List<SomeRestriction> outputs = new ArrayList<>();
    invoker.invokeSplitRestriction(
        "blah",
        restriction,
        new DoFn.OutputReceiver<SomeRestriction>() {
          @Override
          public void output(SomeRestriction output) {
            outputs.add(output);
          }
        });
    assertEquals(Arrays.asList(part1, part2, part3), outputs);
    assertEquals(tracker, invoker.invokeNewTracker(restriction));
    assertEquals(
        ProcessContinuation.resume(),
        invoker.invokeProcessElement(
            mockContext,
            new DoFn.FakeExtraContextFactory<String, String>() {
              @Override
              public RestrictionTracker restrictionTracker() {
                return tracker;
              }
            }));
  }

  @Test
  public void testSplittableDoFnDefaultMethods() throws Exception {
    class MockFn extends DoFn<String, String> {
      @ProcessElement
      public void processElement(ProcessContext c, SomeRestrictionTracker tracker) {}

      @GetInitialRestriction
      public SomeRestriction getInitialRestriction(String element) {
        return null;
      }

      @NewTracker
      public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
        return null;
      }
    }
    MockFn fn = mock(MockFn.class);
    DoFnInvoker<String, String> invoker = DoFnInvokers.invokerFor(fn);

    CoderRegistry coderRegistry = new CoderRegistry();
    coderRegistry.registerCoder(SomeRestriction.class, SomeRestrictionCoder.class);
    assertThat(
        invoker.<SomeRestriction>invokeGetRestrictionCoder(coderRegistry),
        instanceOf(SomeRestrictionCoder.class));
    invoker.invokeSplitRestriction(
        "blah",
        "foo",
        new DoFn.OutputReceiver<String>() {
          private boolean invoked;

          @Override
          public void output(String output) {
            assertFalse(invoked);
            invoked = true;
            assertEquals("foo", output);
          }
        });
    assertEquals(
        ProcessContinuation.stop(), invoker.invokeProcessElement(mockContext, extraContextFactory));
  }

  // ---------------------------------------------------------------------------------------
  // Tests for ability to invoke private, inner and anonymous classes.
  // ---------------------------------------------------------------------------------------

  private static class PrivateDoFnClass extends DoFn<String, String> {
    @ProcessElement
    public void processThis(ProcessContext c) {}
  }

  @Test
  public void testLocalPrivateDoFnClass() throws Exception {
    PrivateDoFnClass fn = mock(PrivateDoFnClass.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    verify(fn).processThis(mockContext);
  }

  @Test
  public void testStaticPackagePrivateDoFnClass() throws Exception {
    DoFn<String, String> fn = mock(DoFnInvokersTestHelper.newStaticPackagePrivateDoFn().getClass());
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    DoFnInvokersTestHelper.verifyStaticPackagePrivateDoFn(fn, mockContext);
  }

  @Test
  public void testInnerPackagePrivateDoFnClass() throws Exception {
    DoFn<String, String> fn =
        mock(new DoFnInvokersTestHelper().newInnerPackagePrivateDoFn().getClass());
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    DoFnInvokersTestHelper.verifyInnerPackagePrivateDoFn(fn, mockContext);
  }

  @Test
  public void testStaticPrivateDoFnClass() throws Exception {
    DoFn<String, String> fn = mock(DoFnInvokersTestHelper.newStaticPrivateDoFn().getClass());
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    DoFnInvokersTestHelper.verifyStaticPrivateDoFn(fn, mockContext);
  }

  @Test
  public void testInnerPrivateDoFnClass() throws Exception {
    DoFn<String, String> fn = mock(new DoFnInvokersTestHelper().newInnerPrivateDoFn().getClass());
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    DoFnInvokersTestHelper.verifyInnerPrivateDoFn(fn, mockContext);
  }

  @Test
  public void testAnonymousInnerDoFn() throws Exception {
    DoFn<String, String> fn = mock(new DoFnInvokersTestHelper().newInnerAnonymousDoFn().getClass());
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    DoFnInvokersTestHelper.verifyInnerAnonymousDoFn(fn, mockContext);
  }

  @Test
  public void testStaticAnonymousDoFnInOtherPackage() throws Exception {
    // Can't use mockito for this one - the anonymous class is final and can't be mocked.
    DoFn<String, String> fn = DoFnInvokersTestHelper.newStaticAnonymousDoFn();
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    DoFnInvokersTestHelper.verifyStaticAnonymousDoFnInvoked(fn, mockContext);
  }

  // ---------------------------------------------------------------------------------------
  // Tests for wrapping exceptions.
  // ---------------------------------------------------------------------------------------

  @Test
  public void testProcessElementException() throws Exception {
    DoFnInvoker<Integer, Integer> invoker =
        DoFnInvokers.invokerFor(
            new DoFn<Integer, Integer>() {
              @ProcessElement
              public void processElement(@SuppressWarnings("unused") ProcessContext c) {
                throw new IllegalArgumentException("bogus");
              }
            });
    thrown.expect(UserCodeException.class);
    thrown.expectMessage("bogus");
    invoker.invokeProcessElement(null, null);
  }

  @Test
  public void testProcessElementExceptionWithReturn() throws Exception {
    thrown.expect(UserCodeException.class);
    thrown.expectMessage("bogus");
    DoFnInvokers
        .invokerFor(
            new DoFn<Integer, Integer>() {
              @ProcessElement
              public ProcessContinuation processElement(
                  @SuppressWarnings("unused") ProcessContext c, SomeRestrictionTracker tracker) {
                throw new IllegalArgumentException("bogus");
              }

              @GetInitialRestriction
              public SomeRestriction getInitialRestriction(Integer element) {
                return null;
              }

              @NewTracker
              public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
                return null;
              }
            })
        .invokeProcessElement(null, new DoFn.FakeExtraContextFactory<Integer, Integer>());
  }

  @Test
  public void testStartBundleException() throws Exception {
    DoFnInvoker<Integer, Integer> invoker =
        DoFnInvokers.invokerFor(
            new DoFn<Integer, Integer>() {
              @StartBundle
              public void startBundle(@SuppressWarnings("unused") Context c) {
                throw new IllegalArgumentException("bogus");
              }

              @ProcessElement
              public void processElement(@SuppressWarnings("unused") ProcessContext c) {}
            });
    thrown.expect(UserCodeException.class);
    thrown.expectMessage("bogus");
    invoker.invokeStartBundle(null);
  }

  @Test
  public void testFinishBundleException() throws Exception {
    DoFnInvoker<Integer, Integer> invoker =
        DoFnInvokers.invokerFor(
            new DoFn<Integer, Integer>() {
              @FinishBundle
              public void finishBundle(@SuppressWarnings("unused") Context c) {
                throw new IllegalArgumentException("bogus");
              }

              @ProcessElement
              public void processElement(@SuppressWarnings("unused") ProcessContext c) {}
            });
    thrown.expect(UserCodeException.class);
    thrown.expectMessage("bogus");
    invoker.invokeFinishBundle(null);
  }

  private class OldDoFnIdentity extends OldDoFn<String, String> {
    public void processElement(ProcessContext c) {}
  }

  @Test
  public void testOldDoFnProcessElement() throws Exception {
    new DoFnInvokers.OldDoFnInvoker<>(mockOldDoFn)
        .invokeProcessElement(mockContext, extraContextFactory);
    verify(mockOldDoFn).processElement(any(OldDoFn.ProcessContext.class));
  }

  @Test
  public void testOldDoFnStartBundle() throws Exception {
    new DoFnInvokers.OldDoFnInvoker<>(mockOldDoFn).invokeStartBundle(mockContext);
    verify(mockOldDoFn).startBundle(any(OldDoFn.Context.class));
  }

  @Test
  public void testOldDoFnFinishBundle() throws Exception {
    new DoFnInvokers.OldDoFnInvoker<>(mockOldDoFn).invokeFinishBundle(mockContext);
    verify(mockOldDoFn).finishBundle(any(OldDoFn.Context.class));
  }

  @Test
  public void testOldDoFnSetup() throws Exception {
    new DoFnInvokers.OldDoFnInvoker<>(mockOldDoFn).invokeSetup();
    verify(mockOldDoFn).setup();
  }

  @Test
  public void testOldDoFnTeardown() throws Exception {
    new DoFnInvokers.OldDoFnInvoker<>(mockOldDoFn).invokeTeardown();
    verify(mockOldDoFn).teardown();
  }
}
