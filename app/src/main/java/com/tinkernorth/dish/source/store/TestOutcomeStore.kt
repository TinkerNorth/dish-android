// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

// The inspector's feedback and audio tests that ask "Did it work?", by their bundle names.
enum class BenchTest(
    val wire: String,
) {
    RUMBLE_WEAK("rumble.weak"),
    RUMBLE_STRONG("rumble.strong"),
    RUMBLE_BOTH("rumble.both"),
    TRIGGER_PULSE("trigger.pulse"),
    LAMP_ON("lamp.on"),
    LAMP_PULSE("lamp.pulse"),
    TONE("tone.play"),
    MIC("mic.level"),
}

// One answer, { test, result, atMs } as the bundle carries it.
data class TestOutcome(
    val test: String,
    val result: String,
    val atMs: Long,
)

const val TEST_OUTCOMES_KEPT = 20
const val OUTCOME_WORKED = "worked"
const val OUTCOME_DID_NOT_WORK = "didNotWork"

fun resultOf(worked: Boolean): String = if (worked) OUTCOME_WORKED else OUTCOME_DID_NOT_WORK

// The answers to "Did it work?" per slot, the last twenty, in memory for the run.
@Singleton
class TestOutcomeStore
    @Inject
    constructor() : AbstractStateSource<Map<String, List<TestOutcome>>>(emptyMap()) {
        fun note(
            slotId: String,
            test: BenchTest,
            worked: Boolean,
            atMs: Long = System.currentTimeMillis(),
        ) {
            val outcome = TestOutcome(test.wire, resultOf(worked), atMs)
            setState { held -> held + (slotId to (held[slotId].orEmpty() + outcome).takeLast(TEST_OUTCOMES_KEPT)) }
        }

        fun outcomesFor(slotId: String): List<TestOutcome> = state.value[slotId].orEmpty()

        fun lastOutcomeFor(slotId: String): TestOutcome? = outcomesFor(slotId).lastOrNull()
    }
