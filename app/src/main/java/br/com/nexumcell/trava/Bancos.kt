package br.com.nexumcell.trava

import android.content.Context

/** Apps de banco e carteira que continuam abrindo com o aparelho bloqueado, para o cliente conseguir pagar. */
object Bancos {
    val LISTA = listOf(
        "com.nu.production" to "Nubank",
        "com.itau" to "Itaú",
        "com.bradesco" to "Bradesco",
        "br.com.bb.android" to "Banco do Brasil",
        "br.com.gabba.Caixa" to "Caixa",
        "br.gov.caixa.tem" to "Caixa Tem",
        "com.santander.app" to "Santander",
        "br.com.intermedium" to "Inter",
        "com.c6bank.app" to "C6 Bank",
        "com.picpay" to "PicPay",
        "com.mercadopago.wallet" to "Mercado Pago",
        "br.com.uol.ps.myaccount" to "PagBank",
        "br.com.bradesco.next" to "next",
        "br.com.neon" to "Neon",
        "br.com.sicoobnet" to "Sicoob",
        "br.com.sicredi.mobile" to "Sicredi",
        "com.bancopan.app" to "Banco PAN"
    )

    /** Lista fixa + pacotes extras que a loja mandar no contrato (campo appsPagamento). */
    fun pacotes(ctx: Context): List<String> {
        val extra = mutableListOf<String>()
        try { Store(ctx).cache()?.optJSONArray("appsPagamento")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.contains('.') }?.let { extra.add(it) } } } catch (e: Exception) { }
        return (LISTA.map { it.first } + extra).distinct()
    }

    /** Só os que estão instalados neste aparelho, com o nome que aparece no celular. */
    fun instalados(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return pacotes(ctx).mapNotNull { p ->
            try { pm.getLaunchIntentForPackage(p) ?: return@mapNotNull null
                p to (LISTA.firstOrNull { it.first == p }?.second ?: pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString())
            } catch (e: Exception) { null }
        }
    }
}
