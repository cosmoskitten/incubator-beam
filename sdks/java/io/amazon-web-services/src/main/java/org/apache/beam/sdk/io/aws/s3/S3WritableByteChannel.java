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
package org.apache.beam.sdk.io.aws.s3;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * A writable S3 object, as a {@link WritableByteChannel}.
 */
class S3WritableByteChannel implements WritableByteChannel {

  private final AmazonS3 amazonS3;
  private final S3ResourceId path;
  private final String uploadId;
  private final ByteBuffer uploadBuffer;
  private final List<PartETag> eTags;

  // AWS S3 parts are 1-indexed, not zero-indexed.
  private int partNumber = 1;
  private boolean open = true;

  S3WritableByteChannel(AmazonS3 amazonS3, S3ResourceId path, String contentType,
      String storageClass, int uploadBufferSizeBytes)
      throws IOException {
    this.amazonS3 = checkNotNull(amazonS3, "amazonS3");
    this.path = checkNotNull(path, "path");
    checkArgument(uploadBufferSizeBytes > 0, "uploadBufferSizeBytes");
    this.uploadBuffer = ByteBuffer.allocate(uploadBufferSizeBytes);
    eTags = new ArrayList<>();

    ObjectMetadata objectMetadata = new ObjectMetadata();
    objectMetadata.setContentType(contentType);
    InitiateMultipartUploadRequest request =
        new InitiateMultipartUploadRequest(path.getBucket(), path.getKey())
            .withStorageClass(storageClass)
            .withObjectMetadata(objectMetadata);
    InitiateMultipartUploadResult result;
    try {
      result = amazonS3.initiateMultipartUpload(request);
    } catch (AmazonClientException e) {
      throw new IOException(e);
    }
    uploadId = result.getUploadId();
  }

  @Override
  public int write(ByteBuffer sourceBuffer) throws IOException {
    if (!isOpen()) {
      throw new ClosedChannelException();
    }

    int totalBytesWritten = 0;
    while (sourceBuffer.hasRemaining()) {
      int bytesWritten = Math.min(sourceBuffer.remaining(), uploadBuffer.remaining());
      totalBytesWritten += bytesWritten;

      byte[] copyBuffer = new byte[bytesWritten];
      sourceBuffer.get(copyBuffer);
      uploadBuffer.put(copyBuffer);

      if (!uploadBuffer.hasRemaining() || sourceBuffer.hasRemaining()) {
        flush();
      }
    }

    return totalBytesWritten;
  }

  private void flush() throws IOException {
    uploadBuffer.flip();
    ByteArrayInputStream inputStream = new ByteArrayInputStream(uploadBuffer.array());

    UploadPartRequest request =
        new UploadPartRequest()
            .withBucketName(path.getBucket())
            .withKey(path.getKey())
            .withUploadId(uploadId)
            .withPartNumber(partNumber++)
            .withPartSize(uploadBuffer.remaining())
            .withInputStream(inputStream);
    UploadPartResult result;
    try {
      result = amazonS3.uploadPart(request);
    } catch (AmazonClientException e) {
      throw new IOException(e);
    }
    uploadBuffer.clear();
    eTags.add(result.getPartETag());
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() throws IOException {
    open = false;
    if (uploadBuffer.remaining() > 0) {
      flush();
    }
    CompleteMultipartUploadRequest request =
        new CompleteMultipartUploadRequest()
            .withBucketName(path.getBucket())
            .withKey(path.getKey())
            .withUploadId(uploadId)
            .withPartETags(eTags);
    try {
      amazonS3.completeMultipartUpload(request);
    } catch (AmazonClientException e) {
      throw new IOException(e);
    }
  }
}
