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
package org.apache.beam.runners.fnexecution.state;

import static com.google.common.base.Throwables.getStackTraceAsString;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.StateRequest;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.StateResponse;
import org.apache.beam.model.fnexecution.v1.BeamFnStateGrpc;
import org.apache.beam.runners.fnexecution.FnService;

/** An implementation of the Beam Fn State service. */
public class GrpcStateService extends BeamFnStateGrpc.BeamFnStateImplBase
    implements StateDelegator, FnService {
  private final ConcurrentHashMap<String, StateRequestHandler> requestHandlers;

  public GrpcStateService()
      throws Exception {
    this.requestHandlers = new ConcurrentHashMap<>();
  }

  @Override
  public void close() {
    // TODO: Track multiple clients and disconnect them cleanly instead of forcing termination
  }

  @Override
  public StreamObserver<StateRequest> state(StreamObserver<StateResponse> responseObserver) {
    return new Inbound(responseObserver);
  }

  @Override
  public StateDelegator.Registration registerForProcessBundleInstructionId(
      String processBundleInstructionId, StateRequestHandler handler) {
    requestHandlers.putIfAbsent(processBundleInstructionId, handler);
    return new Registration(processBundleInstructionId);
  }

  private class Registration implements StateDelegator.Registration {
    private final String processBundleInstructionId;

    private Registration(String processBundleInstructionId) {
      this.processBundleInstructionId = processBundleInstructionId;
    }

    @Override
    public void deregister() {
      requestHandlers.remove(processBundleInstructionId);
    }

    @Override
    public void abort() {
      deregister();
      // TODO: Abort in-flight state requests. Flag this processBundleInstructionId as a fail.
    }
  }

  /**
   * An inbound {@link StreamObserver} which delegates requests to registered handlers.
   *
   * <p>Is only threadsafe if the outbound observer is threadsafe.
   *
   * <p>TODO: Handle when the client indicates completion or an error on the inbound stream and
   * there are pending requests.
   */
  private class Inbound implements StreamObserver<StateRequest> {
    private final StreamObserver<StateResponse> outboundObserver;

    Inbound(StreamObserver<StateResponse> outboundObserver) {
      this.outboundObserver = outboundObserver;
    }

    @Override
    public void onNext(StateRequest request) {
      StateRequestHandler handler =
        requestHandlers.getOrDefault(request.getInstructionReference(), this::handlerNotFound);
      try {
        CompletionStage<StateResponse.Builder> result = handler.handle(request);
        result.whenCompleteAsync(
            (StateResponse.Builder responseBuilder, Throwable t) ->
                // note that this is threadsafe if and only if outboundObserver is threadsafe.
                outboundObserver.onNext(
                    t == null
                        ? responseBuilder.setId(request.getId()).build()
                        : createErrorResponse(request.getId(), t)));
      } catch (Exception e) {
        outboundObserver.onNext(createErrorResponse(request.getId(), e));
      }
    }

    @Override
    public void onError(Throwable t) {
      outboundObserver.onCompleted();
    }

    @Override
    public void onCompleted() {
      outboundObserver.onCompleted();
    }

    private CompletionStage<StateResponse.Builder> handlerNotFound(StateRequest request) {
      CompletableFuture<StateResponse.Builder> result = new CompletableFuture<>();
      result.complete(
          StateResponse.newBuilder()
              .setError(
                  String.format(
                      "Unknown process bundle instruction id '%s'",
                      request.getInstructionReference())));
      return result;
    }

    private StateResponse createErrorResponse(String id, Throwable t) {
      return StateResponse.newBuilder().setId(id).setError(getStackTraceAsString(t)).build();
    }
  }
}

