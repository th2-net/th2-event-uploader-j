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

package com.exactpro.th2.uploader.event.util

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.IBodyData
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.schema.message.MessageRouter

fun createEvent(
    eventRouter: MessageRouter<EventBatch>,
    book: String,
    scope: String,
    name: String,
    status: Event.Status = Event.Status.PASSED,
    body: IBodyData? = null,
    parentEventId: EventID? = null
): EventID = Event.start()
    .name(name)
    .status(status)
    .run {
        body?.let(this::bodyData)

        val batch = if (parentEventId == null) {
            toBatchProto(book, scope)
        } else {
            toBatchProto(parentEventId)
        }

        eventRouter.send(batch)
        batch.getEvents(0).id
    }