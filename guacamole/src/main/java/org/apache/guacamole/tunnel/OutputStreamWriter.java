/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.tunnel;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles writing of data  blobs to the output stream in a separate thread.
 * Tracks and returns the results of the writing operation for each blob.
 */
public class OutputStreamWriter {

    /**
     * Listener interface for receiveing results of blob writing operations
     * or anything which happens inside the writer thread.
     */
    public interface ExecutionListener {
        void onBlobWritten(String streamIndex);
        void onWriteFailed(String streamIndex);
    }

    /**
     * Thread message base.
     */
    private interface Message {}

    /**
     * Thread message to send a blob.
     */
    private final class MessageBlob implements Message {
        public final byte[] blob;
        public MessageBlob(byte[] blob) {
            this.blob = blob;
        }
    }

    /**
     * Message to trigger the thread activity.
     * Used together with isRunning == false.
     */
    private final class MessageStop implements Message {}

    /**
     * Logger for this class.
     */
    private static final Logger logger =
            LoggerFactory.getLogger(OutputStreamInterceptingFilter.class);

    /**
     * Index of the output stream.
     */
    private final String streamIndex;

    /**
     * The stream to write blobs.
     */
    private final InterceptedStream<OutputStream> stream;

    /**
     * Reference to the object where to send results of blob
     * writing operations and other events from the writer thread.
     */
    private final ExecutionListener executionListener;

    /**
     * The queue for thread messages which includes blobs to write to the stream.
     */
    private final BlockingQueue<Message> queue = new LinkedBlockingQueue<>();

    /**
     * Whether the writer is alive and the thread is writing.
     */
    private volatile boolean isRunning = true;

    /**
     * Creates a new OutputStreamWriter which will write blobs
     * into the output stream and return results of the writing operations
     * to the execution listener.
     * 
     * @param stream
     *     The stream to write blobs.
     * 
     * @param executionListener
     *     Exception listener object to send results of the writing operations. 
     */
    public OutputStreamWriter(InterceptedStream<OutputStream> stream,
            ExecutionListener executionListener) {
        this.streamIndex = stream.getIndex();
        this.stream = stream;
        this.executionListener = executionListener;
    }

    /**
     * Signals the streaming thread to stop.
     */
    public void stop() {
        isRunning = false;
        queue.offer(new MessageStop());
    }

    /**
     * Return the stream where the blobs are written.
     * 
     * @return
     *     The stream related to this writer.
     */
    public InterceptedStream<OutputStream> getStream() {
        return stream;
    }

    /**
     * Puts a blob into the internal queue to be write into the stream.
     * 
     * @param blob
     *     Blob which has to be written into the stream.
     */
    public void handleBlob(byte[] blob) {
        queue.offer(new MessageBlob(blob));
    }

    public void run() {
        try {
            while (isRunning) {

                Message message = queue.take();

                // This includes the case of StreamWriterStop.
                if (!isRunning) {
                    break;
                }

                // Write the blob
                if (message instanceof MessageBlob) {
                    MessageBlob streamWriterBlob = (MessageBlob) message;

                    // Attempt to write data to stream
                    stream.getStream().write(streamWriterBlob.blob);

                    // Otherwise, acknowledge the blob on the client's behalf
                    executionListener.onBlobWritten(stream.getIndex());
                }

            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        catch (IOException e) {
            executionListener.onWriteFailed(streamIndex);
            logger.debug("Write failed for intercepted stream.", e);
        }
    }
}

