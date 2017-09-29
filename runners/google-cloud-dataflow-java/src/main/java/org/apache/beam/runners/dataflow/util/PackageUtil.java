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
package org.apache.beam.runners.dataflow.util;

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.core.Base64Variants;
import com.google.api.client.util.BackOff;
import com.google.api.client.util.Sleeper;
import com.google.api.services.dataflow.model.DataflowPackage;
import com.google.auto.value.AutoValue;
import com.google.cloud.hadoop.util.ApiErrorExtractor;
import com.google.common.base.Function;
import com.google.common.hash.Funnels;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.CountingOutputStream;
import com.google.common.io.Files;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.extensions.gcp.storage.GcsCreateOptions;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.CreateOptions;
import org.apache.beam.sdk.io.fs.ResolveOptions.StandardResolveOptions;
import org.apache.beam.sdk.util.BackOffAdapter;
import org.apache.beam.sdk.util.FluentBackoff;
import org.apache.beam.sdk.util.MimeTypes;
import org.apache.beam.sdk.util.ZipFiles;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper routines for packages. */
@Internal
class PackageUtil implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(PackageUtil.class);

  /**
   * A reasonable upper bound on the number of jars required to launch a Dataflow job.
   */
  private static final int SANE_CLASSPATH_SIZE = 1000;

  private static final int DEFAULT_THREAD_POOL_SIZE = 32;

  private static final Sleeper DEFAULT_SLEEPER = Sleeper.DEFAULT;

  private static final CreateOptions DEFAULT_CREATE_OPTIONS =
      GcsCreateOptions.builder()
          .setGcsUploadBufferSizeBytes(1024 * 1024)
          .setMimeType(MimeTypes.BINARY)
          .build();

  private static final FluentBackoff BACKOFF_FACTORY =
      FluentBackoff.DEFAULT.withMaxRetries(4).withInitialBackoff(Duration.standardSeconds(5));

  /**
   * Translates exceptions from API calls.
   */
  private static final ApiErrorExtractor ERROR_EXTRACTOR = new ApiErrorExtractor();

  private final ListeningExecutorService executorService;

  private PackageUtil(ListeningExecutorService executorService) {
    this.executorService = executorService;
  }

  public static PackageUtil withDefaultThreadPool() {
    return PackageUtil.withExecutorService(
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE)));
  }

  public static PackageUtil withExecutorService(ListeningExecutorService executorService) {
    return new PackageUtil(executorService);
  }

  @Override
  public void close() {
    executorService.shutdown();
  }


  /** Utility comparator used in uploading packages efficiently. */
  private static class PackageUploadOrder implements Comparator<PackageAttributes> {
    @Override
    public int compare(PackageAttributes o1, PackageAttributes o2) {
      // Smaller size compares high so that bigger packages are uploaded first.
      long sizeDiff = o2.getSize() - o1.getSize();
      if (sizeDiff != 0) {
        // returns sign of long
        return Long.signum(sizeDiff);
      }

      // Otherwise, choose arbitrarily based on hash.
      return o1.getHash().compareTo(o2.getHash());
    }
  }

  /** Asynchronously computes {@link PackageAttributes} for a single staged file. */
  private ListenableFuture<PackageAttributes> computePackageAttributes(
      final DataflowPackage source, final String stagingPath) {

    return executorService.submit(
        new Callable<PackageAttributes>() {
          @Override
          public PackageAttributes call() throws Exception {
            final File file = new File(source.getLocation());
            if (!file.exists()) {
              throw new FileNotFoundException(
                  String.format("Non-existent file to stage: %s", file.getAbsolutePath()));
            }

            PackageAttributes attributes = PackageAttributes.forFileToStage(file, stagingPath);
            if (source.getName() != null) {
              attributes = attributes.withPackageName(source.getName());
            }
            return attributes;
          }
        });
  }

  private boolean alreadyStaged(PackageAttributes attributes) throws IOException {
    try {
      long remoteLength =
          FileSystems.matchSingleFileSpec(attributes.getDestination().getLocation()).sizeBytes();
      return remoteLength == attributes.getSize();
    } catch (FileNotFoundException expected) {
      // If the file doesn't exist, it means we need to upload it.
      return false;
    }
  }

  /** Stages one file ("package") if necessary. */
  public ListenableFuture<StagingResult> stagePackage(
      final PackageAttributes attributes,
      final Sleeper retrySleeper,
      final CreateOptions createOptions) {
    return executorService.submit(
        new Callable<StagingResult>() {
          @Override
          public StagingResult call() throws Exception {
            return stagePackageSynchronously(attributes, retrySleeper, createOptions);
          }
        });
  }

  private StagingResult stagePackageSynchronously(
      PackageAttributes attributes, Sleeper retrySleeper, CreateOptions createOptions) {
    try {
      return stagePackageBlithely(attributes, retrySleeper, createOptions);
    } catch (Exception e) {
      throw new RuntimeException(
          String.format(
              "Could not stage classpath element: %s to %s",
              attributes.getSource(), attributes.getDestination().getLocation()),
          e);
    }
  }

  private StagingResult stagePackageBlithely(
      PackageAttributes attributes, Sleeper retrySleeper, CreateOptions createOptions)
      throws IOException, InterruptedException {
    File source = attributes.getSource();
    String target = attributes.getDestination().getLocation();

    // TODO: Should we attempt to detect the Mime type rather than
    // always using MimeTypes.BINARY?
    if (alreadyStaged(attributes)) {
      LOG.debug("Skipping file already staged: {} at {}", attributes.getSource(), target);
      return StagingResult.cached(attributes);
    }

    // Upload file, retrying on failure.
    BackOff backoff = BackOffAdapter.toGcpBackOff(BACKOFF_FACTORY.backoff());
    while (true) {
      try {
        LOG.info("Uploading {} to {}", source, target);
        try (WritableByteChannel writer =
            FileSystems.create(FileSystems.matchNewResource(target, false), createOptions)) {
          copyContent(attributes.getSource(), writer);
        }
        return StagingResult.uploaded(attributes);
      } catch (IOException e) {
        if (ERROR_EXTRACTOR.accessDenied(e)) {
          String errorMessage =
              String.format(
                  "Uploaded failed due to permissions error, will NOT retry staging "
                      + "of %s. Please verify credentials are valid and that you have "
                      + "write access to %s. Stale credentials can be resolved by executing "
                      + "'gcloud auth application-default login'.",
                  source, target);
          LOG.error(errorMessage);
          throw new IOException(errorMessage, e);
        }
        long sleep = backoff.nextBackOffMillis();
        if (sleep == BackOff.STOP) {
          // Rethrow last error, to be included as a cause in the catch below.
          LOG.error("Upload failed, will NOT retry staging of classpath: {}", source, e);
          throw e;
        } else {
          LOG.warn(
              "Upload attempt failed, sleeping before retrying staging of classpath: {}",
              source,
              e);
          retrySleeper.sleep(sleep);
        }
      }
    }
  }

  /**
   * Transfers the classpath elements to the staging location using a default {@link Sleeper}.
   *
   * @see {@link #stageClasspathElements(Collection, String, Sleeper, CreateOptions)}
   */
  List<DataflowPackage> stageClasspathElements(
      Collection<String> classpathElements, String stagingPath, CreateOptions createOptions) {
    return stageClasspathElements(classpathElements, stagingPath, DEFAULT_SLEEPER, createOptions);
  }

  /**
   * Transfers the classpath elements to the staging location using default settings.
   *
   * @see {@link #stageClasspathElements(Collection, String, Sleeper, CreateOptions)}
   */
  List<DataflowPackage> stageClasspathElements(
      Collection<String> classpathElements, String stagingPath) {
    return stageClasspathElements(
        classpathElements, stagingPath, DEFAULT_SLEEPER, DEFAULT_CREATE_OPTIONS);
  }

  /**
   * Transfers the classpath elements to the staging location.
   *
   * @param classpathElements The elements to stage.
   * @param stagingPath The base location to stage the elements to.
   * @return A list of cloud workflow packages, each representing a classpath element.
   */
  List<DataflowPackage> stageClasspathElements(
      Collection<String> classpathElements,
      final String stagingPath,
      final Sleeper retrySleeper,
      final CreateOptions createOptions) {
    LOG.info("Uploading {} files from PipelineOptions.filesToStage to staging location to "
        + "prepare for execution.", classpathElements.size());

    if (classpathElements.size() > SANE_CLASSPATH_SIZE) {
      LOG.warn("Your classpath contains {} elements, which Google Cloud Dataflow automatically "
            + "copies to all workers. Having this many entries on your classpath may be indicative "
            + "of an issue in your pipeline. You may want to consider trimming the classpath to "
            + "necessary dependencies only, using --filesToStage pipeline option to override "
            + "what files are being staged, or bundling several dependencies into one.",
          classpathElements.size());
    }

    checkArgument(
        stagingPath != null,
        "Can't stage classpath elements because no staging location has been provided");

    final AtomicInteger numUploaded = new AtomicInteger(0);
    final AtomicInteger numCached = new AtomicInteger(0);
    List<ListenableFuture<DataflowPackage>> destinationPackages = new ArrayList<>();

    for (String classpathElement : classpathElements) {
      DataflowPackage sourcePackage = new DataflowPackage();
      if (classpathElement.contains("=")) {
        String[] components = classpathElement.split("=", 2);
        sourcePackage.setName(components[0]);
        sourcePackage.setLocation(components[1]);
      } else {
        sourcePackage.setName(null);
        sourcePackage.setLocation(classpathElement);
      }

      File sourceFile = new File(sourcePackage.getLocation());
      if (!sourceFile.exists()) {
        LOG.warn("Skipping non-existent file to stage {}.", sourceFile);
        continue;
      }

      // TODO: Java 8 / Guava 23.0: FluentFuture
      ListenableFuture<StagingResult> stagingResult =
          Futures.transformAsync(
              computePackageAttributes(sourcePackage, stagingPath),
              new AsyncFunction<PackageAttributes, StagingResult>() {
                @Override
                public ListenableFuture<StagingResult> apply(
                    final PackageAttributes packageAttributes) throws Exception {
                  return stagePackage(packageAttributes, retrySleeper, createOptions);
                }
              });

      ListenableFuture<DataflowPackage> stagedPackage =
          Futures.transform(
              stagingResult,
              new Function<StagingResult, DataflowPackage>() {
                @Override
                public DataflowPackage apply(StagingResult stagingResult) {
                  if (stagingResult.alreadyStaged()) {
                    numCached.incrementAndGet();
                  } else {
                    numUploaded.incrementAndGet();
                  }
                  return stagingResult.getPackageAttributes().getDestination();
                }
              });

      destinationPackages.add(stagedPackage);
    }

    try {
      List<DataflowPackage> stagedPackages = Futures.allAsList(destinationPackages).get();
      LOG.info(
          "Staging files complete: {} files cached, {} files newly uploaded",
          numCached.get(), numUploaded.get());
      return stagedPackages;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while staging packages", e);
    } catch (ExecutionException e) {
      throw new RuntimeException("Error while staging packages", e.getCause());
    }
  }

  /**
   * Returns a unique name for a file with a given content hash.
   *
   * <p>Directory paths are removed. Example:
   * <pre>
   * dir="a/b/c/d", contentHash="f000" => d-f000.jar
   * file="a/b/c/d.txt", contentHash="f000" => d-f000.txt
   * file="a/b/c/d", contentHash="f000" => d-f000
   * </pre>
   */
  static String getUniqueContentName(File classpathElement, String contentHash) {
    String fileName = Files.getNameWithoutExtension(classpathElement.getAbsolutePath());
    String fileExtension = Files.getFileExtension(classpathElement.getAbsolutePath());
    if (classpathElement.isDirectory()) {
      return fileName + "-" + contentHash + ".jar";
    } else if (fileExtension.isEmpty()) {
      return fileName + "-" + contentHash;
    }
    return fileName + "-" + contentHash + "." + fileExtension;
  }

  /**
   * Copies the contents of the classpathElement to the output channel.
   *
   * <p>If the classpathElement is a directory, a Zip stream is constructed on the fly,
   * otherwise the file contents are copied as-is.
   *
   * <p>The output channel is not closed.
   */
  private static void copyContent(File classpathElement, WritableByteChannel outputChannel)
      throws IOException {
    if (classpathElement.isDirectory()) {
      ZipFiles.zipDirectory(classpathElement, Channels.newOutputStream(outputChannel));
    } else {
      Files.asByteSource(classpathElement).copyTo(Channels.newOutputStream(outputChannel));
    }
  }

  @AutoValue
  abstract static class StagingResult {
    abstract PackageAttributes getPackageAttributes();

    abstract boolean alreadyStaged();

    public static StagingResult cached(PackageAttributes attributes) {
      return new AutoValue_PackageUtil_StagingResult(attributes, true);
    }

    public static StagingResult uploaded(PackageAttributes attributes) {
      return new AutoValue_PackageUtil_StagingResult(attributes, false);
    }
  }

  /**
   * Holds the metadata necessary to stage a file or confirm that a staged file has not changed.
   */
  @AutoValue
  abstract static class PackageAttributes {

    public static PackageAttributes forFileToStage(File source, String stagingPath)
        throws IOException {

      // Compute size and hash in one pass over file or directory.
      long size;
      String hash;
      Hasher hasher = Hashing.md5().newHasher();
      OutputStream hashStream = Funnels.asOutputStream(hasher);
      try (CountingOutputStream countingOutputStream = new CountingOutputStream(hashStream)) {
        if (!source.isDirectory()) {
          // Files are staged as-is.
          Files.asByteSource(source).copyTo(countingOutputStream);
        } else {
          // Directories are recursively zipped.
          ZipFiles.zipDirectory(source, countingOutputStream);
        }
        countingOutputStream.flush();

        size = countingOutputStream.getCount();
        hash = Base64Variants.MODIFIED_FOR_URL.encode(hasher.hash().asBytes());
      }

      String uniqueName = getUniqueContentName(source, hash);

      String resourcePath =
          FileSystems.matchNewResource(stagingPath, true)
              .resolve(uniqueName, StandardResolveOptions.RESOLVE_FILE)
              .toString();
      DataflowPackage target = new DataflowPackage();
      target.setName(uniqueName);
      target.setLocation(resourcePath);

      return new AutoValue_PackageUtil_PackageAttributes(source, target, size, hash);
    }

    public PackageAttributes withPackageName(String overridePackageName) {
      DataflowPackage newDestination = new DataflowPackage();
      newDestination.setName(overridePackageName);
      newDestination.setLocation(getDestination().getLocation());

      return new AutoValue_PackageUtil_PackageAttributes(
          getSource(), newDestination, getSize(), getHash());
    }

    /** @return the file to be uploaded */
    public abstract File getSource();

    /** @return the dataflowPackage */
    public abstract DataflowPackage getDestination();

    /** @return the size */
    public abstract long getSize();

    /** @return the hash */
    public abstract String getHash();
  }
}
