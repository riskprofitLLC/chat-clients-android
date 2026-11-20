package chat.client.domain

// Дедупликация контактов адресной книги на этапе показа: облачная
// синхронизация возвращает физически удалённые дубли, поэтому в списке
// показываем один контакт на кластер, а все номера/e-mail собираем из
// всех дублей кластера (ничего не теряется).
//
// ВАЖНО: имя — слабый сигнал и само по себе кластер НЕ образует (два разных
// «Иван»/«Анна» не склеиваются). Кластер образуется только по сильному
// сигналу (нормализованный телефон или e-mail); имя лишь присоединяет
// запись к существующему кластеру при дополнительном совпадении.
object ContactDedup {

    data class Entry(
        val key: String,       // уникальный ключ записи (rawId)
        val name: String,
        val phones: List<String>,
        val email: String,
    )

    data class Merged(
        val key: String,
        val name: String,
        val phone: String,        // первый номер
        val phones: List<String>, // все номера кластера
        val email: String,
        val emails: List<String> = emptyList(), // все e-mail кластера
    )

    // нормализация номера: только цифры, последние 10 (фолбэк для E164 —
    // без libphonenumber;.platformенный PhoneNumberUtils требует Context)
    fun normPhone(p: String): String = p.filter { it.isDigit() }.takeLast(10)

    // нормализация имени: регистр и небуквенные символы
    fun normName(n: String): String = n.lowercase().filter { it.isLetterOrDigit() }

    // один алгоритм кластеризации (union-find); strongOnly=false добавляет
    // слабый сигнал (имя) — только для присоединения к существующему кластеру
    private fun unionFind(entries: List<Entry>, useName: Boolean): Map<String, String> {
        val parent = entries.map { it.key to it.key }.toMap().toMutableMap()
        fun find(k: String): String {
            var r = k
            while (parent[r] != r) r = parent[r]!!
            var c = k
            while (parent[c] != c) { val n = parent[c]!!; parent[c] = r; c = n }
            return r
        }
        fun union(a: String, b: String) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        val byPhone = mutableMapOf<String, String>()
        val byEmail = mutableMapOf<String, String>()
        val byName = mutableMapOf<String, String>()
        for (e in entries) {
            for (p in e.phones) {
                val n = normPhone(p)
                if (n.length >= 7) {
                    val prev = byPhone[n]
                    if (prev != null) union(prev, e.key) else byPhone[n] = e.key
                }
            }
            val em = e.email.lowercase()
            if (em.contains("@")) {
                val prev = byEmail[em]
                if (prev != null) union(prev, e.key) else byEmail[em] = e.key
            }
            if (useName) {
                val nm = normName(e.name)
                if (nm.length >= 4) {
                    val prev = byName[nm]
                    // имя склеивает только если у записи уже есть сильный сигнал,
                    // иначе два одноимённых человека останутся раздельно
                    val hasStrong = e.phones.any { normPhone(it).length >= 7 } || em.contains("@")
                    if (prev != null && hasStrong) union(prev, e.key) else byName.putIfAbsent(nm, e.key)
                }
            }
        }
        return parent
    }

    // кластеры дублей (только сильные сигналы: телефон / e-mail)
    fun clusters(entries: List<Entry>): List<List<Entry>> {
        val parent = unionFind(entries, useName = false)
        fun find(k: String): String {
            var r = k
            while (parent[r] != r) r = parent[r]!!
            return r
        }
        return entries.groupBy { find(it.key) }.values
            .filter { it.size > 1 }
            .map { it.sortedBy { e -> e.name.lowercase() } }
    }

    // дедуп для отображения: один Merged на кластер со всеми данными;
    // «главная» запись — с наибольшим числом полей (имя+телефоны+e-mail).
    // Имя кластер не образует (слабый сигнал) — только сильные сигналы
    fun dedupForDisplay(entries: List<Entry>): List<Merged> {
        val parent = unionFind(entries, useName = false)
        fun find(k: String): String {
            var r = k
            while (parent[r] != r) r = parent[r]!!
            return r
        }
        return entries.groupBy { find(it.key) }.values.map { cluster ->
            val main = cluster.maxByOrNull { e ->
                (if (e.name.isNotBlank()) 1 else 0) + e.phones.size + (if (e.email.contains("@")) 1 else 0)
            }!!
            val allPhones = cluster.flatMap { it.phones }.distinct()
            val allEmails = cluster.map { it.email.lowercase() }.filter { it.isNotBlank() }.distinct()
            Merged(
                key = main.key,
                name = main.name,
                phone = allPhones.firstOrNull() ?: "",
                phones = allPhones,
                email = allEmails.firstOrNull() ?: "",
                emails = allEmails,
            )
        }
    }
}
