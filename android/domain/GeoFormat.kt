package chat.client.domain

import org.json.JSONObject
import java.util.Locale

// Геопозиция (Фаза W4, аналог Location у Telegram): сообщение-карточка
// с координатами и кнопкой «Открыть карту» (системный geo:-intent,
// фолбэк — OSM-ссылка в браузер; карта не рисуется — без новых
// зависимостей, осознанный потолок). Точность — 6 знаков (~0.1 м),
// как принято у геоссылок.
object GeoFormat {

    class Point(val lat: Double, val lng: Double) {
        // локале-независимо: десятичный разделитель — точка (не запятая ru)
        val text: String get() = "%.5f, %.5f".format(Locale.US, lat, lng)
    }

    private fun round6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0

    fun encode(lat: Double, lng: Double): ByteArray =
        Envelope.encode(
            Envelope.TYPE_GEO,
            JSONObject()
                .put("la", round6(lat))
                .put("lo", round6(lng))
                .toString(),
        )

    // строгий разбор: null — мусор; диапазоны координат проверяем
    fun decode(payload: ByteArray): Point? = try {
        val json = JSONObject(payload.decodeToString())
        val lat = json.optDouble("la", Double.NaN)
        val lng = json.optDouble("lo", Double.NaN)
        if (!lat.isFinite() || !lng.isFinite() ||
            lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0
        ) {
            null
        } else {
            Point(lat, lng)
        }
    } catch (_: Exception) {
        null
    }

    // геопозиция из произвольного содержимого: null — не гео
    fun of(content: ByteArray): Point? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_GEO) return null
        return decode(envelope.payload)
    }
}
