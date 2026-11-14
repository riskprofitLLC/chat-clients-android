package chat.client.domain

import chat.crypto.CryptoPrimitives

// Стабильная ссылка на сообщение: sha256(content)[:8 hex-байт] + автор.
// Байты content идентичны на обеих сторонах (sealed box передаётся как есть),
// поэтому ref совпадает у отправителя и получателя — в отличие от ts.
// Используется для ответов, реакций и перехода к оригиналу.
// 64 бита (C2): при 32 битах на 20 000 сообщений коллизия ≈ 5%, и реакции/
// ответы резолвились по firstOrNull не в ту цель
object MessageRef {

    // 16 hex-символов (8 байт) — коллизии на объёме чата практически нет
    fun of(content: ByteArray): String =
        CryptoPrimitives.sha256(content).take(8)
            .joinToString("") { "%02x".format(it) }

    // сопоставление ссылки с записью (C2): новые — полные 16 hex,
    // старые 8-hex ссылки (до расширения) — по префиксу
    fun matches(ref: String, content: ByteArray): Boolean {
        val current = of(content)
        return if (ref.length == 8) current.startsWith(ref) else current == ref
    }

    // сопоставление двух ссылок МЕЖДУ СОБОЙ (C2): старая 8-hex против
    // новой 16-hex — по префиксу
    fun sameRef(a: String, b: String): Boolean = a == b ||
        (a.length == 8 && b.startsWith(a)) || (b.length == 8 && a.startsWith(b))

    // полная ссылка с автором: уникальна в пределах чата
    fun full(author: String, content: ByteArray): String = "$author/${of(content)}"
}
