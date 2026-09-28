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
            setState { withProtocolVersion(it, connectionId, protocolVersion) }
        }

        // Merged like noteProtocolVersion, and both directions ride one write because one document
        // reports both: two writes would publish a host with the mic moved and the speaker not.
        fun noteControllerAudio(
            connectionId: String,
            mic: Boolean,
            speaker: Boolean,
        ) {
            setState { withControllerAudio(it, connectionId, mic, speaker) }
        }

        fun clearConnection(connectionId: String) {
            setState { if (connectionId in it) it - connectionId else it }
        }
    }

// A host the store has not heard of starts from the default. A read that changes nothing hands
// back the same map, so a re-probe that learned nothing builds no new one.
internal fun withProtocolVersion(
    features: Map<String, HostFeatureSet>,
    connectionId: String,
    protocolVersion: Int,
): Map<String, HostFeatureSet> {
    val base = features[connectionId] ?: HostFeatureSet.SATELLITE_DEFAULT
    val isUnchanged = base.protocolVersion == protocolVersion
    if (isUnchanged) return features
    return features + (connectionId to base.copy(protocolVersion = protocolVersion))
}

// The same rule for the audio verdict, read as a pair: moving either direction is a change.
internal fun withControllerAudio(
    features: Map<String, HostFeatureSet>,
    connectionId: String,
    mic: Boolean,
    speaker: Boolean,
): Map<String, HostFeatureSet> {
    val base = features[connectionId] ?: HostFeatureSet.SATELLITE_DEFAULT
    val isUnchanged = base.controllerMic == mic && base.controllerSpeaker == speaker
    if (isUnchanged) return features
    return features + (connectionId to base.copy(controllerMic = mic, controllerSpeaker = speaker))
}
