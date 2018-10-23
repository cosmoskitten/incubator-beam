package org.apache.beam.sdk.io.hadoop.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests functionality of {@link HDFSSynchronization} class. */
public class HDFSSynchronizationTest {

  public static final String DEFAULT_JOB_ID = String.valueOf(1);
  @Rule public TemporaryFolder tmpFolder = new TemporaryFolder();

  private HDFSSynchronization tested;
  private Configuration configuration;

  @Before
  public void setup() {
    this.tested = new HDFSSynchronization(tmpFolder.getRoot().getAbsolutePath());
    this.configuration = new Configuration();
    configuration.set(HadoopFormatIO.JOB_ID, DEFAULT_JOB_ID);
  }

  /** Tests that job lock will be acquired only once until it is again released. */
  @Test
  public void tryAcquireJobLockTest() {
    boolean firstAttempt = tested.tryAcquireJobLock(configuration);
    boolean secondAttempt = tested.tryAcquireJobLock(configuration);
    boolean thirdAttempt = tested.tryAcquireJobLock(configuration);

    assertTrue(isFileExists(getJobLockPath()));

    tested.releaseJobIdLock(configuration);

    boolean fourthAttempt = tested.tryAcquireJobLock(configuration);
    boolean fifthAttempt = tested.tryAcquireJobLock(configuration);

    assertTrue(firstAttempt);
    assertFalse(secondAttempt);
    assertFalse(thirdAttempt);

    assertTrue(fourthAttempt);
    assertFalse(fifthAttempt);
  }

  /** Missing job id in configuration will throw exception. */
  @Test(expected = NullPointerException.class)
  public void testMissingJobId() {

    Configuration conf = new Configuration();

    tested.tryAcquireJobLock(conf);
  }

  /** Multiple attempts to release job will not throw exception */
  @Test
  public void testMultipleTaskDeletion() {
    tested.tryAcquireJobLock(configuration);

    assertTrue(isFileExists(getJobLockPath()));

    tested.releaseJobIdLock(configuration);

    assertFalse(isFileExists(getJobLockPath()));

    // any exception will not be thrown
    tested.releaseJobIdLock(configuration);
  }

  @Test
  public void testTaskIdLockAcquire() {

    int tasksCount = 100;
    for (int i = 0; i < tasksCount; i++) {
      TaskID taskID = tested.acquireTaskIdLock(configuration);
      assertTrue(isFileExists(getTaskIdPath(taskID)));
    }

    String jobFolderName = getFileInJobFolder("");
    File jobFolder = new File(jobFolderName);
    assertTrue(jobFolder.isDirectory());
    // we have to multiply by 2 because crc files exists
    assertEquals(tasksCount * 2, jobFolder.list().length);
  }

  @Test
  public void testTaskAttemptIdAcquire() {
    int tasksCount = 100;
    int taskId = 25;

    for (int i = 0; i < tasksCount; i++) {
      TaskAttemptID taskAttemptID = tested.acquireTaskAttemptIdLock(configuration, taskId);
      assertTrue(isFileExists(getTaskAttemptIdPath(taskId, taskAttemptID.getId())));
    }
  }

  private String getTaskAttemptIdPath(int taskId, int taskAttemptId) {
    return getFileInJobFolder(taskId + "_" + taskAttemptId);
  }

  private String getTaskIdPath(TaskID taskID) {
    return getFileInJobFolder(String.valueOf(taskID.getId()));
  }

  private String getJobLockPath() {
    return getFileInJobFolder("_job");
  }

  private String getFileInJobFolder(String filename) {
    return tmpFolder.getRoot().getAbsolutePath()
        + File.separator
        + DEFAULT_JOB_ID
        + File.separator
        + filename;
  }

  private boolean isFileExists(String path) {
    File file = new File(path);

    return file.exists() && !file.isDirectory();
  }
}
