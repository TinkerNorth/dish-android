// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import com.tinkernorth.dish.core.net.DishProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityTest {
    @Test
    fun `contains checks membership`() {
        val set = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION)
        assertTrue(Feature.MOTION in set)
        assertFalse(Feature.RUMBLE in set)
    }

    @Test
    fun `intersect keeps only shared features`() {
        val a = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION, Feature.RUMBLE)
        val b = CapabilitySet.of(Feature.MOTION, Feature.RUMBLE, Feature.TOUCHPAD)
        assertEquals(CapabilitySet.of(Feature.MOTION, Feature.RUMBLE), a intersect b)
    }

    @Test
    fun `minus removes the right-hand features`() {
        val a = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION, Feature.RUMBLE)
        val b = CapabilitySet.of(Feature.MOTION)
        assertEquals(CapabilitySet.of(Feature.GAMEPAD, Feature.RUMBLE), a - b)
    }

    @Test
    fun `EMPTY contains nothing`() {
        assertFalse(Feature.GAMEPAD in CapabilitySet.EMPTY)
        assertTrue(CapabilitySet.EMPTY.features.isEmpty())
    }

    @Test
    fun `toCapabilitySet carries the satellite baseline and gates optional features`() {
        val baseline =
            HostFeatureSet(
                hasCatalog = true,
                mouseControl = false,
                keyboardControl = false,
                rumbleReturn = true,
            ).toCapabilitySet()
        assertTrue(Feature.GAMEPAD in baseline)
        assertTrue(Feature.ANALOG_TRIGGERS in baseline)
        assertTrue(Feature.MOTION in baseline)
        assertTrue(Feature.TOUCHPAD in baseline)
        assertTrue(Feature.LIGHTBAR in baseline)
        assertTrue(Feature.RUMBLE in baseline)
        assertFalse(Feature.MOUSE in baseline)
        assertFalse(Feature.KEYBOARD in baseline)
    }

    @Test
    fun `toCapabilitySet adds MOUSE when mouseControl is granted`() {
        val withMouse =
            HostFeatureSet(
                hasCatalog = true,
                mouseControl = true,
                keyboardControl = false,
                rumbleReturn = true,
            ).toCapabilitySet()
        assertTrue(Feature.MOUSE in withMouse)
    }

    @Test
    fun `toCapabilitySet drops RUMBLE when the host has no return channel`() {
        val noRumble =
            HostFeatureSet(
                hasCatalog = true,
                mouseControl = false,
                keyboardControl = false,
                rumbleReturn = false,
            ).toCapabilitySet()
        assertFalse(Feature.RUMBLE in noRumble)
    }

    @Test
    fun `SATELLITE_DEFAULT is the optimistic not-yet-fetched baseline`() {
        val default = HostFeatureSet.SATELLITE_DEFAULT
        assertFalse(default.hasCatalog)
        // A satellite has always accepted mouse control and returned rumble, so both are
        // assumed until a fetched catalog refines them.
        assertTrue(default.mouseControl)
        assertTrue(default.rumbleReturn)
        val caps = default.toCapabilitySet()
        assertTrue(Feature.MOTION in caps)
        assertTrue(Feature.RUMBLE in caps)
        assertTrue(Feature.MOUSE in caps)
    }

    @Test
    fun `fromCatalog parses mouseControl supported and modes`() {
        val catalog =
            CatalogDto(
                hostFeatures =
                    mapOf(
                        "mouseControl" to CatalogHostFeatureDto(supported = true, modes = listOf("absolute", "relative")),
                    ),
            )
        val features = HostFeatureSet.fromCatalog(catalog)
        assertTrue(features.hasCatalog)
        assertTrue(features.mouseControl)
        assertTrue(Feature.MOUSE in features.toCapabilitySet())
    }

    @Test
    fun `fromCatalog treats a missing mouseControl as unsupported`() {
        val features = HostFeatureSet.fromCatalog(CatalogDto())
        assertTrue(features.hasCatalog)
        assertFalse(features.mouseControl)
        assertFalse(Feature.MOUSE in features.toCapabilitySet())
    }

    @Test
    fun `fromCatalog keeps rumble optimistic when the slug is absent (back-compat)`() {
        // A satellite predating the rumble host feature still returns rumble.
        val features = HostFeatureSet.fromCatalog(CatalogDto())
        assertTrue(features.rumbleReturn)
        assertTrue(Feature.RUMBLE in features.toCapabilitySet())
    }

    @Test
    fun `fromCatalog honors an explicit rumble unsupported`() {
        val catalog =
            CatalogDto(hostFeatures = mapOf("rumble" to CatalogHostFeatureDto(supported = false)))
        val features = HostFeatureSet.fromCatalog(catalog)
        assertFalse(features.rumbleReturn)
        assertFalse(Feature.RUMBLE in features.toCapabilitySet())
    }

    @Test
    fun `fromCatalog reads keyboardControl opt-in (absent stays unsupported)`() {
        assertFalse(HostFeatureSet.fromCatalog(CatalogDto()).keyboardControl)
        val withKeyboard =
            HostFeatureSet.fromCatalog(
                CatalogDto(hostFeatures = mapOf("keyboardControl" to CatalogHostFeatureDto(supported = true))),
            )
        assertTrue(withKeyboard.keyboardControl)
        assertTrue(Feature.KEYBOARD in withKeyboard.toCapabilitySet())
    }

    @Test
    fun `fromServerCapabilities reads the host block`() {
        val caps =
            ServerCapabilitiesDto(
                motion = ServerMotionDto(available = true),
                host =
                    ServerHostDto(
                        catalog = ServerHostFeatureDto(supported = true),
                        mouseControl = ServerHostFeatureDto(supported = true, available = true),
                        keyboardControl = ServerHostFeatureDto(supported = false),
                        rumble = ServerHostFeatureDto(supported = false),
                    ),
            )
        val features = HostFeatureSet.fromServerCapabilities(caps)
        assertTrue(features.hasCatalog)
        assertTrue(features.mouseControl)
        assertFalse(features.keyboardControl)
        // The host explicitly reports no rumble return; honored, not assumed.
        assertFalse(features.rumbleReturn)
        assertTrue(Feature.MOUSE in features.toCapabilitySet())
        assertFalse(Feature.RUMBLE in features.toCapabilitySet())
    }

    @Test
    fun `extended mouse needs a v2 protocol read, so an unversioned document means basic`() {
        assertFalse(HostFeatureSet.SATELLITE_DEFAULT.extendedMouse)
        val fromV1Catalog =
            HostFeatureSet.fromCatalog(
                CatalogDto(hostFeatures = mapOf("mouseControl" to CatalogHostFeatureDto(supported = true))),
            )
        assertFalse(fromV1Catalog.extendedMouse)
        val fromV1Caps =
            HostFeatureSet.fromServerCapabilities(
                ServerCapabilitiesDto(host = ServerHostDto(mouseControl = ServerHostFeatureDto(supported = true))),
            )
        assertFalse(fromV1Caps.extendedMouse)
    }

    @Test
    fun `fromCatalog carries the protocol version into extended mouse`() {
        val features =
            HostFeatureSet.fromCatalog(
                CatalogDto(
                    protocolVersion = 2,
                    hostFeatures = mapOf("mouseControl" to CatalogHostFeatureDto(supported = true)),
                ),
            )
        assertEquals(2, features.protocolVersion)
        assertTrue(features.extendedMouse)
    }

    @Test
    fun `fromServerCapabilities carries the protocol version into extended mouse`() {
        val features =
            HostFeatureSet.fromServerCapabilities(
                ServerCapabilitiesDto(
                    protocolVersion = 2,
                    host = ServerHostDto(mouseControl = ServerHostFeatureDto(supported = true, available = true)),
                ),
            )
        assertEquals(2, features.protocolVersion)
        assertTrue(features.extendedMouse)
    }

    @Test
    fun `compat mirrors the advertised version, with zero reading as unknown`() {
        assertEquals(DishProtocol.Compat.UNKNOWN, HostFeatureSet.SATELLITE_DEFAULT.compat)
        assertEquals(
            DishProtocol.Compat.SATELLITE_UPDATE_AVAILABLE,
            HostFeatureSet.SATELLITE_DEFAULT.copy(protocolVersion = DishProtocol.CURRENT - 1).compat,
        )
        assertEquals(
            DishProtocol.Compat.CURRENT,
            HostFeatureSet.SATELLITE_DEFAULT.copy(protocolVersion = DishProtocol.CURRENT).compat,
        )
        assertEquals(
            DishProtocol.Compat.APP_UPDATE_REQUIRED,
            HostFeatureSet.SATELLITE_DEFAULT.copy(protocolVersion = DishProtocol.CURRENT + 1).compat,
        )
    }

    // ── controller audio: the one runtime-switched host fact ────────────────

    private fun hostWith(
        mic: Boolean,
        speaker: Boolean = mic,
    ): HostFeatureSet =
        HostFeatureSet(
            hasCatalog = true,
            mouseControl = true,
            keyboardControl = false,
            rumbleReturn = true,
            controllerMic = mic,
            controllerSpeaker = speaker,
        )

    private fun backend(
        id: String,
        available: Boolean,
        audio: Boolean,
    ): ServerBackendDto = ServerBackendDto(id = id, supported = true, available = available, audio = audio)

    private fun audioBlock(
        enabled: Boolean = true,
        mic: Boolean = true,
        speaker: Boolean = true,
    ): ServerControllerAudioDto = ServerControllerAudioDto(enabled = enabled, mic = mic, speaker = speaker)

    @Test
    fun `the audio pair rides the host layer only while the host carries audio`() {
        val on = hostWith(mic = true).toCapabilitySet()
        assertTrue(Feature.MIC in on)
        assertTrue(Feature.SPEAKER in on)

        val off = hostWith(mic = false).toCapabilitySet()
        assertFalse(Feature.MIC in off)
        assertFalse(Feature.SPEAKER in off)
        // The rest of the host layer is untouched by the audio switch.
        assertTrue(Feature.GAMEPAD in off)
        assertTrue(Feature.RUMBLE in off)
        assertTrue(Feature.LIGHTBAR in off)
    }

    @Test
    fun `each audio direction reaches its own feature and only its own`() {
        // The host gates the two wires separately, so one verdict cannot stand in for both:
        // a speaker-only host that offered a microphone would spend a permission prompt on
        // frames the host drops.
        val micOnly = hostWith(mic = true, speaker = false).toCapabilitySet()
        assertTrue(Feature.MIC in micOnly)
        assertFalse(Feature.SPEAKER in micOnly)

        val speakerOnly = hostWith(mic = false, speaker = true).toCapabilitySet()
        assertFalse(Feature.MIC in speakerOnly)
        assertTrue(Feature.SPEAKER in speakerOnly)
    }

    @Test
    fun `SATELLITE_DEFAULT keeps audio off, unlike the optimistic mouse and rumble`() {
        // Opt-IN: an unprobed satellite may well predate controller audio, and offering a
        // microphone that cannot land would cost a permission prompt for nothing.
        assertFalse(HostFeatureSet.SATELLITE_DEFAULT.controllerMic)
        assertFalse(HostFeatureSet.SATELLITE_DEFAULT.controllerSpeaker)
        assertFalse(Feature.MIC in HostFeatureSet.SATELLITE_DEFAULT.toCapabilitySet())
        assertFalse(Feature.SPEAKER in HostFeatureSet.SATELLITE_DEFAULT.toCapabilitySet())
    }

    @Test
    fun `the controllerAudio block reports each direction on its own`() {
        val caps =
            ServerCapabilitiesDto(
                controllerAudio = audioBlock(mic = true, speaker = false),
                host = ServerHostDto(catalog = ServerHostFeatureDto(supported = true)),
            )
        val features = HostFeatureSet.fromServerCapabilities(caps)
        assertTrue(features.controllerMic)
        assertFalse(features.controllerSpeaker)
        assertTrue(Feature.MIC in features.toCapabilitySet())
        assertFalse(Feature.SPEAKER in features.toCapabilitySet())

        val mirrored =
            HostFeatureSet.fromServerCapabilities(
                ServerCapabilitiesDto(controllerAudio = audioBlock(mic = false, speaker = true)),
            )
        assertFalse(mirrored.controllerMic)
        assertTrue(mirrored.controllerSpeaker)
    }

    @Test
    fun `a disabled controllerAudio block forces both directions off`() {
        // The host folds `enabled` into both switches already; this is the client refusing to
        // hand out an endpoint on a stale switch should a host ever stop folding it.
        val features =
            HostFeatureSet.fromServerCapabilities(
                ServerCapabilitiesDto(controllerAudio = audioBlock(enabled = false, mic = true, speaker = true)),
            )
        assertFalse(features.controllerMic)
        assertFalse(features.controllerSpeaker)
    }

    @Test
    fun `the controllerAudio block wins over the per-backend flag`() {
        // The backend still reports audio (it is the one that carries it), but the host has
        // switched one direction of the WIRE off, and the wire is what the client obeys.
        val caps =
            ServerCapabilitiesDto(
                backends = listOf(backend("hidmaestro", available = true, audio = true)),
                controllerAudio = audioBlock(mic = false, speaker = true),
            )
        val features = HostFeatureSet.fromServerCapabilities(caps)
        assertFalse(features.controllerMic)
        assertTrue(features.controllerSpeaker)
    }

    @Test
    fun `an absent block falls back to the per-backend flag for both directions`() {
        // A satellite that carries audio but predates the block: reading the missing block as
        // two falses would mute it outright, so the older per-backend verdict answers for both.
        val caps =
            ServerCapabilitiesDto(
                backends =
                    listOf(
                        backend("vigem", available = true, audio = false),
                        backend("hidmaestro", available = true, audio = true),
                    ),
                host = ServerHostDto(catalog = ServerHostFeatureDto(supported = true)),
            )
        val features = HostFeatureSet.fromServerCapabilities(caps)
        assertTrue(features.controllerMic)
        assertTrue(features.controllerSpeaker)
        assertTrue(Feature.MIC in features.toCapabilitySet())
        assertTrue(Feature.SPEAKER in features.toCapabilitySet())
    }

    @Test
    fun `fromServerCapabilities honors a host that switched controller audio off`() {
        // The setting is folded into `audio` server-side, so every backend reports false.
        val caps =
            ServerCapabilitiesDto(
                backends =
                    listOf(
                        backend("vigem", available = true, audio = false),
                        backend("hidmaestro", available = true, audio = false),
                    ),
            )
        val features = HostFeatureSet.fromServerCapabilities(caps)
        assertFalse(features.controllerMic)
        assertFalse(features.controllerSpeaker)
    }

    @Test
    fun `fromServerCapabilities ignores an audio backend that is not available`() {
        // A backend that cannot open its bus materializes nothing, audio included.
        val caps =
            ServerCapabilitiesDto(
                backends = listOf(backend("hidmaestro", available = false, audio = true)),
            )
        val features = HostFeatureSet.fromServerCapabilities(caps)
        assertFalse(features.controllerMic)
        assertFalse(features.controllerSpeaker)
    }

    @Test
    fun `an older satellite sends no backends array, which reads as no audio`() {
        val features = HostFeatureSet.fromServerCapabilities(ServerCapabilitiesDto())
        assertFalse(features.controllerMic)
        assertFalse(features.controllerSpeaker)
    }

    @Test
    fun `fromCatalog never claims audio, because the catalog cannot carry it`() {
        // The catalog is cached on server version + locale, so an install-time switch
        // must not move it; the store carries the probe's verdict across this write.
        assertFalse(HostFeatureSet.fromCatalog(CatalogDto()).controllerMic)
        assertFalse(HostFeatureSet.fromCatalog(CatalogDto()).controllerSpeaker)
        assertFalse(
            Feature.MIC in
                HostFeatureSet
                    .fromCatalog(
                        CatalogDto(hostFeatures = mapOf("mouseControl" to CatalogHostFeatureDto(supported = true))),
                    ).toCapabilitySet(),
        )
    }

    @Test
    fun `the audio features carry the protocol's own slugs and directions`() {
        // The slugs are protocol constants; the catalog is matched on them by name.
        assertEquals("mic", Feature.MIC.catalogSlug)
        assertEquals("speaker", Feature.SPEAKER.catalogSlug)
        // The phone SOURCES the microphone and RECEIVES the pad's speaker audio.
        assertEquals(Direction.SEND, Feature.MIC.direction)
        assertEquals(Direction.RECEIVE, Feature.SPEAKER.direction)
    }

    @Test
    fun `extended mouse needs both the version and mouse control itself`() {
        val versionWithoutMouse =
            HostFeatureSet.fromCatalog(CatalogDto(protocolVersion = 2))
        assertFalse(versionWithoutMouse.extendedMouse)
        val futureVersion =
            HostFeatureSet.fromCatalog(
                CatalogDto(
                    protocolVersion = 3,
                    hostFeatures = mapOf("mouseControl" to CatalogHostFeatureDto(supported = true)),
                ),
            )
        assertTrue(futureVersion.extendedMouse)
    }
}
