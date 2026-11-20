package chat.client.net

import androidx.annotation.StringRes
import chat.client.R
import java.io.IOException
import org.json.JSONObject

// Маппинг ошибок сервера/сети на понятные пользователю строки.
// Сырое тело ответа сервера наружу не показываем.
@StringRes
fun RelayException.userMessageRes(): Int = when (code) {
    400 -> R.string.err_bad_request
    401 -> R.string.err_unauthorized
    403 -> R.string.err_forbidden
    404 -> R.string.err_not_found
    409 -> R.string.err_conflict
    410 -> R.string.err_gone
    413 -> R.string.err_too_large
    429 -> R.string.err_rate_limited
    in 500..599 -> R.string.err_server
    else -> R.string.err_network
}

// типизированный код из тела ответа сервера («bad_code», «email_taken»):
// прежде маппинг шёл только по HTTP-коду и «неверный код» выглядел
// безликим «неверный запрос»
fun RelayException.typedCode(): String? = try {
    JSONObject(message ?: "").optString("error").ifEmpty { null }
} catch (_: Exception) {
    null
}

// А8: типизированные причины входа различаются ДО кода — иначе
// KeysUnavailable (401) выглядит как «неверный логин или пароль»,
// а KeysMismatch (409) — как безликий «конфликт»
@StringRes
fun errorMessageRes(error: Throwable): Int = when (error) {
    is KeysMismatchException -> R.string.err_keys_mismatch
    is KeysUnavailableException -> R.string.err_keys_unavailable
    is RelayException -> when (error.typedCode()) {
        "bad_code" -> R.string.error_bad_code
        "email_taken" -> R.string.error_email_taken
        "bad_password" -> R.string.error_bad_password
        "cloudpw_required" -> R.string.error_cloudpw_required
        "link_expired" -> R.string.error_link_expired
        "key_mismatch" -> R.string.error_key_mismatch
        "device_conflict" -> R.string.error_device_conflict
        "current_device" -> R.string.error_current_device
        "login_busy" -> R.string.error_login_busy
        "store_unavailable" -> R.string.error_store_unavailable
        else -> if (error.code == 401) R.string.err_token_expired else error.userMessageRes()
    }
    is IOException -> R.string.err_network
    else -> R.string.err_unknown
}
