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

        // The catalog is the richer read and wins, except for the audio verdict it has no fields
        // for: that stays with the capabilities probe, so a catalog write carries it forward.
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

        // Merged like noteProtocolVersion, and both directions ride one write because one document
        // reports both: two writes would publish a host with the mic moved and the speaker not.
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
