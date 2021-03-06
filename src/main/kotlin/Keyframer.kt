package org.operndr.extra.keyframer

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.openrndr.color.ColorRGBa
import org.openrndr.extras.easing.Easing
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import java.io.File
import kotlin.math.roundToInt
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible


enum class KeyframerFormat {
    SIMPLE,
    FULL
}

open class Keyframer {
    private var currentTime = 0.0
    operator fun invoke(time: Double) {
        currentTime = time
    }

    open inner class CompoundChannel(val keys: Array<String>, private val defaultValues: Array<Double>) {
        private var channelTimes: Array<Double> = Array(keys.size) { Double.NEGATIVE_INFINITY }
        private var compoundChannels: Array<KeyframerChannel?> = Array(keys.size) { null }
        private var cachedValues: Array<Double?> = Array(keys.size) { null }

        open fun reset() {
            for (i in channelTimes.indices) {
                channelTimes[i] = Double.NEGATIVE_INFINITY
            }
        }

        fun getValue(compound: Int): Double {
            if (compoundChannels[compound] == null) {
                compoundChannels[compound] = channels[keys[compound]]
            }
            return if (compoundChannels[compound] != null) {
                if (channelTimes[compound] == currentTime && cachedValues[compound] != null) {
                    cachedValues[compound] ?: defaultValues[compound]
                } else {
                    val value = compoundChannels[compound]?.value(currentTime) ?: defaultValues[compound]
                    cachedValues[compound] = value
                    value
                }
            } else {
                defaultValues[compound]
            }
        }
    }

    inner class DoubleChannel(key: String, defaultValue: Double = 0.0) :
        CompoundChannel(arrayOf(key), arrayOf(defaultValue)) {
        operator fun getValue(keyframer: Keyframer, property: KProperty<*>): Double = getValue(0)
    }

    inner class Vector2Channel(keys: Array<String>, defaultValue: Vector2 = Vector2.ZERO) :
        CompoundChannel(keys, arrayOf(defaultValue.x, defaultValue.y)) {
        operator fun getValue(keyframer: Keyframer, property: KProperty<*>): Vector2 = Vector2(getValue(0), getValue(1))
    }

    inner class Vector3Channel(keys: Array<String>, defaultValue: Vector3 = Vector3.ZERO) :
        CompoundChannel(keys, arrayOf(defaultValue.x, defaultValue.y, defaultValue.z)) {
        operator fun getValue(keyframer: Keyframer, property: KProperty<*>): Vector3 =
            Vector3(getValue(0), getValue(1), getValue(2))
    }

    inner class Vector4Channel(keys: Array<String>, defaultValue: Vector4 = Vector4.ZERO) :
        CompoundChannel(keys, arrayOf(defaultValue.x, defaultValue.y, defaultValue.z, defaultValue.w)) {
        operator fun getValue(keyframer: Keyframer, property: KProperty<*>): Vector4 =
            Vector4(getValue(0), getValue(1), getValue(2), getValue(3))
    }

    inner class RGBaChannel(keys: Array<String>, defaultValue: ColorRGBa = ColorRGBa.WHITE) :
        CompoundChannel(keys, arrayOf(defaultValue.r, defaultValue.g, defaultValue.b, defaultValue.a)) {
        operator fun getValue(keyframer: Keyframer, property: KProperty<*>): ColorRGBa =
            ColorRGBa(getValue(0), getValue(1), getValue(2), getValue(3))
    }

    inner class RGBChannel(keys: Array<String>, defaultValue: ColorRGBa = ColorRGBa.WHITE) :
        CompoundChannel(keys, arrayOf(defaultValue.r, defaultValue.g, defaultValue.b)) {
        operator fun getValue(keyframer: Keyframer, property: KProperty<*>): ColorRGBa =
            ColorRGBa(getValue(0), getValue(1), getValue(2))
    }

    val channels = mutableMapOf<String, KeyframerChannel>()

    fun loadFromJson(file: File, format: KeyframerFormat = KeyframerFormat.SIMPLE, parameters:Map<String, Double> = emptyMap()) {
        when (format) {
            KeyframerFormat.SIMPLE -> {
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                val keys : List<MutableMap<String, Any>> = Gson().fromJson(file.readText(), type)
                loadFromObjects(keys, parameters)
            }
            KeyframerFormat.FULL -> {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val keys : Map<String, Any> = Gson().fromJson(file.readText(), type)
                loadFromObjects(keys, parameters)
            }
        }
    }

    val parameters = mutableMapOf<String, Double>()
    val prototypes = mutableMapOf<String, Map<String, Any>>()

    fun loadFromObjects(dict: Map<String, Any>, externalParameters: Map<String, Double>) {
        this.parameters.clear()
        this.parameters.putAll(externalParameters)

        prototypes.clear()
        (dict["parameters"] as? Map<String, Any>)?.let { lp ->
            for (entry in lp) {
                this.parameters[entry.key] = when (val candidate = entry.value) {
                    is Double -> candidate
                    is String -> evaluateExpression(candidate, parameters)
                        ?: error("could not evaluate expression: '$candidate'")
                    is Int -> candidate.toDouble()
                    is Float -> candidate.toDouble()
                    else -> error("unknown type for parameter '${entry.key}'")
                }
            }
        }
        this.parameters.putAll(externalParameters)

        (dict["prototypes"] as? Map<String, Map<String, Any>>)?.let {
            prototypes.putAll(it)
        }

        (dict["keys"] as? List<Map<String, Any>>)?.let { keys ->
            loadFromObjects(keys, parameters)
        }
    }

    fun resolvePrototype(prototypeNames:String) : Map<String, Any> {
        val prototypeTokens = prototypeNames.split(" ").map { it.trim() }.filter { it.isNotBlank() }
        val prototypeRefs = prototypeTokens.mapNotNull { prototypes[it] }

        val computed = mutableMapOf<String, Any>()
        for (ref in prototypeRefs) {
            computed.putAll(ref)
        }
        return computed
    }

    fun loadFromObjects(keys: List<Map<String, Any>>, externalParameters : Map<String, Double>) {

        if (externalParameters !== parameters) {
            parameters.clear()
            parameters.putAll(externalParameters)
        }

        var lastTime = 0.0

        val channelDelegates = this::class.memberProperties
            .mapNotNull { it as? KProperty1<Keyframer, Any> }
            .filter { it.isAccessible = true; it.getDelegate(this) is CompoundChannel }
            .associate { Pair(it.name, it.getDelegate(this) as CompoundChannel) }

        val channelKeys = channelDelegates.values.flatMap {
            it.keys.map { it }
        }.toSet()

        for (delegate in channelDelegates.values) {
            delegate.reset()
        }

        val expressionContext = mutableMapOf<String, Double>()
        expressionContext.putAll(parameters)
        expressionContext["t"] = 0.0

        fun handleKey(key: Map<String, Any>) {

            val prototype = (key["prototypes"] as? String)?.let {
                resolvePrototype(it)
            } ?: emptyMap()

            val computed = mutableMapOf<String, Any>()
            computed.putAll(prototype)
            computed.putAll(key)

            val time = when (val candidate = computed["time"]) {
                null -> lastTime
                is String -> evaluateExpression(candidate, expressionContext)
                    ?: error { "unknown value format for time : $candidate" }
                is Double -> candidate
                is Int -> candidate.toDouble()
                is Float -> candidate.toDouble()
                else -> error("unknown time format for '$candidate'")
            }

            val duration = when (val candidate = computed["duration"]) {
                null -> 0.0
                is String -> evaluateExpression(candidate, expressionContext)
                    ?: error { "unknown value format for time : $candidate" }
                is Int -> candidate.toDouble()
                is Float -> candidate.toDouble()
                is Double -> candidate
                else -> error("unknown duration type for '$candidate")
            }

            val easing = when (val easingCandidate = computed["easing"]) {
                null -> Easing.Linear.function
                is String -> when (easingCandidate) {
                    "linear" -> Easing.Linear.function
                    "cubic-in" -> Easing.CubicIn.function
                    "cubic-out" -> Easing.CubicOut.function
                    "cubic-in-out" -> Easing.CubicInOut.function
                    "quad-in" -> Easing.QuadIn.function
                    "quad-out" -> Easing.QuadOut.function
                    "quad-in-out" -> Easing.QuadInOut.function
                    "quart-in" -> Easing.QuartIn.function
                    "quart-out" -> Easing.QuartOut.function
                    "quart-in-out" -> Easing.QuartInOut.function
                    "quint-in" -> Easing.QuintIn.function
                    "quint-out" -> Easing.QuintOut.function
                    "quint-in-out" -> Easing.QuintInOut.function
                    "expo-in" -> Easing.ExpoIn.function
                    "expo-out" -> Easing.ExpoOut.function
                    "expo-in-out" -> Easing.ExpoInOut.function
                    "one" -> Easing.One.function
                    "zero" -> Easing.Zero.function
                    else -> error { "unknown easing name '$easingCandidate" }
                }
                else -> error { "unknown easing for '$easingCandidate" }
            }

            val holdCandidate = computed["hold"]
            val hold = Hold.HoldNone

            val reservedKeys = setOf("time", "easing", "hold")

            for (channelCandidate in computed.filter { it.key !in reservedKeys }) {
                if (channelCandidate.key in channelKeys) {
                    val channel = channels.getOrPut(channelCandidate.key) {
                        KeyframerChannel()
                    }
                    expressionContext["v"] = channel.lastValue() ?: 0.0
                    val value = when (val candidate = channelCandidate.value) {
                        is Double -> candidate
                        is String -> evaluateExpression(candidate, expressionContext)
                            ?: error { "unknown value format for key '${channelCandidate.key}' : $candidate" }
                        is Int -> candidate.toDouble()
                        else -> error { "unknown value type for key '${channelCandidate.key}' : $candidate" }
                    }
                    channel.add(time, value, easing, hold)
                }
            }
            lastTime = time + duration
            expressionContext["t"] = lastTime

            if (computed.containsKey("repeat")) {
                val repeatObject = computed["repeat"] as? Map<String, Any> ?: error("'repeat' should be a map")
                val count = when (val candidate = repeatObject["count"]) {
                    null -> 1
                    is Int -> candidate
                    is Double -> candidate.toInt()
                    is String -> evaluateExpression(candidate, expressionContext)?.roundToInt() ?: error("cannot evaluate expression for count: '$candidate'")
                    else -> error("unknown value type for count: '$candidate")
                }

                val keys = repeatObject["keys"] as? List<Map<String, Any>> ?: error("no repeat keys")

                for (i in 0 until count) {
                    expressionContext["r"] = i.toDouble()
                    for (key in keys) {
                        handleKey(key)
                    }
                }
            }
        }

        for (key in keys) {
            handleKey(key)
        }
    }
}
