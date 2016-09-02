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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.ProcessContinuation;
import org.apache.beam.sdk.transforms.reflect.testhelper.DoFnInvokersTestHelper;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.UserCodeException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link DoFnInvokers}. */
@RunWith(JUnit4.class)
public class DoFnInvokersTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Mock private DoFn.ProcessContext mockContext;
  @Mock private BoundedWindow mockWindow;
  @Mock private DoFn.InputProvider<String> mockInputProvider;
  @Mock private DoFn.OutputReceiver<String> mockOutputReceiver;

  private DoFn.ExtraContextFactory<String, String> extraContextFactory;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    this.extraContextFactory =
        new DoFn.ExtraContextFactory<String, String>() {
          @Override
          public BoundedWindow window() {
            return mockWindow;
          }

          @Override
          public DoFn.InputProvider<String> inputProvider() {
            return mockInputProvider;
          }

          @Override
          public DoFn.OutputReceiver<String> outputReceiver() {
            return mockOutputReceiver;
          }

          @Override
          public <RestrictionT> RestrictionTracker<RestrictionT> restrictionTracker() {
            throw new UnsupportedOperationException("TODO");
          }
        };
  }

  private ProcessContinuation invokeProcessElement(DoFn<String, String> fn) {
    return DoFnInvokers.INSTANCE
        .newByteBuddyInvoker(fn)
        .invokeProcessElement(mockContext, extraContextFactory);
  }

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

  @Test
  public void testDoFnInvokersReused() throws Exception {
    // Ensures that we don't create a new Invoker class for every instance of the DoFn.
    IdentityParent fn1 = new IdentityParent();
    IdentityParent fn2 = new IdentityParent();
    assertSame(
        "Invoker classes should only be generated once for each type",
        DoFnInvokers.INSTANCE.newByteBuddyInvoker(fn1).getClass(),
        DoFnInvokers.INSTANCE.newByteBuddyInvoker(fn2).getClass());
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
      public void processElement(ProcessContext c, BoundedWindow w) throws Exception {}
    }
    MockFn fn = mock(MockFn.class);
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    verify(fn).processElement(mockContext, mockWindow);
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
      public ProcessContinuation processElement(ProcessContext c, InputProvider<String> o)
          throws Exception {
        return null;
      }
    }
    MockFn fn = mock(MockFn.class);
    when(fn.processElement(mockContext, mockInputProvider))
        .thenReturn(ProcessContinuation.resume());
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
    DoFnInvoker<String, String> invoker = DoFnInvokers.INSTANCE.newByteBuddyInvoker(fn);
    invoker.invokeSetup();
    invoker.invokeStartBundle(mockContext);
    invoker.invokeFinishBundle(mockContext);
    invoker.invokeTeardown();
    verify(fn).before();
    verify(fn).startBundle(mockContext);
    verify(fn).finishBundle(mockContext);
    verify(fn).after();
  }

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
    DoFnInvokersTestHelper.verifyStaticPackagePrivateDoFn(fn);
  }

  @Test
  public void testInnerPackagePrivateDoFnClass() throws Exception {
    DoFn<String, String> fn =
        mock(new DoFnInvokersTestHelper().newInnerPackagePrivateDoFn().getClass());
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    DoFnInvokersTestHelper.verifyInnerPackagePrivateDoFn(fn);
  }

  @Test
  public void testStaticPrivateDoFnClass() throws Exception {
    DoFn<String, String> fn = mock(DoFnInvokersTestHelper.newStaticPrivateDoFn().getClass());
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    DoFnInvokersTestHelper.verifyStaticPrivateDoFn(fn);
  }

  @Test
  public void testInnerPrivateDoFnClass() throws Exception {
    DoFn<String, String> fn = mock(new DoFnInvokersTestHelper().newInnerPrivateDoFn().getClass());
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    DoFnInvokersTestHelper.verifyInnerPrivateDoFn(fn);
  }

  @Test
  public void testAnonymousInnerDoFn() throws Exception {
    DoFn<String, String> fn = mock(new DoFnInvokersTestHelper().newInnerAnonymousDoFn().getClass());
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    DoFnInvokersTestHelper.verifyInnerAnonymousDoFn(fn);
  }

  @Test
  public void testStaticAnonymousDoFnInOtherPackage() throws Exception {
    // Can't use mockito for this one - the anonymous class is final and can't be mocked.
    DoFn<String, String> fn = DoFnInvokersTestHelper.newStaticAnonymousDoFn();
    assertEquals(ProcessContinuation.stop(), invokeProcessElement(fn));
    assertTrue(DoFnInvokersTestHelper.wasStaticAnonymousDoFnInvoked(fn));
  }

  @Test
  public void testProcessElementException() throws Exception {
    thrown.expect(UserCodeException.class);
    thrown.expectMessage("bogus");
    DoFnInvokers.INSTANCE
        .newByteBuddyInvoker(
            new DoFn<Integer, Integer>() {
              @ProcessElement
              public void processElement(@SuppressWarnings("unused") ProcessContext c) {
                throw new IllegalArgumentException("bogus");
              }
            })
        .invokeProcessElement(null, null);
  }

  @Test
  public void testProcessElementExceptionWithReturn() throws Exception {
    thrown.expect(UserCodeException.class);
    thrown.expectMessage("bogus");
    DoFnInvokers.INSTANCE
        .newByteBuddyInvoker(
            new DoFn<Integer, Integer>() {
              @ProcessElement
              public ProcessContinuation processElement(
                  @SuppressWarnings("unused") ProcessContext c) {
                throw new IllegalArgumentException("bogus");
              }
            })
        .invokeProcessElement(null, null);
  }

  @Test
  public void testStartBundleException() throws Exception {
    thrown.expect(UserCodeException.class);
    thrown.expectMessage("bogus");
    DoFnInvokers.INSTANCE
        .newByteBuddyInvoker(
            new DoFn<Integer, Integer>() {
              @StartBundle
              public void startBundle(@SuppressWarnings("unused") Context c) {
                throw new IllegalArgumentException("bogus");
              }

              @ProcessElement
              public void processElement(@SuppressWarnings("unused") ProcessContext c) {}
            })
        .invokeStartBundle(null);
  }

  @Test
  public void testFinishBundleException() throws Exception {
    thrown.expect(UserCodeException.class);
    thrown.expectMessage("bogus");

    DoFnInvokers.INSTANCE
        .newByteBuddyInvoker(
            new DoFn<Integer, Integer>() {
              @FinishBundle
              public void finishBundle(@SuppressWarnings("unused") Context c) {
                throw new IllegalArgumentException("bogus");
              }

              @ProcessElement
              public void processElement(@SuppressWarnings("unused") ProcessContext c) {}
            })
        .invokeFinishBundle(null);
  }
}
