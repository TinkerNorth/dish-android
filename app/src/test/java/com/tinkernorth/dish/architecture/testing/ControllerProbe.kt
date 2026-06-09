// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.testing

import com.tinkernorth.dish.architecture.abstracts.AbstractController
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ControllerProbe<S> internal constructor(
    private val controller: AbstractController<S>,
) {
    val owner = TestLifecycleOwner()

    fun start() {
        owner.start()
        controller.onStart(owner)
    }

    fun stop() {
        controller.onStop(owner)
        owner.stop()
    }
}

fun <S> AbstractController<S>.probe(): ControllerProbe<S> = ControllerProbe(this)

fun controllerTest(block: suspend TestScope.() -> Unit) = runTest(testBody = block)
