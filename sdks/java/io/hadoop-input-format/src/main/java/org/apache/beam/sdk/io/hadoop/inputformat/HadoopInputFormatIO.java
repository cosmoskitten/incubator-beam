/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.beam.sdk.io.hadoop.inputformat;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.io.hadoop.inputformat.coders.WritableCoder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * A {@link HadoopInputFormatIO} is a {@link Transform} for reading data from any source which
 * implements Hadoop InputFormat. For example- Cassandra, Elasticsearch, HBase, Redis, Postgres etc.
 * HadoopInputFormatIO has to make several performance trade-offs in connecting to InputFormat, so
 * if there is another Beam IO Transform specifically for connecting to your data source of choice,
 * we would recommend using that one, but this IO Transform allows you to connect to many data
 * sources that do not yet have a Beam IO Transform.
 * <p>
 * You will need to pass a Hadoop {@link Configuration} with parameters specifying how the read will
 * occur. Many properties of the Configuration are optional, and some are required for certain
 * InputFormat classes, but the following properties must be set for all InputFormats:
 * <ul>
 * <li>mapreduce.job.inputformat.class : The InputFormat class used to connect to your data source
 * of choice.</li>
 * <li>key.class : The key class returned by the InputFormat in
 * 'mapreduce.job.inputformat.class'.</li>
 * <li>value.class : The value class returned by the InputFormat in
 * 'mapreduce.job.inputformat.class'.</li>
 * </ul>
 * For example:
 * 
 * <pre>
 * {@code
 *   Configuration myHadoopConfiguration = new Configuration(false);
 *   // Set Hadoop InputFormat, key and value class in configuration
 *   myHadoopConfiguration.setClass("mapreduce.job.inputformat.class", InputFormatClass,
 *       InputFormat.class);
 *   myHadoopConfiguration.setClass("key.class", InputFormatKeyClass, Object.class);
 *   myHadoopConfiguration.setClass("value.class", InputFormatValueClass, Object.class);
 * }
 * </pre>
 * <p>
 * You will need to check to see if the key and value classes output by the InputFormat have a Beam
 * {@link Coder} available. If not, You can use withKeyTranslation/withValueTranslation to specify a
 * method transforming instances of those classes into another class that is supported by a Beam
 * {@link Coder}. These settings are optional and you don't need to specify translation for both key
 * and value.
 * <p>
 * For example:
 * 
 * <pre> 
 * {@code
 *   SimpleFunction<InputFormatKeyClass, MyKeyClass> myOutputKeyType =
 *       new SimpleFunction<InputFormatKeyClass, MyKeyClass>() {
 *         public MyKeyClass apply(InputFormatKeyClass input) {
 *           // ...logic to transform InputFormatKeyClass to MyKeyClass
 *         }
 *       };
 * 
 *   SimpleFunction<InputFormatValueClass, MyValueClass> myOutputValueType =
 *       new SimpleFunction<InputFormatValueClass, MyValueClass>() {
 *         public MyValueClass apply(InputFormatValueClass input) {
 *           // ...logic to transform InputFormatValueClass to MyValueClass
 *         }
 *       };
 * }
 * </pre>
 * 
 * <h3>Reading using HadoopInputFormatIO</h3> 
 * Pipeline p = ...; // Create pipeline.
 * <P>
 * // Read data only with Hadoop configuration.
 * 
 * <pre>
 * {@code 
 * p.apply("read",
 *     HadoopInputFormatIO.<InputFormatKeyClass, InputFormatKeyClass>read() 
 *              .withConfiguration(myHadoopConfiguration); 
 * }
 * </pre>
 * <P>
 * // Read data with configuration and key translation (Example scenario: Beam Coder is not
 * available for key class hence key translation is required.).
 * 
 * <pre>
 * {@code 
 * p.apply("read",
 *     HadoopInputFormatIO.<MyKeyClass, InputFormatKeyClass>read() 
 *              .withConfiguration(myHadoopConfiguration) 
 *              .withKeyTranslation(myOutputKeyType);
 * }
 * </pre>
 * <P>
 * // Read data with configuration and value translation (Example scenario: Beam Coder is not
 * available for value class hence value translation is required.).
 * 
 * <pre>
 * {@code 
 * p.apply("read",
 *     HadoopInputFormatIO.<InputFormatKeyClass, MyValueClass>read() 
 *              .withConfiguration(myHadoopConfiguration) 
 *              .withValueTranslation(myOutputValueType);
 * }
 * </pre>
 * 
 * <P>
 * // Read data with configuration, value translation and key translation (Example scenario: Beam
 * Coders are not available for both key class and value class of InputFormat hence key and value
 * translation is required.).
 * 
 * <pre>
 * {@code 
 * p.apply("read",
 *     HadoopInputFormatIO.<MyKeyClass, MyValueClass>read() 
 *              .withConfiguration(myHadoopConfiguration) 
 *              .withKeyTranslation(myOutputKeyType)
 *              .withValueTranslation(myOutputValueType);
 * }
 * </pre>
 * 
 * <h3>Read data from Cassandra using HadoopInputFormatIO transform</h3>
 * <p>
 * To read data from Cassandra, {@link org.apache.cassandra.hadoop.cql3.CqlInputFormat
 * CqlInputFormat} can be used which needs following properties to be set.
 * <p>
 * Create Cassandra Hadoop configuration as follows:
 * 
 * <pre>
 * {@code 
 * Configuration cassandraConf = new Configuration();
 *   cassandraConf.set("cassandra.input.thrift.port" , "9160");
 *   cassandraConf.set("cassandra.input.thrift.address" , CassandraHostIp);
 *   cassandraConf.set("cassandra.input.partitioner.class" , "Murmur3Partitioner");
 *   cassandraConf.set("cassandra.input.keyspace" , "myKeySpace");
 *   cassandraConf.set("cassandra.input.columnfamily" , "myColumnFamily");
 *   cassandraConf.setClass("key.class" ,{@link java.lang.Long Long.class} , Object.class);
 *   cassandraConf.setClass("value.class" ,{@link com.datastax.driver.core.Row Row.class} , Object.class);
 *   cassandraConf.setClass("mapreduce.job.inputformat.class" ,{@link org.apache.cassandra.hadoop.cql3.CqlInputFormat CqlInputFormat.class} , InputFormat.class);
 *   }
 * </pre>
 * <p>
 * Call Read transform as follows:
 * 
 * <pre>
 * {@code
 * PCollection<KV<Long,String>> cassandraData =
 *          p.apply("read",
 *                  HadoopInputFormatIO.<Long, String>read() 
 *                      .withConfiguration( cassandraConf ) 
 *                      .withValueTranslation( cassandraOutputValueType );
 * }
 * </pre>
 * <p>
 * The CqlInputFormat key class is {@link java.lang.Long Long}, which has a Beam Coder. The
 * CqlInputFormat value class is {@link com.datastax.driver.core.Row Row}, which does not have a
 * Beam Coder. Rather than write a coder, we will provide our own translation method as follows:
 * 
 * <pre>
 *  
 * {@code
 * SimpleFunction<Row, String> cassandraOutputValueType = SimpleFunction<Row, String>() 
 * {
 *    public String apply(Row row) {
 *      return row.getString('myColName'); 
 *    }
 * };
 * }
 * </pre>
 * 
 * <h3>Read data from Elasticsearch using HadoopInputFormatIO transform</h3>
 * <p>
 * To read data from Elasticsearch, EsInputFormat can be used which needs following properties to be
 * set.
 * <p>
 * Create ElasticSearch Hadoop configuration as follows:
 * 
 * <pre>
 * {@code
 * Configuration elasticSearchConf = new Configuration();
 *   elasticSearchConf.set("es.nodes", ElasticsearchHostIp);
 *   elasticSearchConf.set("es.port", "9200");
 *   elasticSearchConf.set("es.resource", "ElasticIndexName/ElasticTypeName");
 *   elasticSearchConf.setClass("key.class", {@link org.apache.hadoop.io.Text Text.class}, Object.class);
 *   elasticSearchConf.setClass("value.class", {@link org.elasticsearch.hadoop.mr.LinkedMapWritable LinkedMapWritable.class} , Object.class); 
 *   elasticSearchConf.setClass("mapreduce.job.inputformat.class", {@link org.elasticsearch.hadoop.mr.EsInputFormat EsInputFormat.class}, InputFormat.class);
 * }
 * </pre>
 * 
 * Call Read transform as follows:
 * 
 * <pre>
 * {@code
 *   PCollection<KV<Text, LinkedMapWritable>> elasticData = p.apply("read",
 *       HadoopInputFormatIO.<Text, LinkedMapWritable>read().withConfiguration(elasticSearchConf));
 * }
 * </pre>
 * 
 * <p>
 * The {@link org.elasticsearch.hadoop.mr.EsInputFormat EsInputFormat} key class is
 * {@link org.apache.hadoop.io.Text Text} and value class is
 * {@link org.elasticsearch.hadoop.mr.LinkedMapWritable LinkedMapWritable}. Both key and value
 * classes have Beam Coders.
 */

public class HadoopInputFormatIO {
  private static final Logger LOG = LoggerFactory.getLogger(HadoopInputFormatIO.class);

  /**
   * Creates an uninitialized HadoopInputFormatIO.Read. Before use, the {@code Read} must be
   * initialized with a HadoopInputFormatIO.Read#withConfiguration(HadoopConfiguration) that
   * specifies the source. A key/value translation may also optionally be specified using
   * HadoopInputFormatIO.Read#withKeyTranslation/HadoopInputFormatIO.Read#withValueTranslation.
   */
  public static <K, V> Read<K, V> read() {
    return new AutoValue_HadoopInputFormatIO_Read.Builder<K, V>().build();
  }

  /**
   * A {@link PTransform} that reads from for any data source which implements Hadoop InputFormat.
   * For e.g. Cassandra, Elasticsearch, HBase, Redis, Postgres etc. See the class-level Javadoc on
   * {@link HadoopInputFormatIO} for more information.
   *
   * @param <K> Type of keys to be read.
   * @param <V> Type of values to be read.
   * @see HadoopInputFormatIO
   */
  @AutoValue
  public abstract static class Read<K, V> extends PTransform<PBegin, PCollection<KV<K, V>>> {

    public static TypeDescriptor<?> inputFormatClass;
    public static TypeDescriptor<?> inputFormatKeyClass;
    public static TypeDescriptor<?> inputFormatValueClass;

    /**
     * Returns the Hadoop Configuration which contains specification of source.
     */
    @Nullable public abstract SerializableConfiguration getConfiguration();
    @Nullable public abstract SimpleFunction<?, K> getKeyTranslationFunction();
    @Nullable public abstract SimpleFunction<?, V> getValueTranslationFunction();
    @Nullable public abstract TypeDescriptor<K> getKeyClass();
    @Nullable public abstract TypeDescriptor<V> getValueClass();

    abstract Builder<K, V> toBuilder();

    @AutoValue.Builder
    abstract static class Builder<K, V> {
      abstract Builder<K, V> setConfiguration(SerializableConfiguration configuration);
      abstract Builder<K, V> setKeyTranslationFunction(SimpleFunction<?, K> function);
      abstract Builder<K, V> setValueTranslationFunction(SimpleFunction<?, V> function);
      abstract Builder<K, V> setKeyClass(TypeDescriptor<K> keyClass);
      abstract Builder<K, V> setValueClass(TypeDescriptor<V> valueClass);
      abstract Read<K, V> build();
    }

    /**
     * Returns a new {@link HadoopInputFormatIO.Read} that will read from the source using the
     * options indicated by the given configuration.
     *
     * <p>
     * Does not modify this object.
     */
    public Read<K, V> withConfiguration(Configuration configuration) {
      validateConfiguration(configuration);
      inputFormatClass = TypeDescriptor
          .of(configuration.getClass(HadoopInputFormatIOContants.INPUTFORMAT_CLASSNAME, null));
      inputFormatKeyClass = TypeDescriptor
          .of(configuration.getClass(HadoopInputFormatIOContants.KEY_CLASS, null));
      inputFormatValueClass = TypeDescriptor
          .of(configuration.getClass(HadoopInputFormatIOContants.VALUE_CLASS, null));
      // Sets the configuration.
      Builder<K, V> builder = toBuilder()
          .setConfiguration(new SerializableConfiguration(configuration));
      /*
       * Sets the output key class to InputFormat key class if withKeyTranslation() is not called
       * yet.
       */
      if ( this.getKeyTranslationFunction() == null) {
        builder.setKeyClass((TypeDescriptor<K>) inputFormatKeyClass);
      }
      /*
       * Sets the output value class to InputFormat value class if withValueTranslation() is not
       * called yet.
       */
      if ( this.getValueTranslationFunction() == null) {
        builder.setValueClass((TypeDescriptor<V>) inputFormatValueClass);
      }
      return builder.build();
    }

    /**
     * Validates that the mandatory configuration properties such as InputFormat class, InputFormat
     * key class and InputFormat value class are provided in the given Hadoop configuration.
     */
    private void validateConfiguration(Configuration configuration) {
      checkNotNull(configuration, HadoopInputFormatIOContants.NULL_CONFIGURATION_ERROR_MSG);
      checkNotNull(configuration.get("mapreduce.job.inputformat.class"),
          HadoopInputFormatIOContants.MISSING_INPUTFORMAT_ERROR_MSG);
      checkNotNull(configuration.get("key.class"),
          HadoopInputFormatIOContants.MISSING_INPUTFORMAT_KEY_CLASS_ERROR_MSG);
      checkNotNull(configuration.get("value.class"),
          HadoopInputFormatIOContants.MISSING_INPUTFORMAT_VALUE_CLASS_ERROR_MSG);
    }

    /**
     * Returns a new {@link HadoopInputFormatIO.Read} that will transform the keys read from the
     * source using the given key translation function.
     *
     * <p>
     * Does not modify this object.
     */
    public Read<K, V> withKeyTranslation(SimpleFunction<?, K> function) {
      checkNotNull(function, HadoopInputFormatIOContants.NULL_KEY_TRANSLATIONFUNC_ERROR_MSG);
      // Sets key class to key translation function's output class type.
      return toBuilder().setKeyTranslationFunction(function)
          .setKeyClass((TypeDescriptor<K>) function.getOutputTypeDescriptor()).build();
    }

    /**
     * Returns a new {@link HadoopInputFormatIO.Read} that will transform the values read from the
     * source using the given value translation function.
     *
     * <p>
     * Does not modify this object.
     */
    public Read<K, V> withValueTranslation(SimpleFunction<?, V> function) {
      checkNotNull(function, HadoopInputFormatIOContants.NULL_VALUE_TRANSLATIONFUNC_ERROR_MSG);
      // Sets value class to value translation function's output class type.
      return toBuilder().setValueTranslationFunction(function)
          .setValueClass((TypeDescriptor<V>) function.getOutputTypeDescriptor()).build();
    }

    @Override
    public PCollection<KV<K, V>> expand(PBegin input) {
      HadoopInputFormatBoundedSource<K, V> source = new HadoopInputFormatBoundedSource<K, V>(
          this.getConfiguration(),
          this.getKeyCoder(),
          this.getValueCoder(),
          this.getKeyTranslationFunction(),
          this.getValueTranslationFunction(),
          null);
      return input.getPipeline().apply(org.apache.beam.sdk.io.Read.from(source));
    }

    /**
     * Validates inputs provided by the pipeline user before reading the data.
     */
    @Override
    public void validate(PBegin input) {
      checkNotNull(this.getConfiguration(),
          HadoopInputFormatIOContants.MISSING_CONFIGURATION_ERROR_MSG);
      // Validate that the key translation input type must be same as key class of InputFormat.
      validateTranslationFunction(inputFormatKeyClass, getKeyTranslationFunction(),
          HadoopInputFormatIOContants.WRONG_KEY_TRANSLATIONFUNC_ERROR_MSG);
      // Validate that the value translation input type must be same as value class of InputFormat.
      validateTranslationFunction(inputFormatValueClass, getValueTranslationFunction(),
          HadoopInputFormatIOContants.WRONG_VALUE_TRANSLATIONFUNC_ERROR_MSG);
      getKeyAndValueCoder(input);
    }


    /**
     * Validates translation function given for key/value translation.
     */
    private void validateTranslationFunction(TypeDescriptor<?> inputType,
        SimpleFunction<?, ?> simpleFunction, String errorMsg) {
      if (simpleFunction != null) {
        if (!simpleFunction.getInputTypeDescriptor().equals(inputType)) {
          throw new IllegalArgumentException(
              String.format(errorMsg, inputFormatClass.getRawType(), inputType.getRawType()));
        }
      }
    }

    private Coder<K> keyCoder;
    private Coder<V> valueCoder;

    /**
     *  Sets the key and value coder based on the key class and value class.
     */
    protected void getKeyAndValueCoder(PBegin input) {
      CoderRegistry coderRegistry = input.getPipeline().getCoderRegistry();
      keyCoder = getDefaultCoder(getKeyClass(), coderRegistry);
      valueCoder = getDefaultCoder(getValueClass(), coderRegistry);
    }

    /**
     * Returns the default coder for a given type descriptor. If type descriptor class is of type
     * Writable, then WritableCoder is returned, else CoderRegistry is queried for the correct
     * coder.
     */
    public <T> Coder<T> getDefaultCoder(TypeDescriptor<?> typeDesc, CoderRegistry coderRegistry) {
      Class classType = typeDesc.getRawType();
      if (Writable.class.isAssignableFrom(classType)) {
        return (Coder<T>) WritableCoder.of(classType);
      } else {
        try {
          return (Coder<T>) coderRegistry.getCoder(typeDesc);
        } catch (CannotProvideCoderException e) {
          throw new IllegalStateException(
              String.format(HadoopInputFormatIOContants.CANNOT_FIND_CODER_ERROR_MSG, typeDesc)
                  + e.getMessage(),
              e);
        }
      }
    }

    public Coder<K> getKeyCoder() {
      return keyCoder;
    }

    public Coder<V> getValueCoder() {
      return valueCoder;
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      if (this.getConfiguration().getHadoopConfiguration() != null) {
        Iterator<Entry<String, String>> configProperties = this.getConfiguration()
            .getHadoopConfiguration().iterator();
        while (configProperties.hasNext()) {
          Entry<String, String> property = configProperties.next();
          builder.add(DisplayData.item(property.getKey(), property.getValue())
              .withLabel(property.getKey()));
        }
      }
      builder
          .addIfNotNull(DisplayData.item("KeyClass", getKeyClass().getRawType())
              .withLabel("Output key class"))
          .addIfNotNull(DisplayData.item("ValueClass", getValueClass().getRawType())
              .withLabel("Output value class"));
      if (getKeyTranslationFunction() != null)
        builder.addIfNotNull(DisplayData
            .item("KeyTranslation", getKeyTranslationFunction().toString())
            .withLabel("Key translation SimpleFunction"));
      if (getValueTranslationFunction() != null)
        builder.addIfNotNull(DisplayData
            .item("ValueTranslation", getValueTranslationFunction().toString())
            .withLabel("Value translation SimpleFunction"));

    }
  }

  /**
   * Bounded source implementation for HadoopInputFormatIO.
   *
   * @param <K> Type of keys to be read.
   * @param <V> Type of values to be read.
   */
  public static class HadoopInputFormatBoundedSource<K, V> extends BoundedSource<KV<K, V>>
      implements Serializable {
    protected final SerializableConfiguration conf;
    protected final Coder<K> keyCoder;
    protected final Coder<V> valueCoder;
    protected final SimpleFunction<?, K> keyTranslationFunction;
    protected final SimpleFunction<?, V> valueTranslationFunction;
    protected final SerializableSplit inputSplit;
    private transient List<SerializableSplit> inputSplits;
    private long boundedSourceEstimatedSize = 0;
    private InputFormat<?, ?> inputFormatObj;

    public HadoopInputFormatBoundedSource(
        SerializableConfiguration conf,
        Coder<K> keyCoder,
        Coder<V> valueCoder) {
      this(conf, keyCoder, valueCoder, null, null, null, null);
    }

    public HadoopInputFormatBoundedSource(
        SerializableConfiguration conf,
        Coder<K> keyCoder,
        Coder<V> valueCoder,
        SimpleFunction<?, K> keyTranslationFunction,
        SimpleFunction<?, V> valueTranslationFunction,
        SerializableSplit inputSplit) {
      this(conf, keyCoder, valueCoder, keyTranslationFunction, valueTranslationFunction, null,
          inputSplit);
    }

    public HadoopInputFormatBoundedSource(
        SerializableConfiguration conf,
        Coder<K> keyCoder,
        Coder<V> valueCoder,
        SimpleFunction<?, K> keyTranslationFunction,
        SimpleFunction<?, V> valueTranslationFunction,
        InputFormat<?, ?> inputFormatObj,
        SerializableSplit inputSplit) {
      this.conf = conf;
      this.inputSplit = inputSplit;
      this.keyCoder = keyCoder;
      this.valueCoder = valueCoder;
      this.keyTranslationFunction = keyTranslationFunction;
      this.valueTranslationFunction = valueTranslationFunction;
      this.inputFormatObj = inputFormatObj;
    }

    public SerializableConfiguration getConfiguration() {
      return conf;
    }

    @Override
    public void validate() {
      checkNotNull(conf, HadoopInputFormatIOContants.MISSING_CONFIGURATION_SOURCE_ERROR_MSG);
      checkNotNull(keyCoder, HadoopInputFormatIOContants.MISSING_KEY_CODER_SOURCE_ERROR_MSG);
      checkNotNull(valueCoder, HadoopInputFormatIOContants.MISSING_VALUE_CODER_SOURCE_ERROR_MSG);
    }

    @Override
    public List<BoundedSource<KV<K, V>>> splitIntoBundles(long desiredBundleSizeBytes,
        PipelineOptions options) throws Exception {
      if (inputSplit == null) {
        if (inputSplits == null) {
          computeSplits();
        }
        LOG.info("Generated {} splits each of size {} ", inputSplits.size(),
            inputSplits.get(0).getSplit().getLength());
        return Lists.transform(inputSplits,
            new Function<SerializableSplit, BoundedSource<KV<K, V>>>() {
              @Override
              public BoundedSource<KV<K, V>> apply(SerializableSplit serializableInputSplit) {
                HadoopInputFormatBoundedSource<K, V> hifBoundedSource =
                    new HadoopInputFormatBoundedSource<K, V>(conf, keyCoder, valueCoder,
                        keyTranslationFunction, valueTranslationFunction, inputFormatObj,
                        serializableInputSplit);
                return hifBoundedSource;
              }
            });
      } else {
        LOG.info("Not splitting source {} because source is already split.", this);
        return ImmutableList.of((BoundedSource<KV<K, V>>) this);
      }
    }

    @Override
    public long getEstimatedSizeBytes(PipelineOptions po) throws Exception {
      if (inputSplit == null) {
        if (inputSplits == null) {
          computeSplits();
        }
        return boundedSourceEstimatedSize;
      }
      return inputSplit.getSplit().getLength();
    }


    /**
     * This is helper function to compute splits. This method will also calculate size of the data
     * being read. Note : This method is called exactly once, the splits are retrieved and cached
     * for further use by splitIntoBundles() and getEstimatesSizeBytes().
     * @throws InterruptedException
     */
    @VisibleForTesting
    void computeSplits() throws IOException, InterruptedException{
      inputFormatObj = createInputFormat();
      List<InputSplit> splits =
          inputFormatObj.getSplits(Job.getInstance(conf.getHadoopConfiguration()));
      if (splits == null) {
        throw new IOException(HadoopInputFormatIOContants.COMPUTESPLITS_NULL_GETSPLITS_ERROR_MSG);
      }
      if (splits.isEmpty()) {
        throw new IOException(HadoopInputFormatIOContants.COMPUTESPLITS_EMPTY_SPLITS_ERROR_MSG);
      }
      boundedSourceEstimatedSize = 0;
      inputSplits = new ArrayList<SerializableSplit>();
      for (InputSplit inputSplit : splits) {
        if (inputSplit == null) {
          throw new IOException(HadoopInputFormatIOContants.COMPUTESPLITS_NULL_SPLIT_ERROR_MSG);
        }
        boundedSourceEstimatedSize = boundedSourceEstimatedSize + inputSplit.getLength();
        inputSplits.add(new SerializableSplit(inputSplit));
      }
      validateKeyValueClasses();
    }

    /**
     * Returns instance of InputFormat set in the configuration.
     */
    private InputFormat<?, ?> createInputFormat() throws IOException, InterruptedException {
      InputFormat<?, ?> inputFormatObj;
      try {
        inputFormatObj = (InputFormat<?, ?>) conf.getHadoopConfiguration()
            .getClass(HadoopInputFormatIOContants.INPUTFORMAT_CLASSNAME, null).newInstance();
        /**
         * Sets configuration if provided InputFormat implements Configurable.
         */
        if (Configurable.class.isAssignableFrom(inputFormatObj.getClass())) {
          ((Configurable) inputFormatObj).setConf(conf.getHadoopConfiguration());
        }
      } catch (InstantiationException e) {
        throw new IOException("Unable to create InputFormat : ", e);
      } catch (IllegalAccessException e) {
        throw new IOException("Unable to create InputFormat : ", e);
      }
      return inputFormatObj;
    }

    /**
     * Throws exception is InputFormat key/value class set by the user is different than InputFormat's actual key/value class.
     */
    private void validateKeyValueClasses() throws IOException, InterruptedException {
      RecordReader<?, ?> reader = inputFormatObj.createRecordReader(inputSplits.get(0).getSplit(),
          new TaskAttemptContextImpl(conf.getHadoopConfiguration(), new TaskAttemptID()));
      if (reader == null) {
        throw new IOException(
            String.format(HadoopInputFormatIOContants.NULL_CREATE_RECORDREADER_ERROR_MSG,
                inputFormatObj.getClass()));
      }
      reader.initialize(inputSplits.get(0).getSplit(),
          new TaskAttemptContextImpl(conf.getHadoopConfiguration(), new TaskAttemptID()));
      reader.nextKeyValue();
      validateClass(reader.getCurrentKey().getClass(),"key.class",HadoopInputFormatIOContants.WRONG_INPUTFORMAT_KEY_CLASS_ERROR_MSG);
      validateClass(reader.getCurrentValue().getClass(),"value.class",HadoopInputFormatIOContants.WRONG_INPUTFORMAT_VALUE_CLASS_ERROR_MSG);
    }

   private void validateClass(Class<?> expectedClass, String property, String errorMessage){
      Class<?> actualClass = conf.getHadoopConfiguration().getClass(property, Object.class);
      if (actualClass != expectedClass) {
        throw new IllegalArgumentException(
            String.format(errorMessage,expectedClass.getName(),actualClass.getName()));
      }
    }

    /**
     * Returns InputFormat object.
     */
    @VisibleForTesting
    InputFormat<?, ?> getInputFormat(){
      return inputFormatObj;
    }

    @Override
    public Coder<KV<K, V>> getDefaultOutputCoder() {
      return KvCoder.of(keyCoder, valueCoder);
    }

    @Override
    public BoundedReader<KV<K, V>> createReader(PipelineOptions options) throws IOException {
      this.validate();
      if (inputSplit == null) {
          throw new IOException(HadoopInputFormatIOContants.CREATEREADER_UNSPLIT_SOURCE_ERROR_MSG);
      } else {
        return new HadoopInputFormatReader<Object>(this, keyTranslationFunction,
            valueTranslationFunction, inputFormatObj, inputSplit.getSplit());
      }
    }

    /** BoundedReader for HadoopInputFormatSource. */
    class HadoopInputFormatReader<T extends Object> extends BoundedSource.BoundedReader<KV<K, V>> {

      private final HadoopInputFormatBoundedSource<K, V> source;
      private final InputSplit split;
      @Nullable private final SimpleFunction<T, K> keyTranslationFunction;
      @Nullable private final SimpleFunction<T, V> valueTranslationFunction;
      private volatile boolean doneReading = false;
      private long recordsReturned = 0L;
      private InputFormat<?, ?> inputFormatObj;

      public HadoopInputFormatReader(HadoopInputFormatBoundedSource<K, V> source,
          @Nullable SimpleFunction keyTranslationFunction,
          @Nullable SimpleFunction valueTranslationFunction,
          InputFormat<?, ?> inputFormatObj,
          InputSplit split) {
        this.source = source;
        this.keyTranslationFunction = keyTranslationFunction;
        this.valueTranslationFunction = valueTranslationFunction;
        this.inputFormatObj = inputFormatObj;
        this.split = split;
      }

      @Override
      public HadoopInputFormatBoundedSource<K, V> getCurrentSource() {
        return source;
      }

      private RecordReader<T, T> currentReader;
      private KV<K, V> currentRecord;

      @Override
      public boolean start() throws IOException {
        try {
          TaskAttemptContextImpl attemptContext = new TaskAttemptContextImpl(
              source.getConfiguration().getHadoopConfiguration(), new TaskAttemptID());
          currentReader =
              (RecordReader<T, T>) inputFormatObj.createRecordReader(split, attemptContext);
            currentReader.initialize(split, attemptContext);
            if (currentReader.nextKeyValue()) {
              currentRecord = nextPair();
              return true;
            }
        } catch (InterruptedException e) {
          throw new IOException("Unable to read data : ", e);
        }
        currentReader = null;
        currentRecord = null;
        doneReading = true;
        return false;
      }

      @Override
      public boolean advance() throws IOException {
        try {
          synchronized (currentReader) {
            if (currentReader != null && currentReader.nextKeyValue()) {
              currentRecord = nextPair();
              return true;
            }
          }
          currentRecord = null;
          doneReading = true;
        } catch (InterruptedException e) {
          throw new IOException("Unable to read data : ", e);
        }
        return false;
      }

      /**
       * Returns the pair of key and value of the next record in sequence.
       */
      public KV<K, V> nextPair() throws IOException, InterruptedException {
        // Transform key if required.
        K key =
            transformKeyOrValue(currentReader.getCurrentKey(), keyTranslationFunction, keyCoder);
        // Transform value if required.
        V value = transformKeyOrValue(currentReader.getCurrentValue(), valueTranslationFunction,
            valueCoder);
        recordsReturned++;
        return KV.of(key, value);
      }

      /**
       * Returns the serialized output by cloning. Given input is transformed with the provided
       * SimpleFunction.
       */
      private <T1 extends Object> T1 transformKeyOrValue(T input,
          @Nullable SimpleFunction<T, T1> simpleFunction, Coder<T1> coder)
          throws IOException, InterruptedException {
        T1 output;
        if (null != simpleFunction) {
          output = simpleFunction.apply(input);
        } else {
          output = (T1) input;
        }
        return clone(output, coder);
      }

      /**
       * Cloning/Serialization is required to take care of Hadoop's mutable objects if returned by
       * RecordReader as Beam needs immutable objects.
       */
      private <T1 extends Object> T1 clone(T1 input, Coder<T1> coder)
          throws IOException, InterruptedException, CoderException, ClassCastException {
        // If the input object is not of known immutable type, clone the object.
        if (!isKnownImmutable(input)) {
          input = CoderUtils.clone(coder, input);
        }
        return input;
      }

      /**
       * Utility method to check if the passed object is of a known immutable type.
       */
      private boolean isKnownImmutable(Object o) {
        Set<Class<?>> immutableTypes = new HashSet<Class<?>>(
            Arrays.asList(String.class, Byte.class, Short.class, Integer.class, Long.class,
                Float.class, Double.class, Boolean.class, BigInteger.class, BigDecimal.class));
        return immutableTypes.contains(o.getClass());
      }

      @Override
      public KV<K, V> getCurrent() throws NoSuchElementException {
        if (currentRecord == null) {
          throw new NoSuchElementException();
        }
        return currentRecord;
      }

      @Override
      public void close() throws IOException {
        LOG.info("Closing reader after reading {} records.", recordsReturned);
        if (currentReader != null) {
          currentReader.close();
          currentReader = null;
        }
        currentRecord = null;
      }

      @Override
      public Double getFractionConsumed() {
        if (doneReading) {
          return 1.0;
        }
        synchronized (currentReader) {
          if (currentReader == null || recordsReturned == 0) {
            return 0.0;
          }
          return getProgress();
        }
      }

      /**
       * Returns RecordReader's Progress.
       */
      private Double getProgress() {
        try {
          return (double) currentReader.getProgress();
        } catch (IOException | InterruptedException e) {
          LOG.error(HadoopInputFormatIOContants.GETFRACTIONSCONSUMED_ERROR_MSG + e.getMessage(), e);
          return null;
        }
      }

      @Override
      public final long getSplitPointsRemaining() {
        if (doneReading) {
          return 0;
        }
        /**
         * This source does not currently support dynamic work rebalancing, so remaining parallelism
         * is always 1.
         */
        return 1;
      }
    }

    /**
     * A wrapper to allow Hadoop {@link org.apache.hadoop.mapreduce.InputSplit}s to be serialized
     * using Java's standard serialization mechanisms. Note that the InputSplit has to be Writable
     * (which mostly are).
     */
    public static class SerializableSplit implements Externalizable {

      private static final long serialVersionUID = 0L;

      private InputSplit split;

      public SerializableSplit() {}

      public SerializableSplit(InputSplit split) {
        checkArgument(split instanceof Writable, String
            .format(HadoopInputFormatIOContants.SERIALIZABLE_SPLIT_WRITABLE_ERROR_MSG, split));
        this.split = split;
      }

      public InputSplit getSplit() {
        return split;
      }

      @Override
      public void writeExternal(ObjectOutput out) throws IOException {
        out.writeUTF(split.getClass().getCanonicalName());
        ((Writable) split).write(out);
      }

      @Override
      public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        String className = in.readUTF();
        try {
          split = (InputSplit) Class.forName(className).newInstance();
          ((Writable) split).readFields(in);
        } catch (InstantiationException | IllegalAccessException e) {
          throw new IOException("Unable to create split : " + e);
        }
      }
    }

    public boolean producesSortedKeys(PipelineOptions arg0) throws Exception {
      return false;
    }

  }

  /**
   * A wrapper to allow Hadoop {@link org.apache.hadoop.conf.Configuration} to be serialized using
   * Java's standard serialization mechanisms. Note that the org.apache.hadoop.conf.Configuration
   * has to be Writable (which mostly are).
   */
  public static class SerializableConfiguration implements Externalizable {
    private static final long serialVersionUID = 0L;

    private Configuration conf;

    public SerializableConfiguration() {}

    public SerializableConfiguration(Configuration conf) {
      this.conf = conf;
    }

    public Configuration getHadoopConfiguration() {
      return conf;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      out.writeUTF(conf.getClass().getCanonicalName());
      ((Writable) conf).write(out);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
      String className = in.readUTF();
      try {
        conf = (Configuration) Class.forName(className).newInstance();
        conf.readFields(in);
      } catch (InstantiationException | IllegalAccessException e) {
        throw new IOException("Unable to create configuration : " + e);
      }
    }
  }
}
