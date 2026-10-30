package chat.client.net

import androidx.annotation.StringRes
import chat.client.R
import java.io.IOException

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

// А8: типизированные причины входа различаются ДО кода — иначе
// KeysUnavailable (401) выглядит как «неверный логин или пароль»,
// а KeysMismatch (409) — как безликий «конфликт»
@StringRes
fun errorMessageRes(error: Throwable): Int = when (error) {
    is KeysMismatchException -> R.string.err_keys_mismatch
    is KeysUnavailableException -> R.string.err_keys_unavailable
    is RelayException -> error.userMessageRes()
    is IOException -> R.string.err_network
    else -> R.string.err_unknown
}
