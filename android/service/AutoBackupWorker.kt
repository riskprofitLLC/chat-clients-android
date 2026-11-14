package chat.client.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import chat.client.ChatApplication
import chat.client.R
import chat.client.data.AppContainer
import chat.client.domain.AutoBackup
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// AI5 (аналог резервного копирования Telegram): зашифрованная копия
// журнала по расписанию — WorkManager будит раз в сутки/неделю, файл
// пишется в SAF-папку пользователя (persistable URI), лишние копии
// ротируются (старейшие сверх KEEP удаляются). Пароль хранится
// Keystore-шифрованным — файл копии сам по себе не читается.
class AutoBackupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? ChatApplication)?.container ?: return Result.success()
        val app = applicationContext
        // сессии может не быть (процесс убит) — поднимаем с диска
        if (container.currentSession() == null) {
            try {
                container.sessionRepo.restoreSession()
            } catch (_: Exception) {
            }
        }
        val s = container.currentSession()
        val mode = container.appPrefs.autoBackupMode()
        val tree = container.appPrefs.autoBackupTreeUri()
        val password = container.appPrefs.autoBackupPassword()
        if (mode == AutoBackup.MODE_OFF || s == null) return Result.success()
        if (tree.isNullOrEmpty() || password.isNullOrEmpty()) {
            container.appPrefs.setAutoBackupLastError("не настроено: папка или пароль")
            return Result.success()
        }
        try {
            run(app, container, Uri.parse(tree), password)
            container.appPrefs.setAutoBackupLastAt(System.currentTimeMillis())
            container.appPrefs.setAutoBackupLastError(null)
            return Result.success()
        } catch (_: Exception) {
            // сырой текст исключения наружу не показываем — только общая фраза
            container.appPrefs.setAutoBackupLastError(app.getString(R.string.autobackup_failed))
            return Result.success() // следующий период попробует снова
        }
    }

    companion object {
        private const val WORK = "chat-autobackup"

        // снять расписание совсем (wipe аккаунта — иначе задача переживёт выход)
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }

        // пересписание задачи по настройке: 0 — снять
        fun schedule(context: Context, mode: Int) {
            if (mode == AutoBackup.MODE_OFF) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(mode.toLong(), TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        // один прогон воркера: экспорт → файл в SAF → ротация. Отдельно —
        // для инструментального теста без WorkManager
        fun run(context: Context, container: AppContainer, tree: Uri, password: String): Long {
            val s = container.currentSession() ?: throw IllegalStateException("нет сессии")
            // A5: секрет идентичности в автобэкап только по явному согласию
            // и при достаточно длинном пароле (>= 12 символов)
            val identity = if (chat.client.domain.AutoBackup.includeIdentityAllowed(
                    container.appPrefs.autoBackupIncludeIdentity(), password,
                )
            ) {
                try {
                    container.sessionRepo.exportIdentityOrNull()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val buffer = ByteArrayOutputStream()
            val count = chat.client.data.Backup.export(
                s.store, password, buffer,
                identity?.first, identity?.second, identity?.third,
            )
            val resolver = context.contentResolver
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree),
            )
            val name = AutoBackup.fileName(System.currentTimeMillis())
            val created = DocumentsContract.createDocument(
                resolver, rootDoc, "application/octet-stream", name,
            ) ?: throw IllegalStateException("папка недоступна")
            resolver.openOutputStream(created)?.use { out ->
                out.write(buffer.toByteArray())
                out.flush()
            } ?: throw IllegalStateException("файл не открыт")
            rotate(resolver, tree, rootDoc)
            return count.toLong()
        }

        // ротация: старейшие копии сверх KEEP удаляются (имя несёт
        // метку фиксированной ширины — сортировка по имени = по времени)
        private fun rotate(resolver: android.content.ContentResolver, tree: Uri, rootDoc: Uri) {
            val children = ArrayList<Pair<String, String>>()
            resolver.query(
                rootDoc,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) children += cursor.getString(0) to cursor.getString(1)
            }
            for (victim in AutoBackup.toDelete(children.map { it.second })) {
                val id = children.firstOrNull { it.second == victim }?.first ?: continue
                try {
                    DocumentsContract.deleteDocument(
                        resolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}
