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
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.uploader.event.bean.EventBean
import com.exactpro.th2.uploader.event.util.createEvent
import com.exactpro.th2.uploader.util.TimeCollector
import com.exactpro.th2.uploader.util.TimeCollectorDummy
import com.google.common.util.concurrent.ThreadFactoryBuilder
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.io.path.bufferedReader

class CoroutineUploader(
    private val eventRouter: MessageRouter<EventBatch>,
    private val book: String,
    private val scope: String,
) : AutoCloseable {
    private val dispatcher = Executors
        .newFixedThreadPool(3, ThreadFactoryBuilder().setNameFormat("publisher-%d").build())
        .asCoroutineDispatcher()

    init {
        require(book.isNotBlank()) { "Book is blank" }
        require(scope.isNotBlank()) { "Scope is blank" }
    }

    suspend fun process(
        eventsPath: Path,
        eventInBatch: Int = 300,
        batchSize: Int = 256 * 1_024,
        readBufferSize: Int = 50,
        batchBufferSize: Int = 10,
    ): Long = coroutineScope {
        withContext(dispatcher) {
            val rootEventId = createEvent(eventRouter, book, scope, "Root event ${Instant.now()}")
            val beanChannel = Channel<EventBean>(readBufferSize)
            val batchChannel = Channel<EventBatch>(batchBufferSize)

            val readDiffered = async {
                read(eventsPath, beanChannel)
            }
            val prepareJob = launch {
                LOGGER.info { "Prepare coroutine started" }
                prepare(rootEventId, eventInBatch, batchSize, beanChannel, batchChannel)
            }
            val sendJob = launch {
                LOGGER.info { "Send coroutine started" }
                send(batchChannel)
            }

            prepareJob.join()
            sendJob.join()
            readDiffered.await()
        }
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
        batchSize: Int,
        beanChannel: Channel<EventBean>,
        batchChannel: Channel<EventBatch>
    ) {
        LOGGER.info { "Prepare method is starting" }

        val totalTimes = createTimeCollector(LOGGER.isDebugEnabled, LOGGER::debug)
        val eventTimes = createTimeCollector(LOGGER.isDebugEnabled, LOGGER::debug)
        val batchTimes = createTimeCollector(LOGGER.isDebugEnabled, LOGGER::debug)
        val batchChannelTimes = createTimeCollector(LOGGER.isTraceEnabled, LOGGER::trace)

        val eventIdSize = rootEventId.bookName.length
            .plus(rootEventId.scope.length)
            .plus(8 + 4) // timestamp in protobuf
            .plus(rootEventId.id.length)

        var size = eventIdSize // Event batch includes event id
        var events = 0
        var eventsTotal = 0L
        var batchesTotal = 0L

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
                    size += eventBean.size
                        .plus(eventIdSize * 2) // event includes own and parent id
                        .plus(8 + 4) // end timestamp in protobuf

                    if (events == eventInBatch || size > batchSize) {
                        val batch = batchBuilder.build()
                        System.nanoTime().also { now ->
                            batchTimes.put(now - batchTime)
                            batchTime = now
                        }
                        batchChannelTimes.measures {
                            batchChannel.send(batch)
                            batchesTotal += 1
                        }
                        batchBuilder = EventBatch.newBuilder().setParentEventId(rootEventId)
                        eventsTotal += events
                        size = eventIdSize
                        events = 0
                    }
                }

                if (events != 0) {
                    batchChannel.send(batchBuilder.build())
                    batchesTotal += 1
                    eventsTotal += events
                }
            }
        } catch (e: Exception) {
            batchChannel.close(e)
            LOGGER.error(e) { "Prepare method failure, sent [batches: $batchesTotal, events: $eventsTotal]" }
            throw e
        } finally {
            batchChannel.close()
            batchChannelTimes.report("Prepare: batch channel (event/sec)", eventInBatch.toLong())
            eventTimes.report("Prepare: event created (event/sec)")
            batchTimes.report("Prepare: batch created (event/sec)", eventInBatch.toLong())
            totalTimes.report("Prepare: total (event/sec)", eventsTotal)
            LOGGER.info { "Prepare method complete, sent [batches: $batchesTotal, events: $eventsTotal]" }
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