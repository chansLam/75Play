package com.shihab.diplay.airplay

import java.io.Closeable
import java.net.Socket
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMediaEngineTest {
    @Test
    fun mediaLivenessRequiresARealPacket() {
        val engine = CarPlayMediaEngine(object : MediaSink {})

        assertFalse(engine.isDataRecent(10_000L))
        engine.noteDataReceived()
        assertTrue(engine.isDataRecent(10_000L))
    }

    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun sessionCloseKeepsAudioThatBelongsToTheOtherSession() {
        val live = testSession()
        val ended = testSession()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val audioMetaField = CarPlayMediaEngine::class.java.getDeclaredField("audioMeta").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val audioMeta = audioMetaField.get(engine) as MutableMap<Any, Any>
        val liveKey = CarPlayMediaEngine.StreamKey(live, 100, "media")
        val endedKey = CarPlayMediaEngine.StreamKey(ended, 100, "media")
        audioMeta[liveKey] = "live"
        audioMeta[endedKey] = "ended"

        try {
            engine.onSessionClosed(ended)
            assertEquals(setOf(liveKey), audioMeta.keys)
        } finally {
            live.close()
            ended.close()
        }
    }

    @Test
    fun videoRemoteControlSessionsAreAcceptedOnlyWithVideoInCar() {
        val stream = mapOf("type" to 130L, "clientTypeUUID" to "A6B27562-B43A-4F2D-B75F-82391E250194", "controlType" to 1L)
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val plain = testSession()
        val video = testSession(videoInCar = true)
        try {
            assertNull(engine.onDataStream(plain, stream))
            val first = engine.onDataStream(video, stream)
            assertEquals(130, first?.get("type"))
            assertEquals(3L, first?.get("streamID"))
            assertEquals(4L, engine.onDataStream(video, stream)?.get("streamID"))
        } finally {
            plain.close()
            video.close()
        }
    }

    @Test
    fun onAudioRegistersMicrophoneUplinkPortWithTransportBridge() {
        val registeredUdpPorts = mutableListOf<Int>()
        val server = java.net.ServerSocket()
        server.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0))
        val client = java.net.Socket()
        client.connect(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), server.localPort))
        val accepted = server.accept()
        val session = testSession(
            socket = accepted,
            registerPort = { port, isTcp ->
                if (!isTcp) registeredUdpPorts += port
            },
        )
        val pairVerify = session.pairVerify
        val sharedField = pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
        sharedField.set(pairVerify, ByteArray(32))

        val engine = CarPlayMediaEngine(object : MediaSink {}, microphoneEnabled = true)
        val stream = mapOf<String, Any?>(
            "type" to 100L,
            "audioType" to "speechrecognition",
            "audioFormat" to 0x40000000L, // OPUS
            "dataPort" to 49152L,
            "streamConnectionID" to 1L,
        )
        try {
            val response = engine.onAudio(session, 100, stream)
            // Downlink dataPort and controlPort plus iPhone's mic dataPort
            assertTrue("iPhone's mic dataPort must be registered with the transport bridge", 49152 in registeredUdpPorts)
            assertEquals(3, registeredUdpPorts.size)
            assertEquals(response?.get("dataPort"), registeredUdpPorts[0])
            assertEquals(response?.get("controlPort"), registeredUdpPorts[1])
            assertEquals(49152, registeredUdpPorts[2])
        } finally {
            session.close()
            client.close()
            server.close()
        }
    }

    private fun testSession(
        videoInCar: Boolean = false,
        socket: Socket = Socket(),
        registerPort: ((Int, Boolean) -> Unit)? = null,
    ): AirPlaySession = AirPlaySession(
        socket = socket,
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            videoInCar = videoInCar,
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
        registerPort = registerPort,
    )
}
