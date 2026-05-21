// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture

/**
 * Three patterns and one exception. Every non-UI, non-pure-utility class in this
 * app fits into one of these buckets — and the folder layout makes the choice
 * visible from the path.
 *
 * **Folder structure inside `architecture/`:**
 *
 *   - `abstracts/` — abstract classes that subclasses extend.
 *       - [com.tinkernorth.dish.architecture.abstracts.AbstractStateSource]
 *       - [com.tinkernorth.dish.architecture.abstracts.AbstractComposer]
 *   - `interfaces/` — interfaces that implementations realize.
 *       - [com.tinkernorth.dish.architecture.interfaces.Repository]
 *       - [com.tinkernorth.dish.architecture.interfaces.KeyedRepository]
 *
 * **Folder structure for concrete classes (one level up from `architecture/`):**
 *
 *   - `source/` — `AbstractStateSource` subclasses (plus the rare event-emitter
 *     class that owns its own SharedFlows directly). Sub-folders by domain:
 *     `connection/`, `sensor/`, `system/`, `notification/`, `lowpower/`,
 *     `bluetooth/`, `store/`.
 *   - `composer/` — `AbstractComposer` subclasses + Coordinator-shaped
 *     orchestrators (e.g. `ConnectionHub`) that own composers and stores.
 *   - `repository/` — `Repository` and `KeyedRepository` implementations
 *     (durable storage).
 *   - `hotpath/` — input dispatch that intentionally skips the pattern stack
 *     for latency reasons. Each file justifies the exemption at the top.
 *   - `core/` — pure data, JNI wrappers, network gateways. No pattern; lives
 *     outside because it has no flow shape to fit.
 *
 * **Decision rule when writing a new class:**
 *
 *   1. Does it own state-over-time (a sensor, socket, receiver, in-memory
 *      cache)? → extend
 *      [com.tinkernorth.dish.architecture.abstracts.AbstractStateSource].
 *   2. Is it a pure function of other flows? → extend
 *      [com.tinkernorth.dish.architecture.abstracts.AbstractComposer].
 *   3. Does it read/write a durable store? → implement
 *      [com.tinkernorth.dish.architecture.interfaces.Repository].
 *   4. Does it emit one-shot events but hold no observable state (e.g. a
 *      notification queue)? → plain `@Singleton` that owns its own
 *      `SharedFlow`(s) directly. Not a pattern instance. See
 *      `DishNotifications` and `SatelliteConnectionManager`'s `events`
 *      flow for examples.
 *   5. Is it on the per-frame input path? → put it under `hotpath/` and
 *      justify in a top-of-file comment.
 *
 * If a class wants to be two of these, split it. A source that also persists
 * state should compose a `Repository` instance; a composer that also needs a
 * side effect should expose its state and let a separate source subscribe.
 *
 * **Why no event channel on `AbstractStateSource`:** earlier iterations of the
 * base had an `events: SharedFlow<E>` alongside `state`. Audit showed only one
 * class (`DishNotifications`) actually used it, and that class needed two
 * SharedFlows so the inheritance only covered half its surface. The base is
 * now state-only; event-emitting classes own their own SharedFlows directly.
 */
internal object Patterns
