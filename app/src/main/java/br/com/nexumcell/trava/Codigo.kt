package br.com.nexumcell.trava

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Código de liberação sem internet.
 * A loja gera no painel um código de 6 dígitos: HMAC-SHA256(segredo do aparelho, "NXC|dia|n").
 * Vale no dia em que foi gerado e no dia seguinte, uma vez só, e só neste aparelho.
 * O painel usa a MESMA conta (função codigoOffline no portal) — se mudar uma, mude a outra.
 */
object Codigo {
    private const val MAX_N = 50
    private const val MAX_ERROS = 5
    private const val ESPERA_MS = 30 * 60 * 1000L
    private val FUSO: ZoneId = ZoneId.of("America/Sao_Paulo")

    fun calcular(segredo: String, dia: String, n: Int): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(segredo.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val h = mac.doFinal("NXC|$dia|$n".toByteArray(Charsets.UTF_8))
        val v = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return "%06d".format(v % 1_000_000L)
    }

    /** Devolve null se liberou, ou a mensagem de erro. */
    fun validar(ctx: Context, digitado: String): String? {
        val st = Store(ctx)
        val agora = System.currentTimeMillis()
        if (st.codigoBloqueadoAte > agora) {
            val min = ((st.codigoBloqueadoAte - agora) / 60000) + 1
            return I18n.t("Muitas tentativas erradas. Tente de novo em") + " $min min."
        }
        val cod = digitado.filter { it.isDigit() }
        if (cod.length != 6) return I18n.t("O código tem 6 números.")
        val hoje = LocalDate.now(FUSO)
        val usados = st.codigosUsados.split(",").filter { it.isNotEmpty() }.toMutableList()
        for (dia in listOf(hoje, hoje.minusDays(1))) {
            for (n in 0 until MAX_N) {
                val chave = "$dia:$n"
                if (chave in usados) continue
                if (calcular(st.segredo, dia.toString(), n) == cod) {
                    usados.add(chave)
                    st.codigosUsados = usados.takeLast(200).joinToString(",")
                    st.errosCodigo = 0
                    val horas = st.cache()?.optLong("codigoHoras", 48L)?.coerceIn(1L, 240L) ?: 48L
                    st.liberadoLocalAte = agora + horas * 3_600_000L
                    return null
                }
            }
        }
        st.errosCodigo = st.errosCodigo + 1
        if (st.errosCodigo >= MAX_ERROS) { st.codigoBloqueadoAte = agora + ESPERA_MS; st.errosCodigo = 0 }
        return I18n.t("Código inválido ou já usado. Peça um código novo à loja.")
    }
}
