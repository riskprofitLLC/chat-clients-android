package chat.client.media

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

// AK2 (аналог EarListener у Telegram): датчик приближения во время
// воспроизведения — поднёс телефон к уху, звук идёт в наушник уха;
// отвёл — обратно в динамик. Слушатель живёт только пока играет
// дорожка, отключение всегда возвращает маршрут.
class EarAudio(
    private val context: Context,
    private val apply: (near: Boolean) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var registered = false

    // нет датчика — фича молча не работает, маршрут не трогаем
    val available: Boolean get() = sensor != null

    @Synchronized
    fun attach() {
        if (registered || sensor == null) return
        try {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            registered = true
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun detach() {
        if (!registered) return
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }
        registered = false
        apply(false) // вернуть обычный маршрут
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val range = sensor?.maximumRange ?: 0f
        if (range <= 0f) return
        // бинарные датчики: 0 — у уха, maxRange — далеко; непрерывные
        // тоже попадают в сравнение «меньше предела»
        handleNearness(event.values.firstOrNull() ?: return < range)
    }

    // внутренняя точка для тестов: применить близость
    internal fun handleNearness(near: Boolean) {
        apply(near)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
