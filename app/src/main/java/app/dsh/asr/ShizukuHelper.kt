package app.dsh.asr

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 整合（v0.4.0）—— 用於「一鍵設為系統預設識別服務」。
 *
 * ## 為什麼需要 Shizuku
 * 系統預設識別服務存在 `Settings.Secure.voice_recognition_service` 這個鍵裡，
 * 寫入它需要 `WRITE_SECURE_SETTINGS` 許可權 —— **普通應用申請不到**（只有系統應用/root/adb 有）。
 *
 * 主人要求「嘗試申請一下 SHZ 許可權來自動設」，這正是 Shizuku 的用途：
 * 它讓應用能以 **adb(uid 2000)** 身份執行 shell，從而
 * `settings put secure voice_recognition_service <元件>`。
 *
 * ## 三級回退（本類只負責第 ② 級，③ 由呼叫方處理）
 *  ① 直接寫 —— 已 root 或系統簽名時可用（`SelfCheck.trySetAsDefault` 裡做）
 *  ② **Shizuku**（本類）—— 使用者裝了 Shizuku 並授權後可用
 *  ③ 都不行 → 提示使用者改用「呼叫方直接指定服務」（DSH 已支援）
 *
 * ## 使用前提（需向使用者說明）
 * 手機需裝 [Shizuku](https://shizuku.rikka.app/) 並按它的指引啟用（root 或無線除錯）。
 * 未安裝/未授權時本類會明確返回原因，不靜默失敗。
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    private const val REQ_CODE = 4001

    /** Shizuku 是否已安裝（Manager 包存在） */
    fun isInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    /** 是否已獲得授權 */
    fun isAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 申請 Shizuku 許可權（會彈授權框）。結果透過 [onResult] 回撥（主執行緒）。
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isInstalledGlobal) { onResult(false); return }
        runCatching {
            if (isAuthorized()) { onResult(true); return }
            Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == REQ_CODE) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            })
            Shizuku.requestPermission(REQ_CODE)
        }.onFailure {
            Log.w(TAG, "requestPermission failed: ${it.message}")
            onResult(false)
        }
    }

    /** isInstalled 的無 Context 版本（requestPermission 裡用） */
    @Volatile private var isInstalledGlobal = false
    fun markInstalled(v: Boolean) { isInstalledGlobal = v }

    /**
     * 以 Shizuku（adb 身份）執行 `settings put secure voice_recognition_service <spec>`。
     * @return true = 執行成功且已生效
     */
    fun setDefaultViaShizuku(ctx: Context, componentSpec: String): Boolean {
        if (!isAuthorized()) {
            Log.w(TAG, "not authorized")
            return false
        }
        val cmd = "settings put secure voice_recognition_service $componentSpec"
        val ok = runCatching {
            // 官方鏈路（照 dsh-android 的 Privilege.shizukuExec 實現）：
            //   Shizuku.getBinder() → IShizukuService.Stub.asInterface →
            //   newProcess(cmd, env, dir) → IRemoteProcess 的 ParcelFileDescriptor 讀輸出。
            // ⚠️ 不能用 Shizuku.newProcess —— 它在 13.1.5 裡是 private（編譯報錯實測）。
            val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(Shizuku.getBinder())
                ?: return@runCatching false
            val rp = svc.newProcess(
                arrayOf("sh", "-c", cmd),
                arrayOf("PATH=/system/bin:/system/xbin"),
                null,
            )
            val out = rp.inputStream.let { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    .bufferedReader().readText()
            }
            val err = rp.errorStream.let { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    .bufferedReader().readText()
            }
            val code = rp.waitFor()
            Log.i(TAG, "shizuku exec rc=$code out=$out err=$err")
            code == 0
        }.getOrElse {
            Log.w(TAG, "shizuku exec failed: ${it.message}")
            false
        }
        // 驗證是否真的生效（寫成功但值沒變也算失敗）
        val applied = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
                .orEmpty().startsWith(ctx.packageName)
        }.getOrDefault(false)
        return ok && applied
    }
}
