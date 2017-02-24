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
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.apache.beam.runners.spark.metrics.MetricsAccumulator;
import org.apache.beam.runners.spark.translation.EvaluationContext;
import org.apache.beam.runners.spark.translation.SparkPipelineTranslator;
import org.apache.beam.runners.spark.translation.TransformEvaluator;
import org.apache.beam.runners.spark.translation.streaming.Checkpoint;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.runners.TransformHierarchy;
import org.apache.beam.sdk.transforms.AppliedPTransform;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.POutput;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Spark-native pipeline - translates pipeline to a Spark native pipeline.
 * Used for debugging purposes.
 */
public class SparkNativePipelineVisitor extends SparkRunner.Evaluator {
  private static final Logger LOG = LoggerFactory.getLogger(SparkNativePipelineVisitor.class);

  private final List<NativeTransform> transforms;
  private final List<String> knownComposites =
      Lists.newArrayList(
          "org.apache.beam.sdk.transforms",
          "org.apache.beam.runners.spark.examples");

  SparkNativePipelineVisitor(SparkPipelineTranslator translator, EvaluationContext ctxt) {
    super(translator, ctxt);
    this.transforms = new ArrayList<>();
    MetricsAccumulator.init(ctxt.getSparkContext(), Optional.<Checkpoint.CheckpointDir>absent());
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
          transforms.add(new NativeTransform(node, null, transform, true));
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
    return node == null || !Iterables.any(transforms, new Predicate<NativeTransform>() {
      @Override
      public boolean apply(NativeTransform debugTransform) {
        return debugTransform.getNode().equals(node) && debugTransform.isComposite();
      }
    }) && shouldDebug(node.getEnclosingNode());
  }

  private <TransformT extends PTransform<? super PInput, POutput>>
  void doVisitTransform(TransformHierarchy.Node node, boolean debugTransform) {
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
      transforms.add(new NativeTransform(node, evaluator, transform, false));
    }
  }

  public String getDebugString() {
    return StringUtils.join(transforms, "\n");
  }

  private static class NativeTransform {
    private final TransformHierarchy.Node node;
    private final TransformEvaluator<?> transformEvaluator;
    private final PTransform<?, ?> transform;
    private final boolean composite;

    NativeTransform(
        TransformHierarchy.Node node,
        TransformEvaluator<?> transformEvaluator,
        PTransform<?, ?> transform,
        boolean composite) {
      this.node = node;
      this.transformEvaluator = transformEvaluator;
      this.transform = transform;
      this.composite = composite;
    }

    TransformHierarchy.Node getNode() {
      return node;
    }

    boolean isComposite() {
      return composite;
    }

    @Override
    public String toString() {
      try {
        Class<? extends PTransform> transformClass = transform.getClass();
        if (node.getFullName().equals("KafkaIO.Read")) {
          return "KafkaUtils.createDirectStream(...)";
        }
        if (composite) {
          return "_.<" + transformClass.getName() + ">";
        }
        String transformString = transformEvaluator.toNativeString();
        if (transformString.contains("<fn>")) {
          transformString = replaceFnString(transformClass, transformString, "fn");
        } else if (transformString.contains("<windowFn>")) {
          transformString = replaceFnString(transformClass, transformString, "windowFn");
        } else if (transformString.contains("<source>")) {
          String sourceName = "...";
          if (transform instanceof Read.Bounded) {
            sourceName = ((Read.Bounded<?>) transform).getSource().getClass().getName();
          } else if (transform instanceof Read.Unbounded) {
            sourceName = ((Read.Unbounded<?>) transform).getSource().getClass().getName();
          }
          transformString = transformString.replace("<source>", sourceName);
        }
        if (transformString.startsWith("sparkContext")
            || transformString.startsWith("streamingContext")) {
          return transformString;
        }
        return "_." + transformString;
      } catch (
          NoSuchMethodException
              | InvocationTargetException
              | IllegalAccessException
              | NoSuchFieldException e) {
        return "<FailedTranslation>";
      }
    }

    private String replaceFnString(
        Class<? extends PTransform> transformClass,
        String transformString,
        String fnFieldName)
        throws IllegalAccessException, InvocationTargetException, NoSuchMethodException,
        NoSuchFieldException {
      Object fn =
          transformClass.getMethod("get" + WordUtils.capitalize(fnFieldName)).invoke(transform);
      Class<?> fnClass = fn.getClass();
      String doFnName;
      Class<?> enclosingClass = fnClass.getEnclosingClass();
      if (enclosingClass != null && enclosingClass.equals(MapElements.class)) {
        Field parent = fnClass.getDeclaredField("this$0");
        parent.setAccessible(true);
        Field fnField = enclosingClass.getDeclaredField(fnFieldName);
        fnField.setAccessible(true);
        doFnName = fnField.get(parent.get(fn)).getClass().getName();
      } else {
        doFnName = fnClass.getName();
      }
      transformString = transformString.replace("<" + fnFieldName + ">", doFnName);
      return transformString;
    }
  }
}
