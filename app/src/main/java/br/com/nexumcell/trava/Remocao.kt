package br.com.nexumcell.trava

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Código de REMOÇÃO DEFINITIVA da trava (só a trava da NexumCell), sem internet.
 * A equipe (Dono/Suporte) gera no painel um código de 10 dígitos:
 *   HMAC-SHA256(segredo do aparelho, "REMOVER|dia") -> 10 dígitos.
 * É só deste aparelho (usa o segredo próprio dele, o mesmo do código de liberação),
 * vale no dia e no seguinte, uma vez só. Ao validar, o app deixa de ser administrador,
 * limpa todas as restrições e se desinstala. Não mexe em FRP nem em nada fora do app.
 * O painel usa a MESMA conta (função removocaoOffline no portal) — mude as duas juntas.
 */
object Remocao {
    private val FUSO: ZoneId = ZoneId.of("America/Sao_Paulo")

    fun calcular(segredo: String, dia: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(segredo.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val h = mac.doFinal("REMOVER|$dia".toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 5) v = (v shl 8) or (h[i].toLong() and 0xff)
        return "%010d".format((v and 0x7fffffffffffffffL) % 10_000_000_000L)
    }

    /** true se o código confere (hoje ou ontem) e ainda não foi usado neste aparelho. */
    fun confere(ctx: Context, digitado: String): Boolean {
        val st = Store(ctx)
        val cod = digitado.filter { it.isDigit() }
        if (cod.length != 10 || st.segredo.isEmpty()) return false
        val hoje = LocalDate.now(FUSO)
        val usados = st.codigosUsados.split(",").filter { it.isNotEmpty() }.toMutableList()
        for (dia in listOf(hoje, hoje.minusDays(1))) {
            val chave = "R:$dia"
            if (chave in usados) continue
            if (calcular(st.segredo, dia.toString()) == cod) {
                usados.add(chave)
                st.codigosUsados = usados.takeLast(200).joinToString(",")
                return true
            }
        }
        return false
    }

    /** Remove a trava da NexumCell deste aparelho e abre a desinstalação do app. */
    fun executar(ctx: Context) {
        Policy.removerTudo(ctx)
        Store(ctx).finalizado = true
        try {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) { }
    }
}
