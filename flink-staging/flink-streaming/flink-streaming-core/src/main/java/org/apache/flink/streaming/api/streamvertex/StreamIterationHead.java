/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.streamvertex;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.flink.streaming.api.collector.StreamOutput;
import org.apache.flink.streaming.io.BlockingQueueBroker;
import org.apache.flink.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamIterationHead<OUT> extends StreamVertex<OUT, OUT> {

	private static final Logger LOG = LoggerFactory.getLogger(StreamIterationHead.class);

	private Collection<StreamOutput<?>> outputs;

	private static int numSources;
	private Integer iterationId;
	private BlockingQueue<RecordOrEvent> dataChannel;
	private long iterationWaitTime;
	private boolean shouldWait;

	public StreamIterationHead() {
		numSources = newVertex();
		instanceID = numSources;
		dataChannel = new ArrayBlockingQueue<RecordOrEvent>(1);
	}

	@Override
	public void setInputsOutputs() {
		outputHandler = new OutputHandler<OUT>(this);
		outputs = outputHandler.getOutputs();

		iterationId = configuration.getIterationId();
		iterationWaitTime = configuration.getIterationWaitTime();
		shouldWait = iterationWaitTime > 0;

		try {
			BlockingQueueBroker.instance().handIn(
					iterationId.toString() + "-" + getEnvironment().getIndexInSubtaskGroup(),
					dataChannel);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	@SuppressWarnings("unchecked")
	@Override
	public void invoke() throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("Iteration source {} invoked with instance id {}", getName(), getInstanceID());
		}

		try {
			RecordOrEvent nextRecordOrEvent;

			while (true) {
				if (shouldWait) {
					nextRecordOrEvent = dataChannel.poll(iterationWaitTime, TimeUnit.MILLISECONDS);
				} else {
					nextRecordOrEvent = dataChannel.take();
				}
				if (nextRecordOrEvent == null) {
					break;
				}
				if (nextRecordOrEvent.isRecord()) {
					for (StreamOutput<?> output : outputs) {
						((StreamOutput<OUT>) output).collect((OUT) nextRecordOrEvent.getRecord()
								.getObject());
					}
				} else if (nextRecordOrEvent.isEvent()) {
					for (StreamOutput<?> output : outputs) {
						((StreamOutput<OUT>) output).broadcastEvent(nextRecordOrEvent.getEvent());
					}
				} else {
					throw new RuntimeException("Error in record passing logic");
				}
			}

		} catch (Exception e) {
			if (LOG.isErrorEnabled()) {
				LOG.error("Iteration source failed due to: {}", StringUtils.stringifyException(e));
			}
			throw e;
		} finally {
			// Cleanup
			outputHandler.flushOutputs();
			clearBuffers();
		}

	}

	@Override
	protected void setInvokable() {
	}
}
