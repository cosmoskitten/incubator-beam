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

package org.apache.beam.runners.core.construction.graph;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Iterables;
import com.google.common.graph.MutableNetwork;
import com.google.common.graph.Network;
import com.google.common.graph.NetworkBuilder;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.beam.model.pipeline.v1.RunnerApi.Components;
import org.apache.beam.model.pipeline.v1.RunnerApi.Environment;
import org.apache.beam.model.pipeline.v1.RunnerApi.PCollection;
import org.apache.beam.model.pipeline.v1.RunnerApi.PTransform;
import org.apache.beam.model.pipeline.v1.RunnerApi.ParDoPayload;
import org.apache.beam.model.pipeline.v1.RunnerApi.Pipeline;
import org.apache.beam.runners.core.construction.Environments;
import org.apache.beam.runners.core.construction.PTransformTranslation;
import org.apache.beam.runners.core.construction.RehydratedComponents;
import org.apache.beam.runners.core.construction.graph.PipelineNode.NodeType;
import org.apache.beam.runners.core.construction.graph.PipelineNode.PCollectionNode;
import org.apache.beam.runners.core.construction.graph.PipelineNode.PTransformNode;

/**
 * A {@link Pipeline} which has additional methods to relate nodes in the graph relative to each
 * other.
 */
public class QueryablePipeline {
  // TODO: Is it better to have the signatures here require nodes in almost all contexts, or should
  // they all take strings? Nodes gives some degree of type signalling that names might not, but
  // it's more painful to construct the node. However, right now the traversal is done starting
  // at the roots and using nodes everywhere based on what any query has returned.
  /**
   * Create a new {@link QueryablePipeline} based on the provided components.
   *
   * <p>The returned {@link QueryablePipeline} will contain only the primitive transforms present
   * within the provided components.
   */
  public static QueryablePipeline fromComponents(Components components) {
    return new QueryablePipeline(components);
  }

  private final Components components;
  private final RehydratedComponents rehydratedComponents;

  /**
   * The {@link Pipeline} represented by a {@link Network}.
   *
   * <p>This is a directed bipartite graph consisting of {@link PTransformNode PTransformNodes} and
   * {@link PCollectionNode PCollectionNodes}. Each {@link PCollectionNode} has exactly one in edge,
   * and an arbitrary number of out edges. Each {@link PTransformNode} has an arbitrary number of in
   * and out edges.
   *
   * <p>Parallel edges are permitted, as a {@link PCollectionNode} can be consumed by a single
   * {@link PTransformNode} any number of times with different local names.
   */
  private final MutableNetwork<PipelineNode, PipelineEdge> pipelineNetwork;

  private QueryablePipeline(Components allComponents) {
    this.components = Pipelines.retainOnlyPrimitives(allComponents);
    this.rehydratedComponents = RehydratedComponents.forComponents(this.components);
    this.pipelineNetwork = buildNetwork(this.components);
  }

  private MutableNetwork<PipelineNode, PipelineEdge> buildNetwork(Components components) {
    MutableNetwork<PipelineNode, PipelineEdge> network =
        NetworkBuilder.directed().allowsParallelEdges(true).allowsSelfLoops(false).build();
    for (Map.Entry<String, PTransform> transformEntry : components.getTransformsMap().entrySet()) {
      String transformId = transformEntry.getKey();
      PTransform transform = transformEntry.getValue();
      PTransformNode transformNode = transformNode(transformId);
      network.addNode(transformNode);
      for (String produced : transform.getOutputsMap().values()) {
        PCollectionNode producedNode =
            PipelineNode.pcollection(produced, components.getPcollectionsOrThrow(produced));
        network.addNode(producedNode);
        network.addEdge(transformNode, producedNode, new PerElementEdge());
        checkState(
            network.inDegree(producedNode) == 1,
            "A %s should have exactly one producing %s, %s has %s",
            PCollectionNode.class.getSimpleName(),
            PTransformNode.class.getSimpleName(),
            producedNode,
            network.successors(producedNode));
      }
      for (Map.Entry<String, String> consumed : transform.getInputsMap().entrySet()) {
        // This loop may add an edge between the consumed PCollection and the current PTransform.
        // The local name of the transform must be used to determine the type of edge.
        PCollectionNode consumedNode = pCollectionNode(consumed.getValue());
        network.addNode(consumedNode);
        if (getLocalSideInputNames(transform).contains(consumed.getKey())) {
          network.addEdge(consumedNode, transformNode, new SingletonEdge());
        } else {
          network.addEdge(consumedNode, transformNode, new PerElementEdge());
        }
      }
    }
    return network;
  }

  /**
   * Get the transforms that are roots of this {@link QueryablePipeline}. These are all nodes which
   * have no input {@link PCollection}.
   */
  public Set<PTransformNode> getRootTransforms() {
    return pipelineNetwork
        .nodes()
        .stream()
        .filter(
            pipelineNode ->
                pipelineNode.getType().equals(NodeType.PTRANSFORM)
                    && pipelineNetwork.inEdges(pipelineNode).isEmpty())
        .map(PipelineNode::asPTransformNode)
        .collect(Collectors.toSet());
  }

  public PipelineNode.PTransformNode getProducer(PCollectionNode pcollection) {
    return Iterables.getOnlyElement(pipelineNetwork.predecessors(pcollection)).asPTransformNode();
  }

  /**
   * Get all of the {@link PTransformNode PTransforms} which consume the provided {@link
   * PCollectionNode} on a per-element basis.
   *
   * <p>If a {@link PTransformNode} consumes a {@link PCollectionNode} on a per-element basis one or
   * more times, it will appear a single time in the result.
   *
   * <p>In theory, a transform may consume a single {@link PCollectionNode} in both a per-element
   * and singleton manner. If this is the case, the transform node is included in the result, as it
   * does consume the {@link PCollectionNode} on a per-element basis.
   */
  public Set<PTransformNode> getPerElementConsumers(PCollectionNode pCollection) {
    return pipelineNetwork
        .successors(pCollection)
        .stream()
        .filter(
            consumer ->
                pipelineNetwork
                    .edgesConnecting(pCollection, consumer)
                    .stream()
                    .anyMatch(PipelineEdge::isPerElement))
        .map(PipelineNode::asPTransformNode)
        .collect(Collectors.toSet());
  }

  public Set<PCollectionNode> getProducedPCollections(PTransformNode ptransform) {
    return pipelineNetwork
        .successors(ptransform)
        .stream()
        .map(PipelineNode::asPCollectionNode)
        .collect(Collectors.toSet());
  }

  public Components getComponents() {
    return components;
  }

  /**
   * Returns the {@link PCollectionNode PCollectionNodes} that the provided transform consumes as
   * side inputs.
   */
  public Collection<PCollectionNode> getSideInputs(PTransformNode transform) {
    return getLocalSideInputNames(transform.getTransform())
        .stream()
        .map(localName -> pCollectionNode(transform.getTransform().getInputsOrThrow(localName)))
        .collect(Collectors.toSet());
  }

  private Set<String> getLocalSideInputNames(PTransform transform) {
    if (PTransformTranslation.PAR_DO_TRANSFORM_URN.equals(transform.getSpec().getUrn())) {
      try {
        return ParDoPayload.parseFrom(transform.getSpec().getPayload()).getSideInputsMap().keySet();
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }
    } else {
      return Collections.emptySet();
    }
  }

  private PTransformNode transformNode(String transformId) {
    return PipelineNode.ptransform(transformId, components.getTransformsOrThrow(transformId));
  }

  private PCollectionNode pCollectionNode(String pcollectionId) {
    return PipelineNode.pcollection(
        pcollectionId, components.getPcollectionsOrThrow(pcollectionId));
  }

  public Optional<Environment> getEnvironment(PTransformNode parDo) {
    return Environments.getEnvironment(parDo.getTransform(), rehydratedComponents);
  }

  private interface PipelineEdge {
    boolean isPerElement();
  }

  private static class PerElementEdge implements PipelineEdge {
    @Override
    public boolean isPerElement() {
      return true;
    }
  }

  private static class SingletonEdge implements PipelineEdge {
    @Override
    public boolean isPerElement() {
      return false;
    }
  }
}
