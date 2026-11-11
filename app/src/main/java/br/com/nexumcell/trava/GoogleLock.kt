package br.com.nexumcell.trava

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.OutcomeReceiver
import android.util.Log

/**
 * Trava oficial do Android para aparelho financiado (Device Lock, Android 14+).
 * Só funciona quando o aparelho foi inscrito no programa de Device Lock do Google por um parceiro aprovado
 * e este app foi instalado por ele como o app "kiosk" do financiamento — aí o sistema concede a permissão
 * MANAGE_DEVICE_LOCK_STATE. Sem isso, nada aqui é chamado e a trava própria (Device Owner) continua valendo.
 */
object GoogleLock {
    private const val TAG = "NexumGoogleLock"
    private const val PERMISSAO = "android.permission.MANAGE_DEVICE_LOCK_STATE"

    /** "sem" = aparelho sem o recurso · "disponivel" = tem o recurso, mas não está inscrito · "ativo" = inscrito, este app controla. */
    fun status(ctx: Context): String = try {
        if (Build.VERSION.SDK_INT < 34 || !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_LOCK)) "sem"
        else if (ctx.checkSelfPermission(PERMISSAO) == PackageManager.PERMISSION_GRANTED) "ativo" else "disponivel"
    } catch (e: Throwable) { "sem" }

    private fun receptor(nome: String) = if (Build.VERSION.SDK_INT >= 34) object : OutcomeReceiver<Void, Exception> {
        override fun onResult(r: Void?) { Log.i(TAG, "$nome ok") }
        override fun onError(e: Exception) { Log.w(TAG, "$nome: ${e.message}") }
    } else null

    private fun gerente(ctx: Context) =
        if (Build.VERSION.SDK_INT >= 34 && status(ctx) == "ativo") ctx.getSystemService(android.devicelock.DeviceLockManager::class.java) else null

    fun travar(ctx: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        try { gerente(ctx)?.lockDevice(ctx.mainExecutor, receptor("lockDevice")!!) } catch (e: Throwable) { Log.w(TAG, "travar: ${e.message}") }
    }

    fun liberar(ctx: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        try { gerente(ctx)?.unlockDevice(ctx.mainExecutor, receptor("unlockDevice")!!) } catch (e: Throwable) { Log.w(TAG, "liberar: ${e.message}") }
    }

    /** Contrato quitado ou cancelado: devolve o aparelho de vez (o app perde o poder de travar). */
    fun encerrar(ctx: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            // o método entrou na API pública depois do Android 14: chamado por nome para compilar com o SDK 34
            val g = gerente(ctx) ?: return
            g.javaClass.getMethod("clearDeviceRestrictions", java.util.concurrent.Executor::class.java, OutcomeReceiver::class.java)
                .invoke(g, ctx.mainExecutor, receptor("clearDeviceRestrictions"))
        } catch (e: Throwable) { Log.w(TAG, "encerrar: ${e.message}") }
    }
}
