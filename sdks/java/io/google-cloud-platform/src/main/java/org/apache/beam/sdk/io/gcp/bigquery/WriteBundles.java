package org.apache.beam.sdk.io.gcp.bigquery;

import com.google.api.services.bigquery.model.TableRow;
import java.util.UUID;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.TableRowWriter;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.values.KV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by relax on 3/17/17.
 */
class WriteBundles extends DoFn<TableRow, KV<String, Long>> {
  private static final Logger LOG = LoggerFactory.getLogger(WriteBundles.class);

  private transient TableRowWriter writer = null;
  private final String tempFilePrefix;

  WriteBundles(String tempFilePrefix) {
    this.tempFilePrefix = tempFilePrefix;
  }

  @ProcessElement
  public void processElement(ProcessContext c) throws Exception {
    if (writer == null) {
      writer = new TableRowWriter(tempFilePrefix);
      writer.open(UUID.randomUUID().toString());
      LOG.debug("Done opening writer {}", writer);
    }
    try {
      writer.write(c.element());
    } catch (Exception e) {
      // Discard write result and close the write.
      try {
        writer.close();
        // The writer does not need to be reset, as this DoFn cannot be reused.
      } catch (Exception closeException) {
        // Do not mask the exception that caused the write to fail.
        e.addSuppressed(closeException);
      }
      throw e;
    }
  }

  @FinishBundle
  public void finishBundle(Context c) throws Exception {
    if (writer != null) {
      c.output(writer.close());
      writer = null;
    }
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);

    builder
        .addIfNotNull(DisplayData.item("tempFilePrefix", tempFilePrefix)
            .withLabel("Temporary File Prefix"));
  }
}
