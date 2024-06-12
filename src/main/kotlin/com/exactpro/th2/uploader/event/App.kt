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
import com.exactpro.th2.uploader.event.AppOption.Companion.buildOptions
import com.exactpro.th2.uploader.event.AppOption.EVENTS_FILE_OPTION
import com.exactpro.th2.uploader.event.AppOption.EVENT_IN_BATCH_OPTION
import com.exactpro.th2.uploader.event.AppOption.TH2_COMMON_CFG_DIR_OPTION
import com.exactpro.th2.uploader.util.TimeCollector
import mu.KotlinLogging
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import java.nio.file.Path

private val LOGGER = KotlinLogging.logger { }

fun main(args: Array<String>) {
    LOGGER.info { "Publisher started with args: ${args.contentToString()}" }
    val options: Options = buildOptions()
    try {
        val cmdLine = DefaultParser().parse(options, args)

        val eventsPath = EVENTS_FILE_OPTION.get(cmdLine).cast<Path>()
        val commonFactoryPath = TH2_COMMON_CFG_DIR_OPTION.get(cmdLine).cast<Path>()
        val eventInBatch = EVENT_IN_BATCH_OPTION.get(cmdLine).cast<Int>()

        val globalTimes = TimeCollector(LOGGER::info)
        val eventsSent = globalTimes.measure {
            CommonFactory.createFromArguments("--configs", commonFactoryPath.toString()).use { factory ->
                CoroutineUploader(factory).use { publisher ->
                    publisher.process(eventsPath, eventInBatch)
                }
            }
        }
        globalTimes.report("Global time (event/sec): $eventsSent", eventsSent)
    } catch (e: ParseException) {
        LOGGER.error(e) { "Parse arguments failure" }
        HelpFormatter().printHelp("commandName [OPTIONS] <FILE>", options)
    } catch(e: Exception) {
        LOGGER.error(e) { "Fatal exception" }
    }
}

private inline fun <reified T> Any.cast(): T {
    require(this is T) {
        "Incorrect type of value, actual: ${this::class.java}, expected: ${T::class.java}"
    }
    return this
}