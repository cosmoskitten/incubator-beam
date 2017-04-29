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
package org.apache.beam.sdk.io;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VoidCoder;
import org.apache.beam.sdk.io.FileBasedSink.FilenamePolicy;
import org.apache.beam.sdk.io.FileBasedSink.WritableByteChannelFactory;
import org.apache.beam.sdk.io.Read.Bounded;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;

/**
 * {@link PTransform}s for reading and writing text files.
 *
 * <p>To read a {@link PCollection} from one or more text files, use {@link TextIO.Read}.
 * You can instantiate a transform using {@link TextIO.Read#from(String)} to specify
 * the path of the file(s) to read from (e.g., a local filename or
 * filename pattern if running locally, or a Google Cloud Storage
 * filename or filename pattern of the form
 * {@code "gs://<bucket>/<filepath>"}).
 *
 * <p>{@link TextIO.Read} returns a {@link PCollection} of {@link String Strings},
 * each corresponding to one line of an input UTF-8 text file (split into lines delimited by '\n',
 * '\r', or '\r\n').
 *
 * <p>Example:
 *
 * <pre>{@code
 * Pipeline p = ...;
 *
 * // A simple Read of a local file (only runs locally):
 * PCollection<String> lines =
 *     p.apply(TextIO.read().from("/local/path/to/file.txt"));
 * }</pre>
 *
 * <p>To write a {@link PCollection} to one or more text files, use
 * {@link TextIO.Write}, specifying {@link TextIO.Write#to(String)} to specify
 * the path of the file to write to (e.g., a local filename or sharded
 * filename pattern if running locally, or a Google Cloud Storage
 * filename or sharded filename pattern of the form
 * {@code "gs://<bucket>/<filepath>"}).
 *
 * <p>By default, all input is put into the global window before writing. If per-window writes are
 * desired - for example, when using a streaming runner -
 * {@link AvroIO.Write.Bound#withWindowedWrites()} will cause windowing and triggering to be
 * preserved. When producing windowed writes, the number of output shards must be set explicitly
 * using {@link AvroIO.Write.Bound#withNumShards(int)}; some runners may set this for you to a
 * runner-chosen value, so you may need not set it yourself. A {@link FilenamePolicy} must be
 * set, and unique windows and triggers must produce unique filenames.
 *
 * <p>Any existing files with the same names as generated output files
 * will be overwritten.
 *
 * <p>For example:
 * <pre>{@code
 * // A simple Write to a local file (only runs locally):
 * PCollection<String> lines = ...;
 * lines.apply(TextIO.write().to("/path/to/file.txt"));
 *
 * // Same as above, only with Gzip compression:
 * PCollection<String> lines = ...;
 * lines.apply(TextIO.write().to("/path/to/file.txt"));
 *      .withSuffix(".txt")
 *      .withWritableByteChannelFactory(FileBasedSink.CompressionType.GZIP));
 * }</pre>
 */
public class TextIO {
  /**
   * Reads from one or more text files and returns a bounded {@link PCollection} containing one
   * element for each line of the input files.
   */
  public static Read read() {
    return new AutoValue_TextIO_Read.Builder().setCompressionType(CompressionType.AUTO).build();
  }

  /**
   * A {@link PTransform} that writes a {@link PCollection} to text file (or
   * multiple text files matching a sharding pattern), with each
   * element of the input collection encoded into its own line.
   */
  public static Write write() {
    return new AutoValue_TextIO_Write.Builder()
        .setFilenameSuffix("")
        .setNumShards(0)
        .setShardTemplate(Write.DEFAULT_SHARD_TEMPLATE)
        .setWritableByteChannelFactory(FileBasedSink.CompressionType.UNCOMPRESSED)
        .setWindowedWrites(false)
        .build();
  }

  /** Implementation of {@link #read}. */
  @AutoValue
  public abstract static class Read extends PTransform<PBegin, PCollection<String>> {
    @Nullable abstract ValueProvider<String> getFilepattern();
    abstract CompressionType getCompressionType();

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setFilepattern(ValueProvider<String> filepattern);
      abstract Builder setCompressionType(CompressionType compressionType);

      abstract Read build();
    }

    /**
     * Reads text files that reads from the file(s) with the given filename or filename pattern.
     *
     * <p>This can be a local path (if running locally), or a Google Cloud Storage filename or
     * filename pattern of the form {@code "gs://<bucket>/<filepath>"} (if running locally or using
     * remote execution service).
     *
     * <p>Standard <a href="http://docs.oracle.com/javase/tutorial/essential/io/find.html" >Java
     * Filesystem glob patterns</a> ("*", "?", "[..]") are supported.
     */
    public Read from(String filepattern) {
      checkNotNull(filepattern, "Filepattern cannot be empty.");
      return from(StaticValueProvider.of(filepattern));
    }

    /** Same as {@code from(filepattern)}, but accepting a {@link ValueProvider}. */
    public Read from(ValueProvider<String> filepattern) {
      checkNotNull(filepattern, "Filepattern cannot be empty.");
      return toBuilder().setFilepattern(filepattern).build();
    }

    /**
     * Returns a new transform for reading from text files that's like this one but
     * reads from input sources using the specified compression type.
     *
     * <p>If no compression type is specified, the default is {@link TextIO.CompressionType#AUTO}.
     */
    public Read withCompressionType(TextIO.CompressionType compressionType) {
      return toBuilder().setCompressionType(compressionType).build();
    }

    @Override
    public PCollection<String> expand(PBegin input) {
      if (getFilepattern() == null) {
        throw new IllegalStateException("need to set the filepattern of a TextIO.Read transform");
      }

      final Bounded<String> read = org.apache.beam.sdk.io.Read.from(getSource());
      PCollection<String> pcol = input.getPipeline().apply("Read", read);
      // Honor the default output coder that would have been used by this PTransform.
      pcol.setCoder(getDefaultOutputCoder());
      return pcol;
    }

    // Helper to create a source specific to the requested compression type.
    protected FileBasedSource<String> getSource() {
      switch (getCompressionType()) {
        case UNCOMPRESSED:
          return new TextSource(getFilepattern());
        case AUTO:
          return CompressedSource.from(new TextSource(getFilepattern()));
        case BZIP2:
          return
              CompressedSource.from(new TextSource(getFilepattern()))
                  .withDecompression(CompressedSource.CompressionMode.BZIP2);
        case GZIP:
          return
              CompressedSource.from(new TextSource(getFilepattern()))
                  .withDecompression(CompressedSource.CompressionMode.GZIP);
        case ZIP:
          return
              CompressedSource.from(new TextSource(getFilepattern()))
                  .withDecompression(CompressedSource.CompressionMode.ZIP);
        case DEFLATE:
          return
              CompressedSource.from(new TextSource(getFilepattern()))
                  .withDecompression(CompressedSource.CompressionMode.DEFLATE);
        default:
          throw new IllegalArgumentException("Unknown compression type: " + getFilepattern());
      }
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);

      String filepatternDisplay = getFilepattern().isAccessible()
        ? getFilepattern().get() : getFilepattern().toString();
      builder
          .add(DisplayData.item("compressionType", getCompressionType().toString())
            .withLabel("Compression Type"))
          .addIfNotNull(DisplayData.item("filePattern", filepatternDisplay)
            .withLabel("File Pattern"));
    }

    @Override
    protected Coder<String> getDefaultOutputCoder() {
      return StringUtf8Coder.of();
    }
  }


  /////////////////////////////////////////////////////////////////////////////

  /** Implementation of {@link #write}. */
  @AutoValue
  public abstract static class Write extends PTransform<PCollection<String>, PDone> {
    private static final String DEFAULT_SHARD_TEMPLATE = ShardNameTemplate.INDEX_OF_MAX;

    /** The prefix of each file written, combined with suffix and shardTemplate. */
    @Nullable abstract ValueProvider<String> getFilenamePrefix();

    /** The suffix of each file written, combined with prefix and shardTemplate. */
    abstract String getFilenameSuffix();

    /** An optional header to add to each file. */
    @Nullable abstract String getHeader();

    /** An optional footer to add to each file. */
    @Nullable abstract String getFooter();

    /** Requested number of shards. 0 for automatic. */
    abstract int getNumShards();

    /** The shard template of each file written, combined with prefix and suffix. */
    abstract String getShardTemplate();

    /** A policy for naming output files. */
    @Nullable abstract FilenamePolicy getFilenamePolicy();

    /** Whether to write windowed output files. */
    abstract boolean getWindowedWrites();

    /**
     * The {@link WritableByteChannelFactory} to be used by the {@link FileBasedSink}. Default is
     * {@link FileBasedSink.CompressionType#UNCOMPRESSED}.
     */
    abstract WritableByteChannelFactory getWritableByteChannelFactory();

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setFilenamePrefix(ValueProvider<String> filenamePrefix);
      abstract Builder setFilenameSuffix(String filenameSuffix);
      abstract Builder setHeader(String header);
      abstract Builder setFooter(String footer);
      abstract Builder setNumShards(int numShards);
      abstract Builder setShardTemplate(String shardTemplate);
      abstract Builder setFilenamePolicy(FilenamePolicy filenamePolicy);
      abstract Builder setWindowedWrites(boolean windowedWrites);
      abstract Builder setWritableByteChannelFactory(
          WritableByteChannelFactory writableByteChannelFactory);

      abstract Write build();
    }

    /**
     * Writes to text files with the given prefix. This can be a local filename
     * (if running locally), or a Google Cloud Storage filename of
     * the form {@code "gs://<bucket>/<filepath>"}
     * (if running locally or using remote execution).
     *
     * <p>The files written will begin with this prefix, followed by
     * a shard identifier (see {@link #withNumShards(int)}, and end
     * in a common extension, if given by {@link #withSuffix(String)}.
     */
    public Write to(String filenamePrefix) {
      validateOutputComponent(filenamePrefix);
      return to(StaticValueProvider.of(filenamePrefix));
    }

    /** Like {@link #to(String)}, but with a {@link ValueProvider}. */
    public Write to(ValueProvider<String> filenamePrefix) {
      return toBuilder().setFilenamePrefix(filenamePrefix).build();
    }

    /** Like {@link #to(String)}, but with a {@link FilenamePolicy}. */
    public Write to(FilenamePolicy filenamePolicy) {
      return toBuilder().setFilenamePolicy(filenamePolicy).build();
    }

    /**
     * Writes to the file(s) with the given filename suffix.
     *
     * @see ShardNameTemplate
     */
    public Write withSuffix(String nameExtension) {
      validateOutputComponent(nameExtension);
      return toBuilder().setFilenameSuffix(nameExtension).build();
    }

    /**
     * Uses the provided shard count.
     *
     * <p>Constraining the number of shards is likely to reduce
     * the performance of a pipeline. Setting this value is not recommended
     * unless you require a specific number of output files.
     *
     * @param numShards the number of shards to use, or 0 to let the system
     *                  decide.
     * @see ShardNameTemplate
     */
    public Write withNumShards(int numShards) {
      checkArgument(numShards >= 0);
      return toBuilder().setNumShards(numShards).build();
    }

    /**
     * Uses the given shard name template.
     *
     * @see ShardNameTemplate
     */
    public Write withShardNameTemplate(String shardTemplate) {
      return toBuilder().setShardTemplate(shardTemplate).build();
    }

    /**
     * Forces a single file as output.
     *
     * <p>Constraining the number of shards is likely to reduce
     * the performance of a pipeline. Using this setting is not recommended
     * unless you truly require a single output file.
     *
     * <p>This is a shortcut for
     * {@code .withNumShards(1).withShardNameTemplate("")}
     */
    public Write withoutSharding() {
      return withNumShards(1).withShardNameTemplate("");
    }

    /**
     * Adds a header string to each file. A newline after the header is added automatically.
     *
     * <p>A {@code null} value will clear any previously configured header.
     */
    public Write withHeader(@Nullable String header) {
      return toBuilder().setHeader(header).build();
    }

    /**
     * Adds a footer string to each file. A newline after the footer is added automatically.
     *
     * <p>A {@code null} value will clear any previously configured footer.
     */
    public Write withFooter(@Nullable String footer) {
      return toBuilder().setFooter(footer).build();
    }

    /**
     * Returns a transform for writing to text files like this one but that has the given
     * {@link WritableByteChannelFactory} to be used by the {@link FileBasedSink} during output.
     * The default is value is {@link FileBasedSink.CompressionType#UNCOMPRESSED}.
     *
     * <p>A {@code null} value will reset the value to the default value mentioned above.
     */
    public Write withWritableByteChannelFactory(
        WritableByteChannelFactory writableByteChannelFactory) {
      return toBuilder().setWritableByteChannelFactory(writableByteChannelFactory).build();
    }

    public Write withWindowedWrites() {
      return toBuilder().setWindowedWrites(true).build();
    }

    @Override
    public PDone expand(PCollection<String> input) {
      if (getFilenamePolicy() == null && getFilenamePrefix() == null) {
        throw new IllegalStateException(
            "need to set the filename prefix of an TextIO.Write transform");
      }
      if (getFilenamePolicy() != null && getFilenamePrefix() != null) {
        throw new IllegalStateException(
            "cannot set both a filename policy and a filename prefix");
      }
      WriteFiles<String> write;
      if (getFilenamePolicy() != null) {
        write =
            WriteFiles.to(
                new TextSink(
                    getFilenamePolicy(),
                    getHeader(),
                    getFooter(),
                    getWritableByteChannelFactory()));
      } else {
        write =
            WriteFiles.to(
                new TextSink(
                    getFilenamePrefix(),
                    getFilenameSuffix(),
                    getHeader(),
                    getFooter(),
                    getShardTemplate(),
                    getWritableByteChannelFactory()));
      }
      if (getNumShards() > 0) {
        write = write.withNumShards(getNumShards());
      }
      if (getWindowedWrites()) {
        write = write.withWindowedWrites();
      }
      return input.apply("WriteFiles", write);
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);

      String prefixString = "";
      if (getFilenamePrefix() != null) {
        prefixString = getFilenamePrefix().isAccessible()
            ? getFilenamePrefix().get() : getFilenamePrefix().toString();
      }
      builder
          .addIfNotNull(DisplayData.item("filePrefix", prefixString)
            .withLabel("Output File Prefix"))
          .addIfNotDefault(DisplayData.item("fileSuffix", getFilenameSuffix())
            .withLabel("Output File Suffix"), "")
          .addIfNotDefault(DisplayData.item("shardNameTemplate", getShardTemplate())
            .withLabel("Output Shard Name Template"),
              DEFAULT_SHARD_TEMPLATE)
          .addIfNotDefault(DisplayData.item("numShards", getNumShards())
            .withLabel("Maximum Output Shards"), 0)
          .addIfNotNull(DisplayData.item("fileHeader", getHeader())
            .withLabel("File Header"))
          .addIfNotNull(DisplayData.item("fileFooter", getFooter())
              .withLabel("File Footer"))
          .add(DisplayData
              .item("writableByteChannelFactory", getWritableByteChannelFactory().toString())
              .withLabel("Compression/Transformation Type"));
    }

    @Override
    protected Coder<Void> getDefaultOutputCoder() {
      return VoidCoder.of();
    }
  }

  /**
   * Possible text file compression types.
   */
  public enum CompressionType {
    /**
     * Automatically determine the compression type based on filename extension.
     */
    AUTO(""),
    /**
     * Uncompressed (i.e., may be split).
     */
    UNCOMPRESSED(""),
    /**
     * GZipped.
     */
    GZIP(".gz"),
    /**
     * BZipped.
     */
    BZIP2(".bz2"),
    /**
     * Zipped.
     */
    ZIP(".zip"),
    /**
     * Deflate compressed.
     */
    DEFLATE(".deflate");

    private String filenameSuffix;

    CompressionType(String suffix) {
      this.filenameSuffix = suffix;
    }

    /**
     * Determine if a given filename matches a compression type based on its extension.
     * @param filename the filename to match
     * @return true iff the filename ends with the compression type's known extension.
     */
    public boolean matches(String filename) {
      return filename.toLowerCase().endsWith(filenameSuffix.toLowerCase());
    }
  }

  // Pattern which matches old-style shard output patterns, which are now
  // disallowed.
  private static final Pattern SHARD_OUTPUT_PATTERN = Pattern.compile("@([0-9]+|\\*)");

  private static void validateOutputComponent(String partialFilePattern) {
    checkArgument(
        !SHARD_OUTPUT_PATTERN.matcher(partialFilePattern).find(),
        "Output name components are not allowed to contain @* or @N patterns: "
        + partialFilePattern);
  }

  //////////////////////////////////////////////////////////////////////////////

  /** Disable construction of utility class. */
  private TextIO() {}
}
