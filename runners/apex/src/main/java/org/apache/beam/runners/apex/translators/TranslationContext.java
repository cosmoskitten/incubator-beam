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
package org.apache.beam.runners.apex.translators;

import static com.google.common.base.Preconditions.checkArgument;

import com.datatorrent.api.Context.PortContext;
import com.datatorrent.api.DAG;
import com.datatorrent.api.Operator;
import com.datatorrent.api.Operator.InputPort;
import com.datatorrent.api.Operator.OutputPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.beam.runners.apex.ApexPipelineOptions;
import org.apache.beam.runners.apex.translators.utils.ApexStateInternals;
import org.apache.beam.runners.apex.translators.utils.ApexStreamTuple;
import org.apache.beam.runners.apex.translators.utils.CoderAdapterStreamCodec;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.runners.TransformTreeNode;
import org.apache.beam.sdk.transforms.AppliedPTransform;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.util.WindowedValue.FullWindowedValueCoder;
import org.apache.beam.sdk.util.state.StateInternalsFactory;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.POutput;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Maintains context data for {@link TransformTranslator}s.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class TranslationContext {

  private final ApexPipelineOptions pipelineOptions;
  private AppliedPTransform<?, ?, ?> currentTransform;
  private final Map<PCollection, Pair<OutputPort<?>, List<InputPort<?>>>> streams = new HashMap<>();
  private final Map<String, Operator> operators = new HashMap<>();
  private final Map<PCollectionView<?>, PInput> viewInputs = new HashMap<>();

  public void addView(PCollectionView<?> view) {
    this.viewInputs.put(view, this.getInput());
  }

  public <InputT extends PInput> InputT getViewInput(PCollectionView<?> view) {
    PInput input = this.viewInputs.get(view);
    checkArgument(input != null, "unknown view " + view.getName());
    return (InputT) input;
  }

  public TranslationContext(ApexPipelineOptions pipelineOptions) {
    this.pipelineOptions = pipelineOptions;
  }

  public void setCurrentTransform(TransformTreeNode treeNode) {
    this.currentTransform = AppliedPTransform.of(treeNode.getFullName(),
        treeNode.getInput(), treeNode.getOutput(), (PTransform) treeNode.getTransform());
  }

  public ApexPipelineOptions getPipelineOptions() {
    return pipelineOptions;
  }

  public <InputT extends PInput> InputT getInput() {
    return (InputT) getCurrentTransform().getInput();
  }

  public <OutputT extends POutput> OutputT getOutput() {
    return (OutputT) getCurrentTransform().getOutput();
  }

  private AppliedPTransform<?, ?, ?> getCurrentTransform() {
    checkArgument(currentTransform != null, "current transform not set");
    return currentTransform;
  }

  public void addOperator(Operator operator, OutputPort port) {
    addOperator(operator, port, this.<PCollection<?>>getOutput());
  }

  /**
   * Register operator and output ports for the given collections.
   * @param operator
   * @param ports
   */
  public void addOperator(Operator operator, Map<PCollection<?>, OutputPort<?>> ports) {
    boolean first = true;
    for (Map.Entry<PCollection<?>, OutputPort<?>> portEntry : ports.entrySet()) {
      if (first) {
        addOperator(operator, portEntry.getValue(), portEntry.getKey());
        first = false;
      } else {
        this.streams.put(portEntry.getKey(), (Pair) new ImmutablePair<>(portEntry.getValue(),
            new ArrayList<>()));
      }
    }
  }

  /**
   * Add the operator with its output port for the given result {link PCollection}.
   * @param operator
   * @param port
   * @param output
   */
  public void addOperator(Operator operator, OutputPort port, PCollection output) {
    // Apex DAG requires a unique operator name
    // use the transform's name and make it unique
    String name = getCurrentTransform().getFullName();
    for (int i = 1; this.operators.containsKey(name); i++) {
      name = getCurrentTransform().getFullName() + i;
    }
    this.operators.put(name, operator);
    this.streams.put(output, (Pair) new ImmutablePair<>(port, new ArrayList<>()));
  }

  public void addStream(PInput input, InputPort inputPort) {
    Pair<OutputPort<?>, List<InputPort<?>>> stream = this.streams.get(input);
    checkArgument(stream != null, "no upstream operator defined for %s", input);
    stream.getRight().add(inputPort);
  }

  public void populateDAG(DAG dag) {
    for (Map.Entry<String, Operator> nameAndOperator : this.operators.entrySet()) {
      dag.addOperator(nameAndOperator.getKey(), nameAndOperator.getValue());
    }
    int streamIndex = 0;
    for (Map.Entry<PCollection, Pair<OutputPort<?>, List<InputPort<?>>>> streamEntry : this.
        streams.entrySet()) {
      List<InputPort<?>> sinksList = streamEntry.getValue().getRight();
      InputPort[] sinks = sinksList.toArray(new InputPort[sinksList.size()]);
      if (sinks.length > 0) {
        dag.addStream("stream" + streamIndex++, streamEntry.getValue().getLeft(), sinks);
        for (InputPort port : sinks) {
          PCollection pc = streamEntry.getKey();
          Coder coder = pc.getCoder();
          if (pc.getWindowingStrategy() != null) {
            coder = FullWindowedValueCoder.of(pc.getCoder(),
                pc.getWindowingStrategy().getWindowFn().windowCoder()
                );
          }
          Coder<Object> wrapperCoder = ApexStreamTuple.ApexStreamTupleCoder.of(coder);
          CoderAdapterStreamCodec streamCodec = new CoderAdapterStreamCodec(wrapperCoder);
          dag.setInputPortAttribute(port, PortContext.STREAM_CODEC, streamCodec);
        }
      }
    }
  }

  /**
   * Return the {@link StateInternalsFactory} for the pipeline translation.
   * @return
   */
  public <K> StateInternalsFactory<K> stateInternalsFactory() {
    return new ApexStateInternals.ApexStateInternalsFactory();
  }

}
