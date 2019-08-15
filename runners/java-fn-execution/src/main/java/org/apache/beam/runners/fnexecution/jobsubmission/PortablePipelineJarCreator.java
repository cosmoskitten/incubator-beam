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
package org.apache.beam.runners.fnexecution.jobsubmission;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.apache.beam.model.jobmanagement.v1.ArtifactApi;
import org.apache.beam.model.jobmanagement.v1.ArtifactApi.ArtifactChunk;
import org.apache.beam.model.jobmanagement.v1.ArtifactApi.ArtifactMetadata;
import org.apache.beam.model.jobmanagement.v1.ArtifactApi.GetArtifactRequest;
import org.apache.beam.model.jobmanagement.v1.ArtifactApi.GetManifestRequest;
import org.apache.beam.model.jobmanagement.v1.ArtifactApi.GetManifestResponse;
import org.apache.beam.model.jobmanagement.v1.ArtifactApi.ProxyManifest;
import org.apache.beam.model.jobmanagement.v1.ArtifactApi.ProxyManifest.Location;
import org.apache.beam.model.jobmanagement.v1.ArtifactRetrievalServiceGrpc;
import org.apache.beam.model.jobmanagement.v1.ArtifactRetrievalServiceGrpc.ArtifactRetrievalServiceBlockingStub;
import org.apache.beam.model.jobmanagement.v1.JobApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.Pipeline;
import org.apache.beam.runners.core.construction.PipelineOptionsTranslation;
import org.apache.beam.runners.core.construction.PipelineResources;
import org.apache.beam.runners.fnexecution.GrpcFnServer;
import org.apache.beam.runners.fnexecution.InProcessServerFactory;
import org.apache.beam.runners.fnexecution.artifact.BeamFileSystemArtifactRetrievalService;
import org.apache.beam.runners.fnexecution.provisioning.JobInfo;
import org.apache.beam.sdk.fn.test.InProcessManagedChannelFactory;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.options.PortablePipelineOptions;
import org.apache.beam.vendor.grpc.v1p21p0.com.google.protobuf.Struct;
import org.apache.beam.vendor.grpc.v1p21p0.com.google.protobuf.util.JsonFormat;
import org.apache.beam.vendor.grpc.v1p21p0.io.grpc.ManagedChannel;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions;
import org.apache.commons.compress.utils.IOUtils;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PortablePipelineJarCreator implements PortablePipelineRunner {
  private static final Logger LOG = LoggerFactory.getLogger(PortablePipelineJarCreator.class);

  private final Class mainClass;

  public PortablePipelineJarCreator(Class mainClass) {
    this.mainClass = mainClass;
  }

  @Override
  public PortablePipelineResult run(Pipeline pipeline, JobInfo jobInfo) throws Exception {
    PortablePipelineOptions pipelineOptions =
        PipelineOptionsTranslation.fromProto(jobInfo.pipelineOptions())
            .as(PortablePipelineOptions.class);

    File outputFile = new File(pipelineOptions.getOutputJar());
    LOG.info("Creating jar {}", outputFile.getAbsolutePath());
    try (JarOutputStream outputStream =
        new JarOutputStream(new FileOutputStream(outputFile), createManifest(mainClass))) {
      writeClassPathResources(mainClass.getClassLoader(), outputStream);
      writePipeline(pipeline, outputStream);
      writePipelineOptions(PipelineOptionsTranslation.toProto(pipelineOptions), outputStream);
      writeArtifacts(jobInfo.retrievalToken(), outputStream);

      LOG.info("Jar {} created successfully.", outputFile.getAbsolutePath());
      return new JarCreatorPipelineResult();
    }
  }

  @VisibleForTesting
  static Manifest createManifest(Class mainClass) {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    boolean classHasMainMethod = false;
    try {
      Class returnType = mainClass.getMethod("main", String[].class).getReturnType();
      if (returnType == Void.TYPE) {
        classHasMainMethod = true;
      } else {
        LOG.warn(
            "No Main-Class will be set in jar because main method in {} returns {}, expected void",
            mainClass,
            returnType);
      }
    } catch (NoSuchMethodException e) {
      LOG.warn("No Main-Class will be set in jar because {} lacks a main method.", mainClass);
    }
    if (classHasMainMethod) {
      manifest.getMainAttributes().put(Name.MAIN_CLASS, mainClass.getName());
    }
    return manifest;
  }

  /** Copy resources from {@code classLoader} to {@code outputStream}. */
  private static void writeClassPathResources(ClassLoader classLoader, JarOutputStream outputStream)
      throws IOException {
    List<String> classPathResources =
        PipelineResources.detectClassPathResourcesToStage(classLoader);
    Preconditions.checkArgument(
        classPathResources.size() == 1, "Expected exactly one jar on " + classLoader.toString());
    copyResourcesFromJar(new JarFile(classPathResources.get(0)), outputStream);
  }

  /** Copy resources from {@code inputJar} to {@code outputStream}. */
  @VisibleForTesting
  protected static void copyResourcesFromJar(JarFile inputJar, JarOutputStream outputStream)
      throws IOException {
    Enumeration<JarEntry> inputJarEntries = inputJar.entries();
    // The zip spec allows multiple files with the same name; the Java zip libraries do not.
    // Keep track of the files we've already written to filter out duplicates.
    // Also, ignore the old manifest; we want to write our own.
    Set<String> previousEntryNames =
        new HashSet<>(
            ImmutableList.of(
                JarFile.MANIFEST_NAME,
                PortablePipelineJarUtils.ARTIFACT_FOLDER_NAME,
                PortablePipelineJarUtils.ARTIFACT_MANIFEST_NAME,
                PortablePipelineJarUtils.PIPELINE_FILE_NAME,
                PortablePipelineJarUtils.PIPELINE_OPTIONS_FILE_NAME));
    while (inputJarEntries.hasMoreElements()) {
      JarEntry inputJarEntry = inputJarEntries.nextElement();
      InputStream inputStream = inputJar.getInputStream(inputJarEntry);
      String entryName = inputJarEntry.getName();
      if (previousEntryNames.contains(entryName)) {
        LOG.debug("Skipping duplicated file {}", entryName);
      } else {
        JarEntry outputJarEntry = new JarEntry(inputJarEntry);
        outputStream.putNextEntry(outputJarEntry);
        LOG.trace("Copying jar entry {}", inputJarEntry);
        IOUtils.copy(inputStream, outputStream);
        previousEntryNames.add(entryName);
      }
    }
  }

  private static void writePipeline(Pipeline pipeline, JarOutputStream outputStream)
      throws IOException {
    JarEntry jarEntry = new JarEntry(PortablePipelineJarUtils.PIPELINE_FILE_NAME);
    outputStream.putNextEntry(jarEntry);
    pipeline.writeTo(outputStream);
  }

  private static void writePipelineOptions(Struct optionsProto, JarOutputStream outputStream)
      throws IOException {
    JarEntry jarEntry = new JarEntry(PortablePipelineJarUtils.PIPELINE_OPTIONS_FILE_NAME);
    outputStream.putNextEntry(jarEntry);
    optionsProto.writeTo(outputStream);
  }

  @VisibleForTesting
  interface ArtifactRetriever {
    GetManifestResponse getManifest(GetManifestRequest request);

    Iterator<ArtifactChunk> getArtifact(GetArtifactRequest request);
  }

  /**
   * Copy all artifacts retrievable via the {@link ArtifactRetrievalServiceBlockingStub} to the
   * {@code outputStream}.
   *
   * @return A {@link ProxyManifest} pointing to the artifacts' location in the output jar.
   */
  @VisibleForTesting
  static ProxyManifest copyStagedArtifacts(
      String retrievalToken, JarOutputStream outputStream, ArtifactRetriever retrievalServiceStub)
      throws IOException {
    GetManifestRequest manifestRequest =
        GetManifestRequest.newBuilder().setRetrievalToken(retrievalToken).build();
    ArtifactApi.Manifest manifest = retrievalServiceStub.getManifest(manifestRequest).getManifest();
    // Create a new proxy manifest to locate artifacts at jar runtime.
    ProxyManifest.Builder proxyManifestBuilder = ProxyManifest.newBuilder().setManifest(manifest);
    for (ArtifactMetadata artifact : manifest.getArtifactList()) {
      String outputPath = PortablePipelineJarUtils.ARTIFACT_FOLDER_NAME + "/" + artifact.getName();
      LOG.trace("Copying artifact to {}", outputPath);
      proxyManifestBuilder.addLocation(
          Location.newBuilder().setName(artifact.getName()).setUri("/" + outputPath).build());
      outputStream.putNextEntry(new JarEntry(outputPath));
      GetArtifactRequest artifactRequest =
          GetArtifactRequest.newBuilder()
              .setRetrievalToken(retrievalToken)
              .setName(artifact.getName())
              .build();
      Iterator<ArtifactChunk> artifactResponse = retrievalServiceStub.getArtifact(artifactRequest);
      while (artifactResponse.hasNext()) {
        artifactResponse.next().getData().writeTo(outputStream);
      }
    }
    return proxyManifestBuilder.build();
  }

  /** Writes {@code proxyManifest} to {@code outputStream} as a JSON file. */
  private static void writeProxyManifest(ProxyManifest proxyManifest, JarOutputStream outputStream)
      throws IOException {
    outputStream.putNextEntry(new JarEntry(PortablePipelineJarUtils.ARTIFACT_MANIFEST_NAME));
    try (WritableByteChannel byteChannel = Channels.newChannel(outputStream)) {
      byteChannel.write(StandardCharsets.UTF_8.encode(JsonFormat.printer().print(proxyManifest)));
    }
  }

  /**
   * Uses {@link BeamFileSystemArtifactRetrievalService} to fetch artifacts, then writes the
   * artifacts to {@code outputStream}. Include a {@link ProxyManifest} to locate artifacts later.
   */
  private static void writeArtifacts(String retrievalToken, JarOutputStream outputStream)
      throws Exception {
    try (GrpcFnServer artifactServer =
        GrpcFnServer.allocatePortAndCreateFor(
            BeamFileSystemArtifactRetrievalService.create(), InProcessServerFactory.create())) {
      ManagedChannel grpcChannel =
          InProcessManagedChannelFactory.create()
              .forDescriptor(artifactServer.getApiServiceDescriptor());
      ArtifactRetrievalServiceBlockingStub retrievalServiceStub =
          ArtifactRetrievalServiceGrpc.newBlockingStub(grpcChannel);
      ProxyManifest proxyManifest =
          copyStagedArtifacts(
              retrievalToken,
              outputStream,
              new ArtifactRetriever() {
                @Override
                public GetManifestResponse getManifest(GetManifestRequest request) {
                  return retrievalServiceStub.getManifest(request);
                }

                @Override
                public Iterator<ArtifactChunk> getArtifact(GetArtifactRequest request) {
                  return retrievalServiceStub.getArtifact(request);
                }
              });
      writeProxyManifest(proxyManifest, outputStream);
      grpcChannel.shutdown();
    }
  }

  private static class JarCreatorPipelineResult implements PortablePipelineResult {

    @Override
    public State getState() {
      return State.DONE;
    }

    @Override
    public State cancel() {
      return State.DONE;
    }

    @Override
    public State waitUntilFinish(Duration duration) {
      return State.DONE;
    }

    @Override
    public State waitUntilFinish() {
      return State.DONE;
    }

    @Override
    public MetricResults metrics() {
      throw new UnsupportedOperationException("Jar creation does not yield metrics.");
    }

    @Override
    public JobApi.MetricResults portableMetrics() throws UnsupportedOperationException {
      return JobApi.MetricResults.getDefaultInstance();
    }
  }
}
