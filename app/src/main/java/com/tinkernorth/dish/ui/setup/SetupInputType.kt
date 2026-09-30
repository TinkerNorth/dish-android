// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

// The wizard threads its accumulated choices forward as intent extras (the
// app's navigation convention) rather than a global session object, so each
// step is restartable from its own extras and "Start over" is a plain
// re-launch of the first screen.
enum class SetupInputType { USB, BLUETOOTH_CONTROLLER, ONSCREEN }
