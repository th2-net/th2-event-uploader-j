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

package com.exactpro.th2.uploader.event.bean

import com.exactpro.th2.common.grpc.MessageID
import com.google.protobuf.util.Timestamps
import kotlinx.serialization.Serializable

@Serializable
class MessageIdBean(
    private val book: String,
    private val alias: String,
    private val group: String,
    private val timestamp: Long,
    private val sequence: Long,
) {
    fun toProto(): MessageID = MessageID.newBuilder().apply {
        this.bookName = this@MessageIdBean.book
        this.timestamp = Timestamps.fromNanos(this@MessageIdBean.timestamp)
        this.sequence = this@MessageIdBean.sequence
        this.connectionIdBuilder.setSessionGroup(this@MessageIdBean.group)
            .setSessionAlias(this@MessageIdBean.alias)
    }.build()
}