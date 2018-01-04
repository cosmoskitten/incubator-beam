package org.apache.beam.runners.core.metrics;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import org.apache.beam.sdk.metrics.DistributionResult;
import org.apache.beam.sdk.metrics.GaugeResult;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.MetricResult;
import org.joda.time.Instant;

/** Serialize metrics into json representation to be pushed to a backend. */
public class JsonMetricsSerializer implements MetricsSerializer<String> {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String serializeMetrics(MetricQueryResults metricResults) throws Exception {
    SimpleModule module = new SimpleModule();
    module.addSerializer(MetricQueryResults.class, new MetricQueryResultsSerializer());
    objectMapper.registerModule(module);
    return objectMapper.writeValueAsString(metricResults);
  }

  private class MetricQueryResultsSerializer extends StdSerializer<MetricQueryResults> {

    public MetricQueryResultsSerializer() {
      this(null);
    }

    public MetricQueryResultsSerializer(Class<MetricQueryResults> t) {
      super(t);
    }

    @Override
    public void serialize(
        MetricQueryResults metricQueryResults,
        JsonGenerator jsonGenerator,
        SerializerProvider serializerProvider)
        throws IOException {
      jsonGenerator.writeStartObject();

      jsonGenerator.writeArrayFieldStart("counters");
      for (MetricResult<Long> result : metricQueryResults.counters()){
        jsonGenerator.writeStartObject();
        String name = result.name().namespace() + "/"  + result.name().name();
        jsonGenerator.writeStringField("name", name);
        String step = result.step();
        jsonGenerator.writeStringField("step", step);
        Long attempted = result.attempted();
        jsonGenerator.writeNumberField("attempted", attempted);
        jsonGenerator.writeEndObject();
      }
      jsonGenerator.writeEndArray();

      jsonGenerator.writeArrayFieldStart("distributions");
      for (MetricResult<DistributionResult> result : metricQueryResults.distributions()){
        jsonGenerator.writeStartObject();
        String name = result.name().namespace() + "/"  + result.name().name();
        jsonGenerator.writeStringField("name", name);
        String step = result.step();
        jsonGenerator.writeStringField("step", step);
        DistributionResult attempted = result.attempted();
        jsonGenerator.writeFieldName("attempted");
        jsonGenerator.writeStartObject();
        jsonGenerator.writeNumberField("min", attempted.min());
        jsonGenerator.writeNumberField("max", attempted.max());
        jsonGenerator.writeNumberField("sum", attempted.sum());
        jsonGenerator.writeNumberField("count", attempted.count());
        jsonGenerator.writeNumberField("mean", attempted.mean());
        jsonGenerator.writeEndObject();

        jsonGenerator.writeEndObject();
      }
      jsonGenerator.writeEndArray();

      jsonGenerator.writeArrayFieldStart("gauges");
      for (MetricResult<GaugeResult> result : metricQueryResults.gauges()){
        jsonGenerator.writeStartObject();
        String name = result.name().namespace() + "/"  + result.name().name();
        jsonGenerator.writeStringField("name", name);
        String step = result.step();
        jsonGenerator.writeStringField("step", step);
        GaugeResult attempted = result.attempted();
        jsonGenerator.writeFieldName("attempted");
        jsonGenerator.writeStartObject();
        jsonGenerator.writeNumberField("value", attempted.value());
        jsonGenerator.writeStringField("timestamp", attempted.timestamp().toString());
        jsonGenerator.writeEndObject();
        jsonGenerator.writeEndObject();
      }
      jsonGenerator.writeEndArray();

      jsonGenerator.writeEndObject();
    }
  }
}
