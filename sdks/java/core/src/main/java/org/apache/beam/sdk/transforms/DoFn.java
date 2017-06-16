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
package org.apache.beam.sdk.transforms;

import java.io.Serializable;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.state.State;
import org.apache.beam.sdk.state.StateSpec;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.state.Timer;
import org.apache.beam.sdk.state.TimerSpec;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.HasDisplayData;
import org.apache.beam.sdk.transforms.splittabledofn.HasDefaultTracker;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.joda.time.Duration;
import org.joda.time.Instant;

/**
 * The argument to {@link ParDo} providing the code to use to process
 * elements of the input
 * {@link org.apache.beam.sdk.values.PCollection}.
 *
 * <p>See {@link ParDo} for more explanation, examples of use, and
 * discussion of constraints on {@code DoFn}s, including their
 * serializability, lack of access to global shared mutable state,
 * requirements for failure tolerance, and benefits of optimization.
 *
 * <p>{@code DoFn}s can be tested in a particular
 * {@code Pipeline} by running that {@code Pipeline} on sample input
 * and then checking its output.  Unit testing of a {@code DoFn},
 * separately from any {@code ParDo} transform or {@code Pipeline},
 * can be done via the {@link DoFnTester} harness.
 *
 * <p>Implementations must define a method annotated with {@link ProcessElement}
 * that satisfies the requirements described there. See the {@link ProcessElement}
 * for details.
 *
 * <p>Example usage:
 *
 * <pre><code>
 * {@literal PCollection<String>} lines = ... ;
 * {@literal PCollection<String>} words =
 *     {@literal lines.apply(ParDo.of(new DoFn<String, String>())} {
 *         {@literal @ProcessElement}
 *          public void processElement(ProcessContext c, BoundedWindow window) {
 *            ...
 *          }}));
 * </code></pre>
 *
 * @param <InputT> the type of the (main) input elements
 * @param <OutputT> the type of the (main) output elements
 */
public abstract class DoFn<InputT, OutputT> implements Serializable, HasDisplayData {
  /**
   * Information accessible while within the {@link StartBundle} method.
   */
  public abstract class StartBundleContext {
    /**
     * Returns the {@code PipelineOptions} specified with the {@link
     * org.apache.beam.sdk.PipelineRunner} invoking this {@code DoFn}. The {@code
     * PipelineOptions} will be the default running via {@link DoFnTester}.
     */
    public abstract PipelineOptions getPipelineOptions();
  }

  /**
   * Information accessible while within the {@link FinishBundle} method.
   */
  public abstract class FinishBundleContext {
    /**
     * Returns the {@code PipelineOptions} specified with the {@link
     * org.apache.beam.sdk.PipelineRunner} invoking this {@code DoFn}. The {@code
     * PipelineOptions} will be the default running via {@link DoFnTester}.
     */
    public abstract PipelineOptions getPipelineOptions();

    /**
     * Adds the given element to the main output {@code PCollection} at the given
     * timestamp in the given window.
     *
     * <p>Once passed to {@code output} the element should not be modified in
     * any way.
     *
     * <p><i>Note:</i> A splittable {@link DoFn} is not allowed to output from the
     * {@link FinishBundle} method.
     */
    public abstract void output(OutputT output, Instant timestamp, BoundedWindow window);

    /**
     * Adds the given element to the output {@code PCollection} with the given tag at the given
     * timestamp in the given window.
     *
     * <p>Once passed to {@code output} the element should not be modified in any way.
     *
     * <p><i>Note:</i> A splittable {@link DoFn} is not allowed to output from the {@link
     * FinishBundle} method.
     */
    public abstract <T> void output(
        TupleTag<T> tag, T output, Instant timestamp, BoundedWindow window);
  }

  /**
   * Information accessible to all methods in this {@link DoFn} where the context is in some window.
   */
  public abstract class WindowedContext {
    /**
     * Returns the {@code PipelineOptions} specified with the {@link
     * org.apache.beam.sdk.PipelineRunner} invoking this {@code DoFn}. The {@code
     * PipelineOptions} will be the default running via {@link DoFnTester}.
     */
    public abstract PipelineOptions getPipelineOptions();

    /**
     * Adds the given element to the main output {@code PCollection}.
     *
     * <p>Once passed to {@code output} the element should not be modified in
     * any way.
     *
     * <p>If invoked from {@link ProcessElement}, the output
     * element will have the same timestamp and be in the same windows
     * as the input element passed to the method annotated with
     * {@code @ProcessElement}.
     *
     * <p>If invoked from {@link StartBundle} or {@link FinishBundle},
     * this will attempt to use the
     * {@link org.apache.beam.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element. The output element
     * will have a timestamp of negative infinity.
     *
     * <p><i>Note:</i> A splittable {@link DoFn} is not allowed to output from
     * {@link StartBundle} or {@link FinishBundle} methods.
     */
    public abstract void output(OutputT output);

    /**
     * Adds the given element to the main output {@code PCollection},
     * with the given timestamp.
     *
     * <p>Once passed to {@code outputWithTimestamp} the element should not be
     * modified in any way.
     *
     * <p>If invoked from {@link ProcessElement}), the timestamp
     * must not be older than the input element's timestamp minus
     * {@link DoFn#getAllowedTimestampSkew}.  The output element will
     * be in the same windows as the input element.
     *
     * <p>If invoked from {@link StartBundle} or {@link FinishBundle},
     * this will attempt to use the
     * {@link org.apache.beam.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element except for the
     * timestamp.
     *
     * <p><i>Note:</i> A splittable {@link DoFn} is not allowed to output from
     * {@link StartBundle} or {@link FinishBundle} methods.
     */
    public abstract void outputWithTimestamp(OutputT output, Instant timestamp);

    /**
     * Adds the given element to the output {@code PCollection} with the
     * given tag.
     *
     * <p>Once passed to {@code output} the element should not be modified
     * in any way.
     *
     * <p>The caller of {@code ParDo} uses {@link ParDo.SingleOutput#withOutputTags} to
     * specify the tags of outputs that it consumes. Non-consumed
     * outputs, e.g., outputs for monitoring purposes only, don't necessarily
     * need to be specified.
     *
     * <p>The output element will have the same timestamp and be in the same
     * windows as the input element passed to {@link ProcessElement}).
     *
     * <p>If invoked from {@link StartBundle} or {@link FinishBundle},
     * this will attempt to use the
     * {@link org.apache.beam.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element. The output element
     * will have a timestamp of negative infinity.
     *
     * <p><i>Note:</i> A splittable {@link DoFn} is not allowed to output from
     * {@link StartBundle} or {@link FinishBundle} methods.
     *
     * @see ParDo.SingleOutput#withOutputTags
     */
    public abstract <T> void output(TupleTag<T> tag, T output);

    /**
     * Adds the given element to the specified output {@code PCollection},
     * with the given timestamp.
     *
     * <p>Once passed to {@code outputWithTimestamp} the element should not be
     * modified in any way.
     *
     * <p>If invoked from {@link ProcessElement}), the timestamp
     * must not be older than the input element's timestamp minus
     * {@link DoFn#getAllowedTimestampSkew}.  The output element will
     * be in the same windows as the input element.
     *
     * <p>If invoked from {@link StartBundle} or {@link FinishBundle},
     * this will attempt to use the
     * {@link org.apache.beam.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element except for the
     * timestamp.
     *
     * <p><i>Note:</i> A splittable {@link DoFn} is not allowed to output from
     * {@link StartBundle} or {@link FinishBundle} methods.
     *
     * @see ParDo.SingleOutput#withOutputTags
     */
    public abstract <T> void outputWithTimestamp(
        TupleTag<T> tag, T output, Instant timestamp);
  }

  /**
   * Information accessible when running a {@link DoFn.ProcessElement} method.
   */
  public abstract class ProcessContext extends WindowedContext {

    /**
     * Returns the input element to be processed.
     *
     * <p>The element will not be changed -- it is safe to cache, etc.
     * without copying.
     */
    public abstract InputT element();

    /**
     * Returns the value of the side input.
     *
     * @throws IllegalArgumentException if this is not a side input
     * @see ParDo.SingleOutput#withSideInputs
     */
    public abstract <T> T sideInput(PCollectionView<T> view);

    /**
     * Returns the timestamp of the input element.
     *
     * <p>See {@link Window} for more information.
     */
    public abstract Instant timestamp();

    /**
     * Returns information about the pane within this window into which the
     * input element has been assigned.
     *
     * <p>Generally all data is in a single, uninteresting pane unless custom
     * triggering and/or late data has been explicitly requested.
     * See {@link Window} for more information.
     */
    public abstract PaneInfo pane();

    /**
     * Gives the runner a (best-effort) lower bound about the timestamps of future output associated
     * with the current element.
     *
     * <p>If the {@link DoFn} has multiple outputs, the watermark applies to all of them.
     *
     * <p>Only splittable {@link DoFn DoFns} are allowed to call this method. It is safe to call
     * this method from a different thread than the one running {@link ProcessElement}, but
     * all calls must finish before {@link ProcessElement} returns.
     */
    public abstract void updateWatermark(Instant watermark);
  }

  /**
   * Information accessible when running a {@link DoFn.OnTimer} method.
   */
  public abstract class OnTimerContext extends WindowedContext {

    /**
     * Returns the timestamp of the current timer.
     */
    public abstract Instant timestamp();

    /**
     * Returns the window in which the timer is firing.
     */
    public abstract BoundedWindow window();

    /**
     * Returns the time domain of the current timer.
     */
    public abstract TimeDomain timeDomain();
  }

  /**
   * Returns the allowed timestamp skew duration, which is the maximum duration that timestamps can
   * be shifted backward in {@link WindowedContext#outputWithTimestamp}.
   *
   * <p>The default value is {@code Duration.ZERO}, in which case timestamps can only be shifted
   * forward to future. For infinite skew, return {@code Duration.millis(Long.MAX_VALUE)}.
   *
   * @deprecated This method permits a {@link DoFn} to emit elements behind the watermark. These
   *     elements are considered late, and if behind the
   *     {@link Window#withAllowedLateness(Duration) allowed lateness} of a downstream
   *     {@link PCollection} may be silently dropped. See
   *     https://issues.apache.org/jira/browse/BEAM-644 for details on a replacement.
   *
   */
  @Deprecated
  public Duration getAllowedTimestampSkew() {
    return Duration.ZERO;
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * Returns a {@link TypeDescriptor} capturing what is known statically
   * about the input type of this {@code DoFn} instance's most-derived
   * class.
   *
   * <p>See {@link #getOutputTypeDescriptor} for more discussion.
   */
  public TypeDescriptor<InputT> getInputTypeDescriptor() {
    return new TypeDescriptor<InputT>(getClass()) {};
  }

  /**
   * Returns a {@link TypeDescriptor} capturing what is known statically
   * about the output type of this {@code DoFn} instance's
   * most-derived class.
   *
   * <p>In the normal case of a concrete {@code DoFn} subclass with
   * no generic type parameters of its own (including anonymous inner
   * classes), this will be a complete non-generic type, which is good
   * for choosing a default output {@code Coder<O>} for the output
   * {@code PCollection<O>}.
   */
  public TypeDescriptor<OutputT> getOutputTypeDescriptor() {
    return new TypeDescriptor<OutputT>(getClass()) {};
  }

  /** Receives values of the given type. */
  public interface OutputReceiver<T> {
    void output(T output);
  }
  /////////////////////////////////////////////////////////////////////////////

  /**
   * Annotation for declaring and dereferencing state cells.
   *
   * <p>To declare a state cell, create a field of type {@link StateSpec} annotated with a
   * {@link StateId}. To use the cell during processing, add a parameter of the appropriate {@link
   * State} subclass to your {@link ProcessElement @ProcessElement} or {@link OnTimer @OnTimer}
   * method, and annotate it with {@link StateId}. See the following code for an example:
   *
   * <pre><code>{@literal new DoFn<KV<Key, Foo>, Baz>()} {
   *
   *  {@literal @StateId("my-state-id")}
   *  {@literal private final StateSpec<K, ValueState<MyState>>} myStateSpec =
   *       StateSpecs.value(new MyStateCoder());
   *
   *  {@literal @ProcessElement}
   *   public void processElement(
   *       ProcessContext c,
   *      {@literal @StateId("my-state-id") ValueState<MyState> myState}) {
   *     myState.read();
   *     myState.write(...);
   *   }
   * }
   * </code></pre>
   *
   * <p>State is subject to the following validity conditions:
   *
   * <ul>
   * <li>Each state ID must be declared at most once.
   * <li>Any state referenced in a parameter must be declared with the same state type.
   * <li>State declarations must be final.
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.PARAMETER})
  @Experimental(Kind.STATE)
  public @interface StateId {
    /** The state ID. */
    String value();
  }

  /**
   * Annotation for declaring and dereferencing timers.
   *
   * <p>To declare a timer, create a field of type {@link TimerSpec} annotated with a {@link
   * TimerId}. To use the cell during processing, add a parameter of the type {@link Timer} to your
   * {@link ProcessElement @ProcessElement} or {@link OnTimer @OnTimer} method, and annotate it with
   * {@link TimerId}. See the following code for an example:
   *
   * <pre><code>{@literal new DoFn<KV<Key, Foo>, Baz>()} {
   *   {@literal @TimerId("my-timer-id")}
   *    private final TimerSpec myTimer = TimerSpecs.timerForDomain(TimeDomain.EVENT_TIME);
   *
   *   {@literal @ProcessElement}
   *    public void processElement(
   *        ProcessContext c,
   *       {@literal @TimerId("my-timer-id") Timer myTimer}) {
   *      myTimer.offset(Duration.standardSeconds(...)).setRelative();
   *    }
   *
   *   {@literal @OnTimer("my-timer-id")}
   *    public void onMyTimer() {
   *      ...
   *    }
   * }</code></pre>
   *
   * <p>Timers are subject to the following validity conditions:
   *
   * <ul>
   * <li>Each timer must have a distinct id.
   * <li>Any timer referenced in a parameter must be declared.
   * <li>Timer declarations must be final.
   * <li>All declared timers must have a corresponding callback annotated with {@link
   *     OnTimer @OnTimer}.
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.PARAMETER})
  @Experimental(Kind.TIMERS)
  public @interface TimerId {
    /** The timer ID. */
    String value();
  }

  /**
   * Annotation for registering a callback for a timer.
   *
   * <p>See the javadoc for {@link TimerId} for use in a full example.
   *
   * <p>The method annotated with {@code @OnTimer} may have parameters according to the same logic
   * as {@link ProcessElement}, but limited to the {@link BoundedWindow}, {@link State} subclasses,
   * and {@link Timer}. State and timer parameters must be annotated with their {@link StateId} and
   * {@link TimerId} respectively.
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @Experimental(Kind.TIMERS)
  public @interface OnTimer {
    /** The timer ID. */
    String value();
  }

  /**
   * Annotation for the method to use to prepare an instance for processing bundles of elements. The
   * method annotated with this must satisfy the following constraints
   * <ul>
   *   <li>It must have zero arguments.
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Setup {
  }

  /**
   * Annotation for the method to use to prepare an instance for processing a batch of elements.
   * The method annotated with this must satisfy the following constraints:
   * <ul>
   *   <li>It must have exactly zero or one arguments.
   *   <li>If it has any arguments, its only argument must be a {@link DoFn.StartBundleContext}.
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface StartBundle {}

  /**
   * Annotation for the method to use for processing elements. A subclass of {@link DoFn} must have
   * a method with this annotation.
   *
   * <p>The signature of this method must satisfy the following constraints:
   *
   * <ul>
   * <li>Its first argument must be a {@link DoFn.ProcessContext}.
   * <li>If one of its arguments is a subtype of {@link RestrictionTracker}, then it is a <a
   *     href="https://s.apache.org/splittable-do-fn">splittable</a> {@link DoFn} subject to the
   *     separate requirements described below. Items below are assuming this is not a splittable
   *     {@link DoFn}.
   * <li>If one of its arguments is a subtype of {@link BoundedWindow} then it will
   *     be passed the window of the current element. When applied by {@link ParDo} the subtype
   *     of {@link BoundedWindow} must match the type of windows on the input {@link PCollection}.
   *     If the window is not accessed a runner may perform additional optimizations.
   * <li>It must return {@code void}.
   * </ul>
   *
   * <h2>Splittable DoFn's (WARNING: work in progress, do not use)</h2>
   *
   * <p>A {@link DoFn} is <i>splittable</i> if its {@link ProcessElement} method has a parameter
   * whose type is a subtype of {@link RestrictionTracker}. This is an advanced feature and an
   * overwhelming majority of users will never need to write a splittable {@link DoFn}. Right now
   * the implementation of this feature is in progress and it's not ready for any use.
   *
   * <p>See <a href="https://s.apache.org/splittable-do-fn">the proposal</a> for an overview of the
   * involved concepts (<i>splittable DoFn</i>, <i>restriction</i>, <i>restriction tracker</i>).
   *
   * <p>If a {@link DoFn} is splittable, the following constraints must be respected:
   *
   * <ul>
   * <li>It <i>must</i> define a {@link GetInitialRestriction} method.
   * <li>It <i>may</i> define a {@link SplitRestriction} method.
   * <li>It <i>may</i> define a {@link NewTracker} method returning the same type as the type of
   *     the {@link RestrictionTracker} argument of {@link ProcessElement}, which in turn must be a
   *     subtype of {@code RestrictionTracker<R>} where {@code R} is the restriction type returned
   *     by {@link GetInitialRestriction}. This method is optional in case the restriction type
   *     returned by {@link GetInitialRestriction} implements {@link HasDefaultTracker}.
   * <li>It <i>may</i> define a {@link GetRestrictionCoder} method.
   * <li>The type of restrictions used by all of these methods must be the same.
   * <li>Its {@link ProcessElement} method <i>must not</i> use any extra context parameters, such as
   *     {@link BoundedWindow}.
   * <li>The {@link DoFn} itself <i>may</i> be annotated with {@link BoundedPerElement} or
   *     {@link UnboundedPerElement}, but not both at the same time. If it's not annotated with
   *     either of these, it's assumed to be {@link BoundedPerElement}.
   * </ul>
   *
   * <p>A non-splittable {@link DoFn} <i>must not</i> define any of these methods.
   *
   * <p>More documentation will be added when the feature becomes ready for general usage.
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface ProcessElement {}

  /**
   * Annotation for the method to use to finish processing a batch of elements.
   * The method annotated with this must satisfy the following constraints:
   * <ul>
   *   <li>It must have exactly zero or one arguments.
   *   <li>If it has any arguments, its only argument must be a {@link DoFn.FinishBundleContext}.
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface FinishBundle {}


  /**
   * Annotation for the method to use to clean up this instance after processing bundles of
   * elements. No other method will be called after a call to the annotated method is made.
   * The method annotated with this must satisfy the following constraint:
   * <ul>
   *   <li>It must have zero arguments.
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Teardown {
  }

  /**
   * Annotation for the method that maps an element to an initial restriction for a <a
   * href="https://s.apache.org/splittable-do-fn">splittable</a> {@link DoFn}.
   *
   * <p>Signature: {@code RestrictionT getInitialRestriction(InputT element);}
   *
   * <p>TODO: Make the InputT parameter optional.
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @Experimental(Kind.SPLITTABLE_DO_FN)
  public @interface GetInitialRestriction {}

  /**
   * Annotation for the method that returns the coder to use for the restriction of a <a
   * href="https://s.apache.org/splittable-do-fn">splittable</a> {@link DoFn}.
   *
   * <p>If not defined, a coder will be inferred using standard coder inference rules and the
   * pipeline's {@link Pipeline#getCoderRegistry coder registry}.
   *
   * <p>This method will be called only at pipeline construction time.
   *
   * <p>Signature: {@code Coder<RestrictionT> getRestrictionCoder();}
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @Experimental(Kind.SPLITTABLE_DO_FN)
  public @interface GetRestrictionCoder {}

  /**
   * Annotation for the method that splits restriction of a <a
   * href="https://s.apache.org/splittable-do-fn">splittable</a> {@link DoFn} into multiple parts to
   * be processed in parallel.
   *
   * <p>Signature: {@code List<RestrictionT> splitRestriction( InputT element, RestrictionT
   * restriction);}
   *
   * <p>Optional: if this method is omitted, the restriction will not be split (equivalent to
   * defining the method and returning {@code Collections.singletonList(restriction)}).
   *
   * <p>TODO: Introduce a parameter for controlling granularity of splitting, e.g. numParts. TODO:
   * Make the InputT parameter optional.
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @Experimental(Kind.SPLITTABLE_DO_FN)
  public @interface SplitRestriction {}

  /**
   * Annotation for the method that creates a new {@link RestrictionTracker} for the restriction of
   * a <a href="https://s.apache.org/splittable-do-fn">splittable</a> {@link DoFn}.
   *
   * <p>Signature: {@code MyRestrictionTracker newTracker(RestrictionT restriction);} where {@code
   * MyRestrictionTracker} must be a subtype of {@code RestrictionTracker<RestrictionT>}.
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @Experimental(Kind.SPLITTABLE_DO_FN)
  public @interface NewTracker {}

  /**
   * Annotation on a <a href="https://s.apache.org/splittable-do-fn">splittable</a> {@link DoFn}
   * specifying that the {@link DoFn} performs a bounded amount of work per input element, so
   * applying it to a bounded {@link PCollection} will produce also a bounded {@link PCollection}.
   * It is an error to specify this on a non-splittable {@link DoFn}.
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @Experimental(Kind.SPLITTABLE_DO_FN)
  public @interface BoundedPerElement {}

  /**
   * Annotation on a <a href="https://s.apache.org/splittable-do-fn">splittable</a> {@link DoFn}
   * specifying that the {@link DoFn} performs an unbounded amount of work per input element, so
   * applying it to a bounded {@link PCollection} will produce an unbounded {@link PCollection}. It
   * is an error to specify this on a non-splittable {@link DoFn}.
   */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @Experimental(Kind.SPLITTABLE_DO_FN)
  public @interface UnboundedPerElement {}

  /** Temporary, do not use. See https://issues.apache.org/jira/browse/BEAM-1904 */
  public class ProcessContinuation {}

  /**
   * Finalize the {@link DoFn} construction to prepare for processing.
   * This method should be called by runners before any processing methods.
   */
  @Deprecated
  public final void prepareForProcessing() {}

  /**
   * {@inheritDoc}
   *
   * <p>By default, does not register any display data. Implementors may override this method
   * to provide their own display data.
   */
  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
  }
}
