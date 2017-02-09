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

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.annotation.Nullable;
import org.apache.beam.runners.spark.aggregators.AggregatorsAccumulator;
import org.apache.beam.runners.spark.aggregators.NamedAggregators;
import org.apache.beam.runners.spark.aggregators.SparkAggregators;
import org.apache.beam.runners.spark.metrics.AggregatorMetricSource;
import org.apache.beam.runners.spark.metrics.CompositeSource;
import org.apache.beam.runners.spark.metrics.MetricsAccumulator;
import org.apache.beam.runners.spark.metrics.SparkBeamMetricSource;
import org.apache.beam.runners.spark.translation.EvaluationContext;
import org.apache.beam.runners.spark.translation.SparkContextFactory;
import org.apache.beam.runners.spark.translation.SparkPipelineTranslator;
import org.apache.beam.runners.spark.translation.TransformEvaluator;
import org.apache.beam.runners.spark.translation.TransformTranslator;
import org.apache.beam.runners.spark.translation.streaming.Checkpoint.CheckpointDir;
import org.apache.beam.runners.spark.translation.streaming.SparkRunnerStreamingContextFactory;
import org.apache.beam.runners.spark.util.GlobalWatermarkHolder.WatermarksListener;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.metrics.MetricsEnvironment;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.PipelineOptionsValidator;
import org.apache.beam.sdk.runners.PipelineRunner;
import org.apache.beam.sdk.runners.TransformHierarchy;
import org.apache.beam.sdk.transforms.AppliedPTransform;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.POutput;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.TaggedPValue;
import org.apache.commons.lang.StringUtils;
import org.apache.spark.Accumulator;
import org.apache.spark.SparkEnv$;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.metrics.MetricsSystem;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.api.java.JavaStreamingListener;
import org.apache.spark.streaming.api.java.JavaStreamingListenerWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The SparkRunner translate operations defined on a pipeline to a representation
 * executable by Spark, and then submitting the job to Spark to be executed. If we wanted to run
 * a Beam pipeline with the default options of a single threaded spark instance in local mode,
 * we would do the following:
 *
 * {@code
 * Pipeline p = [logic for pipeline creation]
 * SparkPipelineResult result = (SparkPipelineResult) p.run();
 * }
 *
 * <p>To create a pipeline runner to run against a different spark cluster, with a custom master url
 * we would do the following:
 *
 * {@code
 * Pipeline p = [logic for pipeline creation]
 * SparkPipelineOptions options = SparkPipelineOptionsFactory.create();
 * options.setSparkMaster("spark://host:port");
 * SparkPipelineResult result = (SparkPipelineResult) p.run();
 * }
 */
public final class SparkRunner extends PipelineRunner<SparkPipelineResult> {

  private static final Logger LOG = LoggerFactory.getLogger(SparkRunner.class);
  /**
   * Options used in this pipeline runner.
   */
  private final SparkPipelineOptions mOptions;

  /**
   * Creates and returns a new SparkRunner with default options. In particular, against a
   * spark instance running in local mode.
   *
   * @return A pipeline runner with default options.
   */
  public static SparkRunner create() {
    SparkPipelineOptions options = PipelineOptionsFactory.as(SparkPipelineOptions.class);
    options.setRunner(SparkRunner.class);
    return new SparkRunner(options);
  }

  /**
   * Creates and returns a new SparkRunner with specified options.
   *
   * @param options The SparkPipelineOptions to use when executing the job.
   * @return A pipeline runner that will execute with specified options.
   */
  public static SparkRunner create(SparkPipelineOptions options) {
    return new SparkRunner(options);
  }

  /**
   * Creates and returns a new SparkRunner with specified options.
   *
   * @param options The PipelineOptions to use when executing the job.
   * @return A pipeline runner that will execute with specified options.
   */
  public static SparkRunner fromOptions(PipelineOptions options) {
    SparkPipelineOptions sparkOptions =
        PipelineOptionsValidator.validate(SparkPipelineOptions.class, options);
    return new SparkRunner(sparkOptions);
  }

  /**
   * No parameter constructor defaults to running this pipeline in Spark's local mode, in a single
   * thread.
   */
  private SparkRunner(SparkPipelineOptions options) {
    mOptions = options;
  }

  private void registerMetrics(final SparkPipelineOptions opts, final JavaSparkContext jsc) {
    Optional<CheckpointDir> maybeCheckpointDir =
        opts.isStreaming() ? Optional.of(new CheckpointDir(opts.getCheckpointDir()))
            : Optional.<CheckpointDir>absent();
    final Accumulator<NamedAggregators> aggregatorsAccumulator =
        SparkAggregators.getOrCreateNamedAggregators(jsc, maybeCheckpointDir);
    // Instantiate metrics accumulator
    MetricsAccumulator.init(jsc, maybeCheckpointDir);
    final NamedAggregators initialValue = aggregatorsAccumulator.value();
    if (opts.getEnableSparkMetricSinks()) {
      final MetricsSystem metricsSystem = SparkEnv$.MODULE$.get().metricsSystem();
      String appName = opts.getAppName();
      final AggregatorMetricSource aggregatorMetricSource =
          new AggregatorMetricSource(appName, initialValue);
      final SparkBeamMetricSource metricsSource =
          new SparkBeamMetricSource(appName);
      final CompositeSource compositeSource =
          new CompositeSource(appName,
              metricsSource.metricRegistry(), aggregatorMetricSource.metricRegistry());
      // re-register the metrics in case of context re-use
      metricsSystem.removeSource(compositeSource);
      metricsSystem.registerSource(compositeSource);
    }
  }

  @Override
  public SparkPipelineResult run(final Pipeline pipeline) {
    LOG.info("Executing pipeline using the SparkRunner.");

    final SparkPipelineResult result;
    final Future<?> startPipeline;
    final ExecutorService executorService = Executors.newSingleThreadExecutor();

    MetricsEnvironment.setMetricsSupported(true);

    detectTranslationMode(pipeline);

    if (mOptions.isStreaming()) {
      CheckpointDir checkpointDir = new CheckpointDir(mOptions.getCheckpointDir());
      final SparkRunnerStreamingContextFactory contextFactory =
          new SparkRunnerStreamingContextFactory(pipeline, mOptions, checkpointDir);
      final JavaStreamingContext jssc =
          JavaStreamingContext.getOrCreate(checkpointDir.getSparkCheckpointDir().toString(),
              contextFactory);

      // Checkpoint aggregator/metrics values
      jssc.addStreamingListener(
          new JavaStreamingListenerWrapper(
              new AggregatorsAccumulator.AccumulatorCheckpointingSparkListener()));
      jssc.addStreamingListener(
          new JavaStreamingListenerWrapper(
              new MetricsAccumulator.AccumulatorCheckpointingSparkListener()));

      // register user-defined listeners.
      for (JavaStreamingListener listener: mOptions.as(SparkContextOptions.class).getListeners()) {
        LOG.info("Registered listener {}." + listener.getClass().getSimpleName());
        jssc.addStreamingListener(new JavaStreamingListenerWrapper(listener));
      }

      // register Watermarks listener to broadcast the advanced WMs.
      jssc.addStreamingListener(new JavaStreamingListenerWrapper(new WatermarksListener(jssc)));

      startPipeline = executorService.submit(new Runnable() {

        @Override
        public void run() {
          registerMetrics(mOptions, jssc.sparkContext());
          LOG.info("Starting streaming pipeline execution.");
          jssc.start();
        }
      });

      result = new SparkPipelineResult.StreamingMode(startPipeline, jssc);
    } else {
      final JavaSparkContext jsc = SparkContextFactory.getSparkContext(mOptions);
      final EvaluationContext evaluationContext = new EvaluationContext(jsc, pipeline);

      startPipeline = executorService.submit(new Runnable() {

        @Override
        public void run() {
          registerMetrics(mOptions, jsc);
          TransformTranslator.Translator translator = new TransformTranslator.Translator();
          Evaluator evaluator = mOptions.isDebugPipeline()
              ? new SparkNativePipelineVisitor(translator, evaluationContext)
              : new Evaluator(translator, evaluationContext);
          pipeline.traverseTopologically(evaluator);
          evaluationContext.computeOutputs(evaluator, mOptions.isDebugPipeline());
          LOG.info("Batch pipeline execution complete.");
        }
      });

      result = new SparkPipelineResult.BatchMode(startPipeline, jsc);
    }

    return result;
  }

  /**
   * Detect the translation mode for the pipeline and change options in case streaming
   * translation is needed.
   * @param pipeline
   */
  private void detectTranslationMode(Pipeline pipeline) {
    TranslationModeDetector detector = new TranslationModeDetector();
    pipeline.traverseTopologically(detector);
    if (detector.getTranslationMode().equals(TranslationMode.STREAMING)) {
      // set streaming mode if it's a streaming pipeline
      this.mOptions.setStreaming(true);
    }
  }

  /**
   * The translation mode of the Beam Pipeline.
   */
  enum TranslationMode {
    /** Uses the batch mode. */
    BATCH,
    /** Uses the streaming mode. */
    STREAMING
  }

  /**
   * Traverses the Pipeline to determine the {@link TranslationMode} for this pipeline.
   */
  static class TranslationModeDetector extends Pipeline.PipelineVisitor.Defaults {
    private static final Logger LOG = LoggerFactory.getLogger(TranslationModeDetector.class);

    private TranslationMode translationMode;

    TranslationModeDetector(TranslationMode defaultMode) {
      this.translationMode = defaultMode;
    }

    TranslationModeDetector() {
      this(TranslationMode.BATCH);
    }

    TranslationMode getTranslationMode() {
      return translationMode;
    }

    @Override
    public void visitPrimitiveTransform(TransformHierarchy.Node node) {
      if (translationMode.equals(TranslationMode.BATCH)) {
        Class<? extends PTransform> transformClass = node.getTransform().getClass();
        if (transformClass == Read.Unbounded.class) {
          LOG.info("Found {}. Switching to streaming execution.", transformClass);
          translationMode = TranslationMode.STREAMING;
        }
      }
    }
  }

  /**
   * Evaluator on the pipeline.
   */
  public static class Evaluator extends Pipeline.PipelineVisitor.Defaults {

    private static final Logger LOG = LoggerFactory.getLogger(Evaluator.class);

    final EvaluationContext ctxt;
    final SparkPipelineTranslator translator;

    public Evaluator(SparkPipelineTranslator translator, EvaluationContext ctxt) {
      this.translator = translator;
      this.ctxt = ctxt;
    }

    @Override
    public CompositeBehavior enterCompositeTransform(TransformHierarchy.Node node) {
      if (node.getTransform() != null) {
        @SuppressWarnings("unchecked")
        Class<PTransform<?, ?>> transformClass =
            (Class<PTransform<?, ?>>) node.getTransform().getClass();
        if (translator.hasTranslation(transformClass) && !shouldDefer(node)) {
          LOG.info("Entering directly-translatable composite transform: '{}'", node.getFullName());

          LOG.debug("Composite transform class: '{}'", transformClass);
          doVisitTransform(node);
          return CompositeBehavior.DO_NOT_ENTER_TRANSFORM;
        }
      }
      return CompositeBehavior.ENTER_TRANSFORM;
    }

    boolean shouldDefer(TransformHierarchy.Node node) {
      // if the input is not a PCollection, or it is but with non merging windows, don't defer.
      if (node.getInputs().size() != 1) {
        return false;
      }
      PValue input = Iterables.getOnlyElement(node.getInputs()).getValue();
      if (!(input instanceof PCollection)
          || ((PCollection) input).getWindowingStrategy().getWindowFn().isNonMerging()) {
        return false;
      }
      // so far we know that the input is a PCollection with merging windows.
      // check for sideInput in case of a Combine transform.
      PTransform<?, ?> transform = node.getTransform();
      boolean hasSideInput = false;
      if (transform instanceof Combine.PerKey) {
        List<PCollectionView<?>> sideInputs = ((Combine.PerKey<?, ?, ?>) transform).getSideInputs();
        hasSideInput = sideInputs != null && !sideInputs.isEmpty();
      } else if (transform instanceof Combine.Globally) {
        List<PCollectionView<?>> sideInputs = ((Combine.Globally<?, ?>) transform).getSideInputs();
        hasSideInput = sideInputs != null && !sideInputs.isEmpty();
      }
      // defer if sideInputs are defined.
      if (hasSideInput) {
        LOG.info("Deferring combine transformation {} for job {}", transform,
            ctxt.getPipeline().getOptions().getJobName());
        return true;
      }
      // default.
      return false;
    }

    @Override
    public void visitPrimitiveTransform(TransformHierarchy.Node node) {
      doVisitTransform(node);
    }

    <TransformT extends PTransform<? super PInput, POutput>> void
        doVisitTransform(TransformHierarchy.Node node) {
      @SuppressWarnings("unchecked")
      TransformT transform = (TransformT) node.getTransform();
      @SuppressWarnings("unchecked")
      Class<TransformT> transformClass = (Class<TransformT>) (Class<?>) transform.getClass();
      @SuppressWarnings("unchecked") TransformEvaluator<TransformT> evaluator =
          translate(node, transform, transformClass);
      LOG.info("Evaluating {}", transform);
      AppliedPTransform<?, ?, ?> appliedTransform = node.toAppliedPTransform();
      ctxt.setCurrentTransform(appliedTransform);
      evaluator.evaluate(transform, ctxt);
      ctxt.setCurrentTransform(null);
    }

    /**
     * Determine if this Node belongs to a Bounded branch of the pipeline, or Unbounded, and
     * translate with the proper translator.
     */
    <TransformT extends PTransform<? super PInput, POutput>>
    TransformEvaluator<TransformT> translate(
        TransformHierarchy.Node node, TransformT transform, Class<TransformT> transformClass) {
      //--- determine if node is bounded/unbounded.
      // usually, the input determines if the PCollection to apply the next transformation to
      // is BOUNDED or UNBOUNDED, meaning RDD/DStream.
      Collection<TaggedPValue> pValues;
      if (node.getInputs().isEmpty()) {
        // in case of a PBegin, it's the output.
        pValues = node.getOutputs();
      } else {
        pValues = node.getInputs();
      }
      PCollection.IsBounded isNodeBounded = isBoundedCollection(pValues);
      // translate accordingly.
      LOG.debug("Translating {} as {}", transform, isNodeBounded);
      return isNodeBounded.equals(PCollection.IsBounded.BOUNDED)
          ? translator.translateBounded(transformClass)
          : translator.translateUnbounded(transformClass);
    }

    PCollection.IsBounded isBoundedCollection(Collection<TaggedPValue> pValues) {
      // anything that is not a PCollection, is BOUNDED.
      // For PCollections:
      // BOUNDED behaves as the Identity Element, BOUNDED + BOUNDED = BOUNDED
      // while BOUNDED + UNBOUNDED = UNBOUNDED.
      PCollection.IsBounded isBounded = PCollection.IsBounded.BOUNDED;
      for (TaggedPValue pValue : pValues) {
        if (pValue.getValue() instanceof PCollection) {
          isBounded = isBounded.and(((PCollection) pValue.getValue()).isBounded());
        } else {
          isBounded = isBounded.and(PCollection.IsBounded.BOUNDED);
        }
      }
      return isBounded;
    }
  }

  /**
   * Spark-native pipeline.
   * Translates pipeline to a Spark native pipeline.
   * Used for debugging purposes.
   */
  public static class SparkNativePipelineVisitor extends Evaluator {
    private final List<DebugTransform> transforms;
    private final List<String> knownComposites =
        Lists.newArrayList(
            "org.apache.beam.sdk.transforms",
            "org.apache.beam.runners.spark.examples");

    SparkNativePipelineVisitor(SparkPipelineTranslator translator, EvaluationContext ctxt) {
      super(translator, ctxt);
      this.transforms = new ArrayList<>();
    }

    @Override
    public CompositeBehavior enterCompositeTransform(TransformHierarchy.Node node) {
      PTransform<?, ?> transform = node.getTransform();
      if (transform != null) {
        @SuppressWarnings("unchecked") final
        Class<PTransform<?, ?>> transformClass =
            (Class<PTransform<?, ?>>) transform.getClass();
        if (translator.hasTranslation(transformClass) && !shouldDefer(node)) {
          LOG.info("Entering directly-translatable composite transform: '{}'", node.getFullName());
          LOG.debug("Composite transform class: '{}'", transformClass);
          doVisitTransform(node, shouldDebug(node));
          return CompositeBehavior.DO_NOT_ENTER_TRANSFORM;
        } else if (!knownComposites.contains(transformClass.getPackage().getName())) {
          if (shouldDebug(node)) {
            transforms.add(new DebugTransform(node, null, transform, true));
          }
        }
      }
      return CompositeBehavior.ENTER_TRANSFORM;
    }

    @Override
    public void visitPrimitiveTransform(TransformHierarchy.Node node) {
      doVisitTransform(node, shouldDebug(node));
    }

    private boolean shouldDebug(final TransformHierarchy.Node node) {
      if (node == null) {
        return true;
      }
      if (Iterables.any(transforms, new Predicate<DebugTransform>() {
        @Override
        public boolean apply(@Nullable DebugTransform debugTransform) {
          return debugTransform.node.equals(node) && debugTransform.isComposite();
        }
      })) {
        return false;
      }
      return shouldDebug(node.getEnclosingNode());
    }

    <TransformT extends PTransform<? super PInput, POutput>> void
    doVisitTransform(TransformHierarchy.Node node, boolean debugTransform) {
      @SuppressWarnings("unchecked")
      TransformT transform = (TransformT) node.getTransform();
      @SuppressWarnings("unchecked")
      Class<TransformT> transformClass = (Class<TransformT>) (Class<?>) transform.getClass();
      @SuppressWarnings("unchecked") TransformEvaluator<TransformT> evaluator =
          translate(node, transform, transformClass);
      LOG.info("Evaluating {}", transform);
      AppliedPTransform<?, ?, ?> appliedTransform = node.toAppliedPTransform();
      ctxt.setCurrentTransform(appliedTransform);
      evaluator.evaluate(transform, ctxt);
      ctxt.setCurrentTransform(null);
      if (debugTransform) {
        transforms.add(new DebugTransform(node, evaluator, transform, false));
      }
    }

    public String getDebugString() {
      return StringUtils.join(transforms, "\n.");
    }

    private static class DebugTransform {
      private final TransformHierarchy.Node node;
      private final TransformEvaluator<?> transformEvaluator;
      private final PTransform<?, ?> transform;
      private final boolean composite;

      DebugTransform(
          TransformHierarchy.Node node,
          TransformEvaluator<?> transformEvaluator,
          PTransform<?, ?> transform,
          boolean composite) {
        this.node = node;
        this.transformEvaluator = transformEvaluator;
        this.transform = transform;
        this.composite = composite;
      }

      public TransformHierarchy.Node getNode() {
        return node;
      }

      boolean isComposite() {
        return composite;
      }

      @Override
      public String toString() {
        try {
          Class<? extends PTransform> transformClass = transform.getClass();
          if (composite) {
            return "<" + transformClass.getName() + ">";
          }
          String transformString = transformEvaluator.toString();
          if (transformString.contains("<doFn>")) {
            Object fn = transformClass.getMethod("getFn").invoke(transform);
            Class<?> fnClass = fn.getClass();
            String doFnName;
            if (fnClass.getEnclosingClass().equals(MapElements.class)) {
              Field parent = fnClass.getDeclaredField("this$0");
              parent.setAccessible(true);
              Field fnField = fnClass.getEnclosingClass().getDeclaredField("fn");
              fnField.setAccessible(true);
              doFnName = fnField.get(parent.get(fn)).getClass().getName();
            } else {
              doFnName = fnClass.getName();
            }
            transformString = transformString.replace("<doFn>", doFnName);
          }
          return transformString;
        } catch (Exception e) {
          return "<FailedTranslation>";
        }
      }
    }
  }
}
