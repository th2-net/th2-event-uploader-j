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

import org.apache.commons.cli.CommandLine
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Option
import org.apache.commons.cli.Options
import org.apache.commons.lang3.StringUtils
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

enum class AppOption(val option: Option) {
    EVENTS_FILE_OPTION(
        Option.builder().option("e").longOpt("events-file").desc("JSONL file with events")
            .required().hasArg().build()
    ) {
        override fun String.checkAndTransform(): Path = Path(this).also { path ->
            require(path.isRegularFile()) {
                "${option.longOpt} '$path' isn't exist or file"
            }
            require("jsonl".equals(path.extension, ignoreCase = true)) {
                "${option.longOpt} '$path' has '${path.extension}' extension instead of 'jsonl'"
            }
        }
    },
    TH2_COMMON_CFG_DIR_OPTION(
        Option.builder().option("c").longOpt("th2-common-cfg-dir").desc("Directory with th2 common configs")
            .required().hasArg().build()
    ) {
        override fun String.checkAndTransform(): Path = Path(this).also { path ->
            require(path.isDirectory()) {
                "${option.longOpt} '$path' isn't exist or directory"
            }
        }
    },
    EVENT_IN_BATCH_OPTION(
        Option.builder().option("n").longOpt("event-in-batch").desc("Number of events in butch")
            .required().hasArg().build()
    ) {
        override fun String.checkAndTransform(): Int = requireNotNull(toIntOrNull()) {
            "${option.longOpt} '$this' isn't integer"
        }.also { num ->
            require(num > 0) {
                "${option.longOpt} '$this' is negative or 0"
            }
        }
    },
    EVENT_SCOPE_OPTION(
        Option.builder().option("s").longOpt("event-scope")
            .desc("""
                Scope is used for events id building. 
                `boxName` field from `box.json` config is used by default.
            """.trimIndent())
            .hasArg().build()
    ) {
        override fun String.checkAndTransform(): String = this.also {
            require(StringUtils.isNotBlank(this)) {
                "${option.longOpt} '$this' is blank"
            }
        }
    },
    EVENT_BOOK_OPTION(
        Option.builder().option("b").longOpt("event-book")
            .desc("""
                Book is used for for events id building. 
                `bookName` field from `box.json` config is used by default.
            """.trimIndent())
            .hasArg().build()
    ) {
        override fun String.checkAndTransform(): String = this.also {
            require(StringUtils.isNotBlank(this)) {
                "${option.longOpt} '$this' is blank"
            }
        }
    },
    HELP(
        Option.builder().option("h").longOpt("help").desc("Print commandline arguments").build()
    ) {
        override fun String.checkAndTransform(): Any {
            throw UnsupportedOperationException("${option.longOpt} hasn't got arguments")
        }
    };

    fun has(cmdLine: CommandLine): Boolean = cmdLine.hasOption(option)

    fun get(cmdLine: CommandLine): Any = cmdLine.getOptionValue(option).checkAndTransform()

    protected abstract fun String.checkAndTransform(): Any

    companion object {
        fun buildOptions(): Options = Options().apply {
            AppOption.values().asSequence()
                .map(AppOption::option)
                .forEach(this::addOption)
        }
        fun printHelp() {
            HelpFormatter().printHelp("./event-uploader [OPTIONS]", buildOptions())
        }
    }
}