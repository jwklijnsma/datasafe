/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NOTE:
 * SimpleStorageResource.java from spring-cloud-aws
 * located by https://github.com/spring-cloud/spring-cloud-aws/blob/master/spring-cloud-aws-core/src/main/java/org/springframework/cloud/aws/core/io/s3/SimpleStorageResource.java
 * is used and was modified according Adorsys project needs.
 */

package de.adorsys.datasafe.storage.impl.s3;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.io.ByteArrayInputStream;
import java.util.concurrent.Callable;

@Slf4j
public class UploadChunkResultCallable implements Callable<CompletedPart> {

    private final S3Client s3;

    private final int contentLength;

    private final int partNumber;

    private final boolean last;

    private final String bucketName;

    private final String fileName;

    private final String uploadId;

    private byte[] content;

    UploadChunkResultCallable(ChunkUploadRequest request) {
        this.s3 = request.getS3();
        this.content = request.getContent();
        this.contentLength = request.getContentSize();
        this.partNumber = request.getChunkNumberCounter();
        this.last = request.isLastChunk();
        this.bucketName = request.getBucketName();
        this.fileName = request.getObjectName();
        this.uploadId = request.getUploadId();

        log.debug("Chunk upload request: {}", request);
    }

    @Override
    public CompletedPart call() {
        log.trace("Upload chunk result call with part: {}", partNumber);
        try {
            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(bucketName)
                    .key(fileName)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .contentLength((long) contentLength)
                    .build();

            // only the first contentLength bytes of the buffer are part of this chunk
            UploadPartResponse response = s3.uploadPart(uploadPartRequest,
                    RequestBody.fromInputStream(new ByteArrayInputStream(content, 0, contentLength), contentLength));
            // SDK v2 responses do not carry the part number; keep it with the ETag, because parts complete in
            // any order when they are uploaded in parallel
            return CompletedPart.builder()
                    .partNumber(partNumber)
                    .eTag(response.eTag())
                    .build();
        } finally {
            // Release the memory, as the callable may still live inside the
            // CompletionService which would cause
            // an exhaustive memory usage
            content = null;
        }
    }

}
