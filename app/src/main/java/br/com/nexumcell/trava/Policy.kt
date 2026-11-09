package br.com.nexumcell.trava

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.telecom.TelecomManager
import android.util.Log
import org.json.JSONObject

/** Tudo que depende de ser Device Owner fica aqui. */
object Policy {
    private const val TAG = "NexumPolicy"

    private val RESTRICOES = listOf(
        UserManager.DISALLOW_FACTORY_RESET,      // não restaura de fábrica pelas Configurações
        UserManager.DISALLOW_SAFE_BOOT,          // não entra em modo de segurança
        UserManager.DISALLOW_DEBUGGING_FEATURES, // sem opções de desenvolvedor / ADB
        UserManager.DISALLOW_ADD_USER,           // não cria outro usuário
        UserManager.DISALLOW_CONFIG_DATE_TIME    // não volta a data para "fugir" do vencimento
    )

    fun dpm(ctx: Context): DevicePolicyManager = ctx.getSystemService(DevicePolicyManager::class.java)
    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    fun isOwner(ctx: Context): Boolean = try { dpm(ctx).isDeviceOwnerApp(ctx.packageName) } catch (e: Exception) { false }

    fun adminAtivo(ctx: Context): Boolean = try { dpm(ctx).isAdminActive(admin(ctx)) } catch (e: Exception) { false }
    fun sobreposicao(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(ctx)
    /** Instalado por link: precisa do administrador comum ativo. */
    fun linkOk(ctx: Context): Boolean = !isOwner(ctx) && Store(ctx).modoLink && adminAtivo(ctx)
    fun modo(ctx: Context): String = if (isOwner(ctx)) "qr" else if (Store(ctx).modoLink) "link" else "nenhum"

    private inline fun tenta(nome: String, f: () -> Unit) {
        try { f() } catch (e: Throwable) { Log.w(TAG, "$nome: ${e.message}") }
    }

    /** Aplicado uma vez, logo após a instalação pelo QR code (e reaplicado a cada boot). */
    fun aplicarBase(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx); val a = admin(ctx)
        RESTRICOES.forEach { r -> tenta("restricao $r") { dpm.addUserRestriction(a, r) } }
        tenta("uninstallBlocked") { dpm.setUninstallBlocked(a, ctx.packageName, true) }
        tenta("perm phone") {
            dpm.setPermissionGrantState(a, ctx.packageName, Manifest.permission.READ_PHONE_STATE, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        if (Build.VERSION.SDK_INT >= 33) tenta("perm notif") {
            dpm.setPermissionGrantState(a, ctx.packageName, Manifest.permission.POST_NOTIFICATIONS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            dpm.setPermissionGrantState(a, ctx.packageName, Manifest.permission.ACCESS_FINE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            dpm.setPermissionGrantState(a, ctx.packageName, Manifest.permission.ACCESS_COARSE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        if (Build.VERSION.SDK_INT >= 30) tenta("autoTime") { dpm.setAutoTimeEnabled(a, true) }
        else tenta("autoTimeRequired") { @Suppress("DEPRECATION") dpm.setAutoTimeRequired(a, true) }
        tenta("lockTaskPackages") { dpm.setLockTaskPackages(a, pacotesPermitidos(ctx)) }
        if (Build.VERSION.SDK_INT >= 28) tenta("lockTaskFeatures") {
            // Permite menu de desligar e a tela de bloqueio (senha) do próprio Android.
            dpm.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD)
        }
    }

    /** App próprio + discador/chamadas, para manter ligações de emergência e para a loja. */
    private fun pacotesPermitidos(ctx: Context): Array<String> {
        val l = mutableListOf(ctx.packageName)
        try { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { l.add(it) } } catch (e: Exception) {}
        l.addAll(listOf(
            "com.android.server.telecom", "com.android.phone", "com.android.dialer", "com.android.incallui",
            "com.google.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui",
            "com.android.emergency", "com.motorola.dialer", "com.miui.securitycenter"
        ))
        return l.distinct().toTypedArray()
    }

    fun travar(ctx: Context, motivo: String) {
        val st = Store(ctx)
        st.motivo = motivo
        st.travado = true
        if (isOwner(ctx)) {
            val dpm = dpm(ctx); val a = admin(ctx)
            tenta("lockTaskPackages") { dpm.setLockTaskPackages(a, pacotesPermitidos(ctx)) }
            setHome(ctx, true)
            tenta("preferredHome") {
                val f = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(a, f, ComponentName(ctx.packageName, "${ctx.packageName}.LockHome"))
            }
            tenta("statusBar") { dpm.setStatusBarDisabled(a, true) }
        }
        abrirTela(ctx)
    }

    fun liberar(ctx: Context) {
        val st = Store(ctx)
        st.travado = false
        st.motivo = ""
        if (isOwner(ctx)) {
            val dpm = dpm(ctx); val a = admin(ctx)
            tenta("clearPreferred") { dpm.clearPackagePersistentPreferredActivities(a, ctx.packageName) }
            tenta("statusBar") { dpm.setStatusBarDisabled(a, false) }
        }
        setHome(ctx, false)
        // A LockActivity percebe travado=false em até 3 s e se fecha (stopLockTask).
    }

    fun abrirTela(ctx: Context) {
        tenta("abrirTela") {
            ctx.startActivity(Intent(ctx, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun setHome(ctx: Context, on: Boolean) {
        tenta("alias") {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx.packageName, "${ctx.packageName}.LockHome"),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    /**
     * Proteção contra reset pelo recovery (botões): após formatar, o aparelho só passa
     * da tela inicial com uma das contas Google da loja. "contas" = IDs numéricos das contas.
     */
    fun aplicarFrp(ctx: Context, contas: List<String>) {
        if (!isOwner(ctx)) return
        val st = Store(ctx)
        val chave = contas.joinToString(",")
        if (chave == st.frpAplicado) return
        val dpm = dpm(ctx); val a = admin(ctx)
        if (Build.VERSION.SDK_INT >= 30) tenta("frpPolicy") {
            val pol = if (contas.isEmpty()) null else FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(contas)
                .setFactoryResetProtectionEnabled(true)
                .build()
            dpm.setFactoryResetProtectionPolicy(a, pol)
        }
        tenta("frpLegacy") {
            val b = Bundle()
            if (contas.isNotEmpty()) b.putStringArray("factoryResetProtectionAdmin", contas.toTypedArray())
            dpm.setApplicationRestrictions(a, "com.google.android.gms", b)
            ctx.sendBroadcast(Intent("com.google.android.gms.auth.FRP_CONFIG_CHANGED")
                .setPackage("com.google.android.gms")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
        }
        st.frpAplicado = chave
    }

    /** Redes sociais, vídeo e jogos populares: bloqueados na etapa "funções limitadas". WhatsApp, banco e ligações continuam. */
    private val APPS_PARCIAL = listOf(
        "com.instagram.android", "com.instagram.lite", "com.facebook.katana", "com.facebook.lite",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
        "com.kwai.video", "com.kwai.bulldog", "com.google.android.youtube", "com.netflix.mediaclient",
        "com.twitter.android", "com.snapchat.android", "com.pinterest", "com.spotify.music",
        "com.dts.freefireth", "com.dts.freefiremax", "com.roblox.client", "com.tencent.ig", "com.supercell.clashroyale"
    )

    fun parcial(ctx: Context, on: Boolean, d: JSONObject?) {
        if (!isOwner(ctx) || Build.VERSION.SDK_INT < 24) return
        val st = Store(ctx); val dpm = dpm(ctx); val a = admin(ctx)
        val antes = st.suspensos.split(",").filter { it.isNotEmpty() }
        if (!on) {
            if (antes.isNotEmpty()) tenta("unsuspend") { dpm.setPackagesSuspended(a, antes.toTypedArray(), false) }
            st.suspensos = ""; return
        }
        val extras = d?.optString("appsParcial").orEmpty().split(",", ";", " ", "\n").map { it.trim() }.filter { it.contains('.') }
        val instalados = (APPS_PARCIAL + extras).distinct().filter { pkg ->
            pkg != ctx.packageName && try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
        }
        val novos = instalados.toSet()
        val soltar = antes.filter { it !in novos }
        if (soltar.isNotEmpty()) tenta("unsuspend") { dpm.setPackagesSuspended(a, soltar.toTypedArray(), false) }
        if (instalados.isNotEmpty()) tenta("suspend") { dpm.setPackagesSuspended(a, instalados.toTypedArray(), true) }
        st.suspensos = instalados.joinToString(",")
    }

    /**
     * Proteções extras contra burlar a trava. Cada uma pode ser desligada pelo Dono no painel
     * (campos bloquearAviao, bloquearVpn, bloquearFontes, bloquearUsb no documento do aparelho; padrão "sim").
     */
    @Volatile var protecoesAtivas: String = ""

    private fun extras(d: JSONObject?): List<Pair<String, Boolean>> {
        val on = { k: String -> d != null && d.optString(k, "sim") != "nao" }
        val l = mutableListOf<Pair<String, Boolean>>()
        if (Build.VERSION.SDK_INT >= 28) l.add(UserManager.DISALLOW_AIRPLANE_MODE to on("bloquearAviao"))
        l.add(UserManager.DISALLOW_CONFIG_VPN to on("bloquearVpn"))
        l.add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES to on("bloquearFontes"))
        if (Build.VERSION.SDK_INT >= 29) l.add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY to on("bloquearFontes"))
        l.add(UserManager.DISALLOW_USB_FILE_TRANSFER to on("bloquearUsb"))
        return l
    }

    fun protecoes(ctx: Context, d: JSONObject?) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx); val a = admin(ctx)
        // não prende o cliente em modo avião: só bloqueia o botão quando o modo avião estiver desligado
        val aviaoLigado = try { android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1 } catch (e: Exception) { false }
        val ativas = mutableListOf<String>()
        extras(d).forEach { (r, ligar) ->
            val efetivo = ligar && !(r == UserManager.DISALLOW_AIRPLANE_MODE && aviaoLigado)
            tenta("extra $r") { if (efetivo) dpm.addUserRestriction(a, r) else dpm.clearUserRestriction(a, r) }
            if (efetivo) ativas.add(r.removePrefix("no_"))
        }
        protecoesAtivas = ativas.joinToString(",")
    }

    /** Contrato quitado/cancelado: remove tudo e deixa de ser administrador. */
    fun removerTudo(ctx: Context) {
        val st = Store(ctx)
        liberar(ctx)
        parcial(ctx, false, null)
        Agendador.cancelar(ctx)
        if (isOwner(ctx)) {
            val dpm = dpm(ctx); val a = admin(ctx)
            RESTRICOES.forEach { r -> tenta("clear $r") { dpm.clearUserRestriction(a, r) } }
            extras(null).forEach { (r, _) -> tenta("clear $r") { dpm.clearUserRestriction(a, r) } }
            st.frpAplicado = "x" // força a limpeza
            aplicarFrp(ctx, emptyList())
            tenta("lockTaskPackages") { dpm.setLockTaskPackages(a, arrayOf()) }
            tenta("uninstallBlocked") { dpm.setUninstallBlocked(a, ctx.packageName, false) }
            tenta("clearOwner") { @Suppress("DEPRECATION") dpm.clearDeviceOwnerApp(ctx.packageName) }
        }
        st.finalizado = true
    }

    fun aplicar(ctx: Context, d: JSONObject, r: Resultado) {
        if (r.final) { removerTudo(ctx); return }
        val contas = d.optString("frpContas").split(",", ";", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        aplicarFrp(ctx, contas)
        protecoes(ctx, d)
        parcial(ctx, r.nivel == 2, d)
        // instalado por link e o cliente desativou o administrador: bloqueia até a loja reativar
        val semAdmin = !isOwner(ctx) && Store(ctx).modoLink && Store(ctx).linkAtivadoEm > 0 && !adminAtivo(ctx)
        if (r.travar || semAdmin) travar(ctx, if (semAdmin && !r.travar) "A proteção do aparelho foi desativada. Abra o app NexumCell e ative de novo, ou procure a loja." else r.motivo) else liberar(ctx)
        try { Notificador.estado(ctx, r, d) } catch (e: Exception) { }
    }
}
