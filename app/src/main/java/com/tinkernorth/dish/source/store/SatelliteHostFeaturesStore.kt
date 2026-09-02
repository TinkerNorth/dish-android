// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.core.model.HostFeatureSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SatelliteHostFeaturesStore
    @Inject
    constructor() : AbstractStateSource<Map<String, HostFeatureSet>>(emptyMap()) {
        fun featuresFor(connectionId: String): HostFeatureSet? = state.value[connectionId]

        // The catalog is the richer read and wins, with ONE exception: it has no `audio`
        // fields to win with (those live on the capabilities probe, since they are the only
        // runtime-switched host facts and the catalog is cached on version + locale). So a
        // catalog write carries the probed audio verdict forward rather than erasing it —
        // both directions, since a catalog that cannot speak for one cannot speak for either.
        fun setFeatures(
            connectionId: String,
            features: HostFeatureSet,
        ) {
            setState { current ->
                val prior = current[connectionId]
                val merged =
                    if (prior == null) {
                        features
                    } else {
                        features.copy(
                            controllerMic = prior.controllerMic,
                            controllerSpeaker = prior.controllerSpeaker,
                        )
                    }
                current + (connectionId to merged)
            }
        }

        // Pre-bind/pre-catalog publish: fills the host layer from a capabilities probe
        // only if a richer catalog read has not already populated it, so the catalog
        // (with touchpad modes) always wins when present.
        fun setIfAbsent(
            connectionId: String,
            features: HostFeatureSet,
        ) {
            setState { if (connectionId in it) it else it + (connectionId to features) }
        }

        // Session negotiation is the freshest protocol read (it beats a stale cached
        // catalog after a satellite update); merged in so the chips and the extended
        // mouse gate follow the live truth without waiting for a catalog refetch.
        fun noteProtocolVersion(
            connectionId: String,
            protocolVersion: Int,
        ) {
            if (protocolVersion <= 0) return
            setState { current ->
                val base = current[connectionId] ?: HostFeatureSet.SATELLITE_DEFAULT
                if (base.protocolVersion == protocolVersion) {
                    current
                } else {
                    current + (connectionId to base.copy(protocolVersion = protocolVersion))
                }
            }
        }

        // The capabilities probe is the only document carrying the audio verdict, and a
        // cached catalog may already have published this host, so setIfAbsent would drop it.
        // Merged like noteProtocolVersion instead, and an unchanged PAIR writes nothing, so
        // probing an old satellite never conjures an entry. Both directions ride one write
        // because one document reports both: two setState calls would publish a host with
        // the mic already moved and the speaker not, and every collector would see it.
        fun noteControllerAudio(
            connectionId: String,
            mic: Boolean,
            speaker: Boolean,
        ) {
            setState { current ->
                val base = current[connectionId] ?: HostFeatureSet.SATELLITE_DEFAULT
                if (base.controllerMic == mic && base.controllerSpeaker == speaker) {
                    current
                } else {
                    current + (connectionId to base.copy(controllerMic = mic, controllerSpeaker = speaker))
                }
            }
        }

        fun clearConnection(connectionId: String) {
            setState { if (connectionId in it) it - connectionId else it }
        }
    }
