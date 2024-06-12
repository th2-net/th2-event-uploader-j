/*
 * Copyright 2024 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.uploader.event

import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.google.common.util.concurrent.ThreadFactoryBuilder
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import com.exactpro.th2.uploader.event.bean.EventBean
import com.exactpro.th2.uploader.event.util.createEvent
import com.exactpro.th2.uploader.event.util.toProtoEvent
import com.exactpro.th2.uploader.util.TimeCollector
import com.exactpro.th2.uploader.util.TimeCollectorDummy
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.io.path.bufferedReader

class CoroutineUploader(
    private val factory: CommonFactory,
) : AutoCloseable {
    private val eventRouter = factory.eventBatchRouter
    private val dispatcher = Executors
        .newFixedThreadPool(3, ThreadFactoryBuilder().setNameFormat("publisher-%d").build())
        .asCoroutineDispatcher()

    fun process(
        eventsPath: Path,
        eventInBatch: Int = 125,
        readBufferSize: Int = 50,
        batchBufferSize: Int = 10,
    ): Long = runBlocking(dispatcher) {
        val rootEventId = createEvent(factory, "Root event ${Instant.now()}")
        val beanChannel = Channel<EventBean>(readBufferSize)
        val batchChannel = Channel<EventBatch>(batchBufferSize)

        val readDiffered = async {
            read(eventsPath, beanChannel)
        }
        val prepareJob = launch {
            LOGGER.info { "Prepare coroutine started" }
            prepare(rootEventId, eventInBatch, beanChannel, batchChannel)
        }
        val sendJob = launch {
            LOGGER.info { "Send coroutine started" }
            send(batchChannel)
        }

        prepareJob.join()
        sendJob.join()
        readDiffered.await()
    }

    private suspend fun read(path: Path, channel: Channel<EventBean>): Long {
        LOGGER.info { "Read method is starting to read the '$path' file " }

        val totalTimes = createTimeCollector(LOGGER.isDebugEnabled, LOGGER::debug)
        val readTimes = createTimeCollector(LOGGER.isDebugEnabled, LOGGER::debug)
        val beanChannelTimes = createTimeCollector(LOGGER.isTraceEnabled, LOGGER::trace)
        var counter = 0L

        try {
            totalTimes.measures {
                path.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        val eventBean = readTimes.measure { Json.decodeFromString(EventBean.serializer(), line) }
                        beanChannelTimes.measures { channel.send(eventBean) }
                        counter += 1
                    }
                }
            }
            return counter
        } catch (e: Exception) {
            channel.close(e)
            LOGGER.error(e) { "Read method failure when reads the '$path' file, read [lines: $counter]" }
            throw e
        } finally {
            channel.close()
            beanChannelTimes.report("Read: bean channel (bean/sec)")
            readTimes.report("Read: decode (bean/sec)")
            totalTimes.report("Read: total (bean/sec)", counter)
            LOGGER.info { "Read method complete, read [lines: $counter]" }
        }
    }

    private suspend fun prepare(
        rootEventId: EventID,
        eventInBatch: Int,
        beanChannel: Channel<EventBean>,
        batchChannel: Channel<EventBatch>
    ) {
        LOGGER.info { "Prepare method is starting" }

        val totalTimes = createTimeCollector(LOGGER.isDebugEnabled, LOGGER::debug)
        val eventTimes = createTimeCollector(LOGGER.isDebugEnabled, LOGGER::debug)
        val batchTimes = createTimeCollector(LOGGER.isDebugEnabled, LOGGER::debug)
        val batchChannelTimes = createTimeCollector(LOGGER.isTraceEnabled, LOGGER::trace)

        var events = 0L
        var batches = 0L

        try {
            totalTimes.measures {
                var batchBuilder = EventBatch.newBuilder().setParentEventId(rootEventId)
                var batchTime = System.nanoTime()
                for (eventBean in beanChannel) {

                    val event = eventTimes.measure {
                        eventBean.toProtoEvent(rootEventId)
                    }
                    batchBuilder.addEvents(event)
                    events += 1

                    if (events % eventInBatch == 0L) {
                        val batch = batchBuilder.build()
                        val now = System.nanoTime()
                        batchTimes.put(now - batchTime)
                        batchTime = now
                        batchChannelTimes.measures {
                            batchChannel.send(batch)
                            batches += 1
                        }
                        batchBuilder = EventBatch.newBuilder().setParentEventId(rootEventId)
                    }
                }

                if (events % eventInBatch != 0L) {
                    batchChannel.send(batchBuilder.build())
                    batches += 1
                }
            }
        } catch (e: Exception) {
            batchChannel.close(e)
            LOGGER.error(e) { "Prepare method failure, sent [batches: $batches, events: $events]" }
            throw e
        } finally {
            batchChannel.close()
            batchChannelTimes.report("Prepare: batch channel (event/sec)", eventInBatch.toLong())
            eventTimes.report("Prepare: event created (event/sec)")
            batchTimes.report("Prepare: batch created (event/sec)", eventInBatch.toLong())
            totalTimes.report("Prepare: total (event/sec)", events)
            LOGGER.info { "Prepare method complete, sent [batches: $batches, events: $events]" }
        }
    }

    private suspend fun send(channel: Channel<EventBatch>) {
        LOGGER.info { "Send method is starting" }

        val totalTimes = createTimeCollector(LOGGER.isDebugEnabled, LOGGER::debug)
        val sendTimes = createTimeCollector(LOGGER.isDebugEnabled, LOGGER::debug)

        var events = 0L
        var batches = 0L

        try {
            totalTimes.measures {
                for (eventBatch in channel) {
                    sendTimes.measure {
                        eventRouter.send(eventBatch)
                    }
                    batches += 1
                    events += eventBatch.eventsCount
                }
            }
        } catch (e: Exception) {
            LOGGER.error(e) { "Send failure" }
            throw e
        } finally {
            sendTimes.report("Send: event (event/sec)")
            totalTimes.report("Send: total (event/sec)", events)
            LOGGER.info { "Send method completed, sent [batches: $batches, events: $events]" }
        }
    }

    private fun createTimeCollector(enabled: Boolean, report: (String) -> Unit) = if (enabled) {
        TimeCollector(report)
    } else {
        TimeCollectorDummy
    }

    override fun close() {
        dispatcher.close()
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }
    }
}