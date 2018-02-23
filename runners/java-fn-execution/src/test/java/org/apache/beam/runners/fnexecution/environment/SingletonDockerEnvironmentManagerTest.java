package org.apache.beam.runners.fnexecution.environment;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.beam.model.pipeline.v1.Endpoints.ApiServiceDescriptor;
import org.apache.beam.model.pipeline.v1.RunnerApi.Environment;
import org.apache.beam.runners.fnexecution.GrpcFnServer;
import org.apache.beam.runners.fnexecution.artifact.ArtifactRetrievalService;
import org.apache.beam.runners.fnexecution.control.SdkHarnessClient;
import org.apache.beam.runners.fnexecution.control.SdkHarnessClientControlService;
import org.apache.beam.runners.fnexecution.logging.GrpcLoggingService;
import org.apache.beam.runners.fnexecution.provisioning.StaticGrpcProvisionService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class SingletonDockerEnvironmentManagerTest {

  private static final ApiServiceDescriptor SERVICE_DESCRIPTOR = ApiServiceDescriptor.newBuilder()
      .setUrl("service-url")
      .build();
  private static final String IMAGE_NAME = "my-image";
  private static final Environment ENVIRONMENT = Environment.newBuilder().setUrl(IMAGE_NAME).build();
  private static final String CONTAINER_ID = "e4485f0f2b813b63470feacba5fe9cb89699878c095df4124abd320fd5401385";

  @Mock private DockerWrapper docker;

  @Mock private GrpcFnServer<SdkHarnessClientControlService> controlServiceServer;
  @Mock private GrpcFnServer<GrpcLoggingService> loggingServiceServer;
  @Mock private GrpcFnServer<ArtifactRetrievalService> retrievalServiceServer;
  @Mock private GrpcFnServer<StaticGrpcProvisionService> provisioningServiceServer;

  @Mock private SdkHarnessClientControlService clientControlService;
  @Mock private SdkHarnessClient sdkHarnessClient;

  @Before
  public void initMocks() {
    MockitoAnnotations.initMocks(this);

    when(controlServiceServer.getApiServiceDescriptor()).thenReturn(SERVICE_DESCRIPTOR);
    when(loggingServiceServer.getApiServiceDescriptor()).thenReturn(SERVICE_DESCRIPTOR);
    when(retrievalServiceServer.getApiServiceDescriptor()).thenReturn(SERVICE_DESCRIPTOR);
    when(provisioningServiceServer.getApiServiceDescriptor()).thenReturn(SERVICE_DESCRIPTOR);

    when(controlServiceServer.getService()).thenReturn(clientControlService);
    when(clientControlService.getClient()).thenReturn(sdkHarnessClient);
  }

  @Test
  public void createsCorrectEnvironment() throws Exception {
    when(docker.runImage(Mockito.eq(IMAGE_NAME), Mockito.any())).thenReturn(CONTAINER_ID);
    SingletonDockerEnvironmentManager manager = getManager();

    RemoteEnvironment handle = manager.getEnvironment(ENVIRONMENT);
    assertThat(handle.getClient()).isSameAs(sdkHarnessClient);
    assertThat(handle.getEnvironment()).isEqualTo(ENVIRONMENT);
  }

  @Test
  public void destroysCorrectContainer() throws Exception {
    when(docker.runImage(Mockito.eq(IMAGE_NAME), Mockito.any())).thenReturn(CONTAINER_ID);
    SingletonDockerEnvironmentManager manager = getManager();

    RemoteEnvironment handle = manager.getEnvironment(ENVIRONMENT);
    handle.close();
    verify(docker).killContainer(CONTAINER_ID);
  }

  @Test
  public void onlyAcceptsSingleEnvironment() throws Exception {
    when(docker.runImage(Mockito.eq(IMAGE_NAME), Mockito.any())).thenReturn(CONTAINER_ID);
    SingletonDockerEnvironmentManager manager = getManager();

    manager.getEnvironment(ENVIRONMENT);
    // TODO: Use JUnit assertThrows when available.
    try {
      manager.getEnvironment(ENVIRONMENT.toBuilder().setUrl("other-environment").build());
      fail("Expected exception");
    } catch (Exception expected) {
      assertThat(expected).isInstanceOf(IllegalArgumentException.class);
    }
  }

  private SingletonDockerEnvironmentManager getManager() {
    return SingletonDockerEnvironmentManager.forServices(
        docker,
        controlServiceServer,
        loggingServiceServer,
        retrievalServiceServer,
        provisioningServiceServer);
  }

}
