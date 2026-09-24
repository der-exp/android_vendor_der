// Процесс приложения. Здесь живёт Shell (одна на процесс, см. Shell.kt) и то, что должно
// случиться при любом запуске процесса — открыли ли экран или система подняла задание
// JobScheduler:
//   - private_dns_default_mode = off, один раз (PrivateDns.ensureDefaultOff);
//   - сверка Private DNS с выключателем движка: если движок выключили не из приложения
//     (adb, сброс свойства), при следующем запуске Private DNS человека вернётся;
//   - ежесуточное задание обновления поставлено, и его работа (logic.Background) говорит с
//     движком и сетью через те же EngineClient и HttpClient, что и экран: со сроками команд,
//     повтором busy и пределом размера ответа.
// Записи настроек — в пуле, чтобы не задерживать первый кадр экрана вызовами в SettingsProvider.
package com.der.splify2

import android.app.Application
import com.der.splify2.logic.Background

class Splify2App : Application() {

    lateinit var shell: Shell
        private set

    override fun onCreate() {
        super.onCreate()
        shell = Shell(this)
        Background.engineFactory = { LogicEngine(shell.engine) }
        Background.httpFactory = { HttpClient(it) }
        shell.pool.execute {
            shell.privateDns.ensureDefaultOff()
            shell.privateDns.reconcile()
        }
        UpdateJobService.ensureScheduled(this)
    }
}
