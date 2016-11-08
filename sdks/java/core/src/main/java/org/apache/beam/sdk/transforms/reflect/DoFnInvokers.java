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

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.ExtraContextFactory;
import org.apache.beam.sdk.transforms.DoFnAdapters;
import org.apache.beam.sdk.transforms.OldDoFn;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.util.UserCodeException;

/** Static utilities for working with {@link DoFnInvoker}. */
public class DoFnInvokers {

  /**
   * Returns a {@link DoFnInvoker} for the given {@link DoFn}, using the default approach of
   * generating a {@link DoFnInvoker} subclass.
   */
  public static <InputT, OutputT> DoFnInvoker<InputT, OutputT> invokerFor(
      DoFn<InputT, OutputT> fn) {
    return ByteBuddyDoFnInvokerFactory.only().newByteBuddyInvoker(fn);
  }

  /**
   * A cache of constructors of generated {@link DoFnInvoker} classes, keyed by {@link DoFn} class.
   * Needed because generating an invoker class is expensive, and to avoid generating an excessive
   * number of classes consuming PermGen memory.
   */
  private final Map<Class<?>, Constructor<?>> byteBuddyInvokerConstructorCache =
      new LinkedHashMap<>();

  private DoFnInvokers() {}

  /**
   * Creates a {@link DoFnInvoker} for the given {@link Object}, which should be either a {@link
   * DoFn} or an {@link OldDoFn}. The expected use would be to deserialize a user's function as an
   * {@link Object} and then pass it to this method, so there is no need to statically specify what
   * sort of object it is.
   *
   * @deprecated this is to be used only as a migration path for decoupling upgrades
   */
  @Deprecated
  public static DoFnInvoker<?, ?> invokerFor(Object deserializedFn) {
    if (deserializedFn instanceof DoFn) {
      return invokerFor((DoFn<?, ?>) deserializedFn);
    } else if (deserializedFn instanceof OldDoFn) {
      return new OldDoFnInvoker<>((OldDoFn<?, ?>) deserializedFn);
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Cannot create a %s for %s; it should be either a %s or an %s.",
              DoFnInvoker.class.getSimpleName(),
              deserializedFn.toString(),
              DoFn.class.getSimpleName(),
              OldDoFn.class.getSimpleName()));
    }
  }

  static class OldDoFnInvoker<InputT, OutputT> implements DoFnInvoker<InputT, OutputT> {

    private final OldDoFn<InputT, OutputT> fn;

    public OldDoFnInvoker(OldDoFn<InputT, OutputT> fn) {
      this.fn = fn;
    }

    @Override
    public DoFn.ProcessContinuation invokeProcessElement(
        DoFn<InputT, OutputT>.ProcessContext c, ExtraContextFactory<InputT, OutputT> extra) {
      OldDoFn<InputT, OutputT>.ProcessContext oldCtx =
          DoFnAdapters.adaptProcessContext(fn, c, extra);
      try {
        fn.processElement(oldCtx);
        return DoFn.ProcessContinuation.stop();
      } catch (Throwable exc) {
        throw UserCodeException.wrap(exc);
      }
    }

    @Override
    public void invokeStartBundle(DoFn.Context c) {
      OldDoFn<InputT, OutputT>.Context oldCtx = DoFnAdapters.adaptContext(fn, c);
      try {
        fn.startBundle(oldCtx);
      } catch (Throwable exc) {
        throw UserCodeException.wrap(exc);
      }
    }

    @Override
    public void invokeFinishBundle(DoFn.Context c) {
      OldDoFn<InputT, OutputT>.Context oldCtx = DoFnAdapters.adaptContext(fn, c);
      try {
        fn.finishBundle(oldCtx);
      } catch (Throwable exc) {
        throw UserCodeException.wrap(exc);
      }
    }

    @Override
    public void invokeSetup() {
      try {
        fn.setup();
      } catch (Throwable exc) {
        throw UserCodeException.wrap(exc);
      }
    }

    @Override
    public void invokeTeardown() {
      try {
        fn.teardown();
      } catch (Throwable exc) {
        throw UserCodeException.wrap(exc);
      }
    }

    @Override
    public <RestrictionT> RestrictionT invokeGetInitialRestriction(InputT element) {
      throw new UnsupportedOperationException("OldDoFn is not splittable");
    }

    @Override
    public <RestrictionT> Coder<RestrictionT> invokeGetRestrictionCoder(
        CoderRegistry coderRegistry) {
      throw new UnsupportedOperationException("OldDoFn is not splittable");
    }

    @Override
    public <RestrictionT> void invokeSplitRestriction(
        InputT element, RestrictionT restriction, DoFn.OutputReceiver<RestrictionT> receiver) {
      throw new UnsupportedOperationException("OldDoFn is not splittable");
    }

    @Override
    public <RestrictionT, TrackerT extends RestrictionTracker<RestrictionT>>
        TrackerT invokeNewTracker(RestrictionT restriction) {
      throw new UnsupportedOperationException("OldDoFn is not splittable");
    }
  }
}
