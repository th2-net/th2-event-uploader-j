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

import com.exactpro.th2.common.schema.factory.CommonFactory
import mu.KotlinLogging
import com.exactpro.th2.uploader.util.TimeCollector
import kotlin.io.path.Path

private val LOGGER = KotlinLogging.logger { }

fun main(args: Array<String>) {
    try {
        LOGGER.info { "Publisher started with args: ${args.contentToString()}" }
        // TODO: implement common-cli
        val eventsPath = Path("data", "big_data_jackson.jsonl")
        val commonFactoryPath = "cfg"
        val eventInBatch = 125

        repeat(10) {
            val globalTimes = TimeCollector(LOGGER::info)
            val eventsSent = globalTimes.measure {
                CommonFactory.createFromArguments("--configs", commonFactoryPath).use { factory ->
                    CoroutineUploader(factory).use { publisher ->
                        publisher.process(eventsPath, eventInBatch)
                    }
                }
            }
            globalTimes.report("Global (event/sec): $eventsSent", eventsSent)
        }
    } catch(e: Exception) {
        LOGGER.error(e) { "Fatal exception" }
    }
}