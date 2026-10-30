package chat.client.media

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

// Геопозиция (Фаза W4, аналог LocationPicker у Telegram): платформенный
// LocationManager без Google-зависимостей. Сначала — последнее известное
// место (мгновенно), иначе короткий запрос с таймаутом; точность —
// доступный провайдер (GPS → сеть → passive).
object GeoLocation {

    private val TIMEOUT_MS = 10_000L
    private val STALE_MS = 120_000L // последнее известное не старше 2 минут

    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    private fun manager(context: Context): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun isGranted(context: Context): Boolean {
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            coarse == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // валидный провайдер: включён и есть разрешение
    private fun enabledProviders(context: Context, lm: LocationManager): List<String> =
        providers.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    private fun lastKnown(context: Context, lm: LocationManager): Location? {
        if (!isGranted(context)) return null
        var best: Location? = null
        for (provider in enabledProviders(context, lm)) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        // устаревшая фиксация — не годится, попросим свежую
        if (best != null && System.currentTimeMillis() - best.time > STALE_MS) return null
        return best
    }

    // точка сейчас: null — нет разрешения/провайдеров или таймаут.
    // Разрешение проверено isGranted, вызовы обёрнуты в runCatching —
    // SecurityException от системы невозможен, Suppress честный
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context): chat.client.domain.GeoFormat.Point? {
        val lm = manager(context) ?: return null
        if (!isGranted(context)) return null
        lastKnown(context, lm)?.let {
            return chat.client.domain.GeoFormat.Point(it.latitude, it.longitude)
        }
        val available = enabledProviders(context, lm)
        if (available.isEmpty()) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = LocationListener { location ->
                    if (cont.isActive) {
                        cont.resumeWith(
                            Result.success(
                                chat.client.domain.GeoFormat.Point(location.latitude, location.longitude),
                            ),
                        )
                    }
                }
                var requested = false
                for (provider in available) {
                    try {
                        lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                        requested = true
                    } catch (_: Exception) {
                    }
                }
                if (!requested) {
                    cont.resumeWith(Result.success(null))
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    try {
                        lm.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
