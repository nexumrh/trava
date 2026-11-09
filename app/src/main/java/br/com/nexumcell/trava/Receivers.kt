package br.com.nexumcell.trava

import android.annotation.TargetApi
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log

@Suppress("DEPRECATION")
internal fun Intent.adminExtras(): PersistableBundle? = try {
    getParcelableExtra<PersistableBundle>(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
} catch (e: Exception) { null }

object Setup {
    /** Chamado ao final da instalação por QR code. Nunca pode lançar exceção. */
    fun concluir(ctx: Context, extras: PersistableBundle?) {
        try {
            val st = Store(ctx)
            st.salvarExtras(extras)
            if (st.ultimoSyncOk == 0L) st.ultimoSyncOk = System.currentTimeMillis()
            Policy.aplicarBase(ctx)
            Agendador.agendar(ctx)
            Agendador.agora(ctx)
        } catch (e: Throwable) {
            Log.e("NexumSetup", "falha no setup", e)
        }
    }
}

class AdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Setup.concluir(context, intent.adminExtras())
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Setup.concluir(context, intent.adminExtras())
    }
}

/** Android 10+: o assistente de configuração pergunta qual modo usar → aparelho totalmente gerenciado. */
@TargetApi(29)
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store(this).salvarExtras(intent.adminExtras())
        val res = Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        setResult(RESULT_OK, res)
        finish()
    }
}

/** Android 10+: último passo da instalação; aplica as políticas. */
@TargetApi(29)
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Setup.concluir(this, intent.adminExtras())
        setResult(RESULT_OK)
        finish()
    }
}

/** Mudança de chip/operadora: sincroniza na hora (para avisar o painel ou travar). */
class ChipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store(context).finalizado) return
        Agendador.agora(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val st = Store(context)
        if (st.finalizado) return
        Policy.aplicarBase(context)
        if (st.travado) Policy.abrirTela(context)
        Agendador.agendar(context)
        Agendador.agora(context)
    }
}
