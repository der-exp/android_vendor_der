// Ежесуточное обновление списков и подписок в фоне.
//
// Требование батареи (ANDROID_AGENT_TASK.md §0, BRIDGE.md «Батарея»): ничего по будильнику, ни
// постоянного сервиса, ни wakelock. JobScheduler — единственный фоновый вход приложения: система
// сама выбирает момент, когда телефон и так не спит и сеть есть, складывает наше задание с
// заданиями других приложений в одно окно и держит wakelock ровно на время работы.
//
// Условия задания:
//   - период сутки, окно 6 часов — система может сдвинуть запуск в удобный момент;
//   - нужна сеть; «не на лимитной сети» — по настройке модели (update.unmetered_only, по
//     умолчанию да), которую Shell передаёт в schedule(unmeteredOnly) после каждого её сохранения;
//   - батарея не на исходе;
//   - переживает перезагрузку (setPersisted — отсюда RECEIVE_BOOT_COMPLETED в манифесте, своего
//     получателя загрузки у приложения нет).
//
// Саму работу делает логика (logic.Background.runDaily): что обновлять, применять ли спеку.
// Служба только даёт ей поток — onStartJob зовётся на главном потоке — и сообщает системе,
// что всё. Неудача не повторяется раньше срока: следующий день и так придёт, а повтор с
// нарастающей паузой будил бы телефон ради того же отказа.
package com.der.splify2

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.util.Log
import com.der.splify2.logic.Background

class UpdateJobService : JobService() {

    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val t = Thread({
            try {
                Background.runDaily(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "фоновое обновление не удалось", e)
            } finally {
                worker = null
                jobFinished(params, false)
            }
        }, "splify2-daily")
        worker = t
        t.start()
        return true
    }

    /**
     * Система забирает время (пропала сеть, телефон уходит в сон). Прервать логику посреди
     * записи файла нельзя без её участия — поток получает interrupt, а результат незаконченного
     * прохода просто не применяется. Раньше срока не повторяем (см. шапку).
     */
    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return false
    }

    companion object {
        private const val TAG = "splify2.job"
        private const val JOB_ID = 1
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val FLEX_MS = 6L * 60 * 60 * 1000
        private const val PREF_UNMETERED = "job.unmetered_only"

        /**
         * Поставить задание, если его ещё нет или сменилось условие сети. Зовётся при каждом
         * запуске процесса — это дёшево: JobScheduler отвечает из памяти.
         */
        fun ensureScheduled(ctx: Context) {
            // Умолчание — как у модели (Model.updateUnmeteredOnly): только без лимитной сети.
            // Сама настройка живёт в модели логики; здесь — её копия, чтобы не читать файлы
            // логики на главном потоке при запуске процесса (её обновляет Shell после
            // settings.put и backup.import).
            val unmetered = ctx.getSharedPreferences("shell", Context.MODE_PRIVATE)
                .getBoolean(PREF_UNMETERED, true)
            schedule(ctx, unmetered, force = false)
        }

        /** Сменить условие «только не на лимитной сети» и переставить задание. */
        fun schedule(ctx: Context, unmeteredOnly: Boolean, force: Boolean = true) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            ctx.getSharedPreferences("shell", Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_UNMETERED, unmeteredOnly).apply()
            val net = if (unmeteredOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
            val pending = js.getPendingJob(JOB_ID)
            val pendingUnmetered = pending?.requiredNetwork
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (!force && pending != null && pendingUnmetered == unmeteredOnly) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJobService::class.java))
                .setPeriodic(DAY_MS, FLEX_MS)
                .setRequiredNetworkType(net)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            if (js.schedule(info) != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "задание не поставлено")
        }
    }
}
