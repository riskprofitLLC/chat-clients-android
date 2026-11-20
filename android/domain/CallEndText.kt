package chat.client.domain

import androidx.annotation.StringRes
import chat.client.R

// Причина завершения звонка → суффикс к «Звонок завершён…». Чистая функция:
// строки для busy/cancel/remote/net существовали, но не использовались —
// экран показывал одну безликую фразу на любой случай.
object CallEndText {

    @StringRes
    fun suffixRes(reason: CallStateMachine.EndReason?): Int = when (reason) {
        CallStateMachine.EndReason.REMOTE -> R.string.call_ended_remote
        CallStateMachine.EndReason.CANCELLED -> R.string.call_ended_cancel
        CallStateMachine.EndReason.BUSY -> R.string.call_ended_busy
        CallStateMachine.EndReason.NETWORK -> R.string.call_ended_net
        // OTHER и null — без суффикса: звонивший сам знает свою ошибку
        else -> 0
    }
}
