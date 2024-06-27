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

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.Event.UNKNOWN_EVENT_NAME
import com.exactpro.th2.common.event.Event.UNKNOWN_EVENT_TYPE
import com.exactpro.th2.common.grpc.EventID
import kotlinx.serialization.Serializable
import com.exactpro.th2.common.grpc.Event as ProtoEvent

@Serializable
class EventBean(
    private val name: String? = null,
    private val type: String? = null,
    private val body: String? = null,
    private val attachedMessageIds: List<MessageIdBean> = emptyList()
) {
    val size: Int = (name?.length ?: UNKNOWN_EVENT_NAME.length)
        .plus (type?.length ?: UNKNOWN_EVENT_TYPE.length)
        .plus(body?.length ?: 0)
        .plus(attachedMessageIds.sumOf(MessageIdBean::size))

    fun toProtoEvent(parentEventId: EventID): ProtoEvent = Event.start().apply {
        name(this@EventBean.name)
        type(this@EventBean.type)
        this@EventBean.body?.toByteArray()?.let(::rawBody)
        this@EventBean.attachedMessageIds.asSequence()
            .map(MessageIdBean::toProto)
            .forEach(this::messageID)
    }.toProto(parentEventId)
}