package dev.sentinel.app

object SentinelNative {
    init { System.loadLibrary("sentinel_jni") }
    external fun validCoordinates(latitude: Double, longitude: Double): Boolean
}
