package chat.crypto

// 6-значный код сверки двух публичных ключей (анти-MITM); порядок ключей не важен
object SafetyCode {
    fun of(pubA: ByteArray, pubB: ByteArray): String {
        val (a, b) = if (less(pubA, pubB)) pubA to pubB else pubB to pubA
        val hash = CryptoPrimitives.sha256(a + b)
        var value = 0uL
        for (i in 0 until 4) value = (value shl 8) or hash[i].toUByte().toULong()
        return (value % 1_000_000uL).toString().padStart(6, '0')
    }

    // лексикографическое сравнение для детерминированного порядка ключей
    private fun less(a: ByteArray, b: ByteArray): Boolean {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            if (a[i] != b[i]) return a[i] < b[i]
        }
        return a.size < b.size
    }
}
