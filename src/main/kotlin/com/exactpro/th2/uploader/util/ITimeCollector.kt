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

package com.exactpro.th2.uploader.util

import com.exactpro.th2.uploader.util.ITimeCollector.Companion.median
import java.text.DecimalFormat

interface ITimeCollector {
    fun put(time: Long): Boolean
    fun <T> measure(func: () -> T): T

    suspend fun <T> measures(func: suspend () -> T): T
    fun report(comment: String, divider: Long = 1)
    fun reset()

    companion object {
        const val NANOSECONDS_IN_SECOND: Double = 1_000_000_000.0
        private val _DECIMAL_FORMAT = object : ThreadLocal<DecimalFormat>() {
            override fun initialValue(): DecimalFormat {
                return DecimalFormat("0.00000000")
            }
        }
        val DECIMAL_FORMAT: DecimalFormat
            get() = _DECIMAL_FORMAT.get()

        @JvmStatic
        fun List<Long>.median(): Long = sorted().let {
            if (it.size % 2 == 0)
                (it[it.size / 2] + it[(it.size - 1) / 2]) / 2
            else
                it[it.size / 2]
        }
    }
}

class TimeCollectorDummy private constructor() : ITimeCollector {
    override fun put(time: Long): Boolean = false

    override fun <T> measure(func: () -> T): T = func()

    override suspend fun <T> measures(func: suspend () -> T): T = func()

    override fun report(comment: String, divider: Long) {}

    override fun reset() {}

    companion object {
        @JvmField
        val INSTANT = TimeCollectorDummy()
    }
}

class TimeCollector(private val report: (String) -> Unit = ::println) : ITimeCollector {
    private val times = mutableListOf<Long>()

    override fun put(time: Long) = times.add(time)

    override fun <T> measure(func: () -> T): T {
        val start = System.nanoTime()
        try {
            return func()
        } finally {
            times.add(System.nanoTime() - start)
        }
    }

    override suspend fun <T> measures(func: suspend () -> T): T {
        val start = System.nanoTime()
        try {
            return func()
        } finally {
            times.add(System.nanoTime() - start)
        }
    }

    override fun report(comment: String, divider: Long) {
        if (this.times.isEmpty()) {
            report.invoke("${comment}: no one measure")
        } else {
            val min: Double = this.times.min().toDouble()
            val avg: Double = this.times.average()
            val median: Double = this.times.median().toDouble()
            val max: Double = this.times.max().toDouble()

            report.invoke(
                "$comment - time (nano): min: ${ITimeCollector.DECIMAL_FORMAT.format(min / divider)}, avg: ${
                    ITimeCollector.DECIMAL_FORMAT.format(
                        avg / divider
                    )
                }, median: ${ITimeCollector.DECIMAL_FORMAT.format(median / divider)}, max: ${
                    ITimeCollector.DECIMAL_FORMAT.format(
                        max / divider
                    )
                }) ; rate (item/sec): min: ${ITimeCollector.DECIMAL_FORMAT.format(divider * ITimeCollector.NANOSECONDS_IN_SECOND / max)}, avg: ${
                    ITimeCollector.DECIMAL_FORMAT.format(
                        divider * ITimeCollector.NANOSECONDS_IN_SECOND / avg
                    )
                }, median: ${ITimeCollector.DECIMAL_FORMAT.format(divider * ITimeCollector.NANOSECONDS_IN_SECOND / median)}, max: ${
                    ITimeCollector.DECIMAL_FORMAT.format(
                        divider * ITimeCollector.NANOSECONDS_IN_SECOND / min
                    )
                }"
            )
        }
    }

    override fun reset() {
        times.clear()
    }
}