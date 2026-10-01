package com.openminis.app.sandbox.offload

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One bounded observation window; discard cached fixes and always unregister listeners. */
internal object HighAccuracyLocation {
    data class Result(val location: Location?, val backend: String)

    @SuppressLint("MissingPermission")
    fun request(context: Context, manager: LocationManager, providers: List<String>, timeout: Int): Result {
        val started = SystemClock.elapsedRealtimeNanos()
        val best = AtomicReference<Location?>()
        val latch = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        fun observe(location: Location) {
            if (closed.get() || !PreciseLocationPolicy.usable(
                    location.elapsedRealtimeNanos, started, location.hasAccuracy(), location.accuracy)) return
            synchronized(best) {
                val previous = best.get()
                if (previous == null || location.accuracy <= previous.accuracy) best.set(Location(location))
            }
            if (location.accuracy <= 50f) latch.countDown()
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = observe(location)
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        val fallbackStarted = AtomicBoolean(false)
        fun startFallback() {
            synchronized(closed) {
                if (closed.get() || !fallbackStarted.compareAndSet(false, true)) return
                for (provider in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in providers }) {
                    runCatching { manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper()) }
                }
            }
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.locations.forEach(::observe) }
        }
        val hasPlay = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        try {
            if (hasPlay) {
                try {
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                        .setMinUpdateIntervalMillis(500L).setMaxUpdateAgeMillis(0L)
                        .setDurationMillis(timeout * 1000L).setWaitForAccurateLocation(false).build()
                    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                        .addOnFailureListener { startFallback() }
                } catch (_: Exception) { startFallback() }
            } else startFallback()
            latch.await(timeout * 1000L, TimeUnit.MILLISECONDS)
        } finally {
            synchronized(closed) {
                closed.set(true)
                runCatching { client.removeLocationUpdates(callback) }
                runCatching { manager.removeUpdates(listener) }
            }
        }
        return Result(best.get(), if (fallbackStarted.get()) "system" else "google_fused")
    }
}
