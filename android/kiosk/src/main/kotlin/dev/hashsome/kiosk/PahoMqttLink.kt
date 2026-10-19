package dev.hashsome.kiosk

import dev.hashsome.client.Scheduler
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttActionListener
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.packet.MqttProperties

/**
 * [MqttLink] over Paho MQTTv5 (works on API 24 and 30 under R8 with the keep rule in `consumer-rules.pro`, spike S4).
 * Paho calls back on its own threads; everything reaches the listener through [scheduler], on the main thread. A callback
 * of a client that has since been replaced or disconnected on purpose is dropped, so the bridge only hears about the current one.
 * No automatic reconnect: the bridge reconnects with its own backoff, which also covers a first connect that fails.
 */
internal class PahoMqttLink(private val scheduler: Scheduler) : MqttLink {
    private var client: MqttAsyncClient? = null
    private var generation = 0

    override fun connect(host: String, port: Int, clientId: String, will: MqttLink.Message, listener: MqttLink.Listener) {
        drop()
        val mine = ++generation
        fun onMain(fn: () -> Unit) = scheduler.post { if (mine == generation) fn() }
        try {
            val c = MqttAsyncClient("tcp://$host:$port", clientId, MemoryPersistence())
            client = c
            c.setCallback(object : MqttCallback {
                override fun disconnected(disconnectResponse: MqttDisconnectResponse) {
                    onMain { listener.onDisconnected(disconnectResponse.reasonString ?: disconnectResponse.exception?.message) }
                }

                override fun mqttErrorOccurred(exception: MqttException) {
                    Log.w("mqtt", "client error: ${exception.message}")
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val text = String(message.payload, Charsets.UTF_8)
                    onMain { listener.onMessage(MqttLink.Message(topic, text, message.isRetained)) }
                }

                override fun deliveryComplete(token: IMqttToken) {}
                override fun connectComplete(reconnect: Boolean, serverURI: String) {}
                override fun authPacketArrived(reasonCode: Int, properties: MqttProperties) {}
            })
            val options = MqttConnectionOptions().apply {
                isCleanStart = true
                // A power cut is noticed by the broker after 1.5 x this, which is when the last will marks the device unavailable.
                keepAliveInterval = KEEP_ALIVE_S
                connectionTimeout = 10
                isAutomaticReconnect = false
                setWill(will.topic, MqttMessage(will.payload.toByteArray(Charsets.UTF_8)).apply { qos = 1; isRetained = will.retained })
            }
            c.connect(options, null, object : MqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    onMain { listener.onConnected() }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    onMain { listener.onDisconnected(exception?.message ?: "connect failed") }
                }
            })
        } catch (e: Exception) {
            onMain { listener.onDisconnected(e.message ?: e.javaClass.simpleName) }
        }
    }

    override fun publish(message: MqttLink.Message) {
        try {
            client?.publish(message.topic, MqttMessage(message.payload.toByteArray(Charsets.UTF_8)).apply { qos = 1; isRetained = message.retained })
        } catch (e: MqttException) {
            Log.w("mqtt", "publish to ${message.topic} failed: ${e.message}")
        }
    }

    override fun subscribe(topic: String) {
        try {
            client?.subscribe(topic, 1)
        } catch (e: MqttException) {
            Log.w("mqtt", "subscribe to $topic failed: ${e.message}")
        }
    }

    override fun disconnect() = drop()

    /** Ends the current client without telling anyone: whoever asked knows. */
    private fun drop() {
        generation++
        val c = client ?: return
        client = null
        val close = object : MqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) = closeQuietly(c)
            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) = closeQuietly(c)
        }
        try {
            // Quiesces first, so a last "offline" still in flight leaves before the connection closes.
            c.disconnect(null, close)
        } catch (e: Exception) {
            closeQuietly(c)
        }
    }

    private fun closeQuietly(c: MqttAsyncClient) {
        try {
            c.close()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val KEEP_ALIVE_S = 20
    }
}
