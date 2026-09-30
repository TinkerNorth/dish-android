// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import io.mockk.every

/**
 * A host answers a call the same whichever line it rides, so a mocked [gateway] answers a call made
 * on a line the caller can hang up with what it was told to answer the plain call with. Stubs made
 * after this one, on either form, say what the host answers.
 */
internal fun answerEveryLineAlike(gateway: MoonlightHttpGateway) {
    every { gateway.getHttpOn(any(), any(), any()) } answers { gateway.getHttp(secondArg(), thirdArg()) }
    every { gateway.getHttpsOn(any(), any(), any()) } answers { gateway.getHttps(secondArg(), thirdArg()) }
}
