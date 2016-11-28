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

package org.apache.beam.runners.spark;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.beam.runners.spark.aggregators.AccumulatorSingleton;
import org.apache.beam.runners.spark.translation.EvaluationContext;
import org.apache.beam.runners.spark.translation.SparkContextFactory;
import org.apache.beam.sdk.AggregatorRetrievalException;
import org.apache.beam.sdk.AggregatorValues;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.transforms.Aggregator;
import org.apache.beam.sdk.util.UserCodeException;
import org.apache.spark.SparkException;
import org.joda.time.Duration;

/**
 * Represents a Spark pipeline execution result.
 */
public abstract class SparkPipelineResult implements PipelineResult {

  protected final Future pipelineExecutionCommenced;
  protected final EvaluationContext context;

  protected PipelineResult.State state;

  SparkPipelineResult(final Future<?> pipelineExecutionCommenced,
                      final EvaluationContext evaluationContext) {
    this.pipelineExecutionCommenced = pipelineExecutionCommenced;
    this.context = evaluationContext;
    // pipelineExecutionCommenced is expected to have started executing eagerly.
    state = State.RUNNING;
  }

  protected RuntimeException runtimeExceptionFrom(Throwable e) {
    return (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
  }

  protected abstract void stop();

  protected abstract State awaitTermination(Duration duration)
      throws TimeoutException, ExecutionException, InterruptedException;

  public <T> T getAggregatorValue(String named, Class<T> resultType) {
    return
        context.getRuntimeContext()
               .getAggregatorValue(AccumulatorSingleton.getInstance(context.getSparkContext()),
                                   named,
                                   resultType);
  }

  @Override
  public PipelineResult.State getState() {
    return state;
  }

  @Override
  public PipelineResult.State waitUntilFinish() {
    return waitUntilFinish(Duration.millis(Long.MAX_VALUE));
  }

  @Override
  public State waitUntilFinish(Duration duration) {
    try {
      state = awaitTermination(duration);
    } catch (TimeoutException e) {
      state = null;
    } catch (ExecutionException e) {
      state = PipelineResult.State.FAILED;
      throw beamExceptionFrom(e.getCause());
    } catch (Exception e) {
      state = PipelineResult.State.FAILED;
      throw beamExceptionFrom(e);
    } finally {
      stop();
    }

    return state;
  }

  @Override
  public <T> AggregatorValues<T> getAggregatorValues(Aggregator<?, T> aggregator)
      throws AggregatorRetrievalException {
    return context.getAggregatorValues(aggregator);
  }

  @Override
  public MetricResults metrics() {
    throw new UnsupportedOperationException("The SparkRunner does not currently support metrics.");
  }

  @Override
  public PipelineResult.State cancel() throws IOException {
    if (!state.isTerminal()) {
      stop();
      state = PipelineResult.State.CANCELLED;
    }

    return state;
  }

  protected RuntimeException beamExceptionFrom(Throwable e) {
    // Scala doesn't declare checked exceptions in the bytecode, and the Java compiler
    // won't let you catch something that is not declared, so we can't catch
    // SparkException here. Instead we do an instanceof check.
    // Then we find the cause by seeing if it's a user exception (wrapped by Beam's
    // UserCodeException), or just use the SparkException cause.
    if (e instanceof SparkException && e.getCause() != null) {
      if (e.getCause() instanceof UserCodeException && e.getCause().getCause() != null) {
        return new Pipeline.PipelineExecutionException(UserCodeException.wrap(e.getCause()
                                                                               .getCause()));
      } else {
        return new Pipeline.PipelineExecutionException(e.getCause());
      }
    }
    // otherwise just wrap in a RuntimeException
    return runtimeExceptionFrom(e);
  }

  /**
   * Represents the result of running a batch pipeline.
   */
  static class BatchMode extends SparkPipelineResult {

    BatchMode(final Future<?> pipelineExecutionCommenced,
              final EvaluationContext evaluationContext) {
      super(pipelineExecutionCommenced, evaluationContext);
    }

    @Override
    protected void stop() {
      SparkContextFactory.stopSparkContext(context.getSparkContext());
    }

    @Override
    protected State awaitTermination(Duration duration)
        throws TimeoutException, ExecutionException, InterruptedException {
      pipelineExecutionCommenced.get(duration.getMillis(), TimeUnit.MILLISECONDS);
      return PipelineResult.State.DONE;
    }
  }

  /**
   * Represents a streaming Spark pipeline result.
   */
  static class StreamingMode extends SparkPipelineResult {

    StreamingMode(final Future<?> pipelineExecutionCommenced,
                  final EvaluationContext evaluationContext) {
      super(pipelineExecutionCommenced, evaluationContext);
    }

    @Override
    protected void stop() {
      context.getStreamingContext().stop(false, true);
      SparkContextFactory.stopSparkContext(context.getSparkContext());
    }

    @Override
    protected State awaitTermination(Duration duration) throws TimeoutException,
        ExecutionException, InterruptedException {
      pipelineExecutionCommenced.get(duration.getMillis(), TimeUnit.MILLISECONDS);
      if (context.getStreamingContext().awaitTerminationOrTimeout(duration.getMillis())) {
        return State.DONE;
      } else {
        return null;
      }
    }

  }

}
