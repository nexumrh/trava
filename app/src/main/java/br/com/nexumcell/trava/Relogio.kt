package br.com.nexumcell.trava

import android.content.Context

/**
 * Relógio confiável: impede "fugir" do vencimento voltando a data do celular.
 * - Online: usa a hora do servidor (cabeçalho Date da resposta) e mede a diferença para o relógio do aparelho.
 * - Offline: a hora nunca anda para trás — vale a maior hora já vista.
 * Diferença maior que a tolerância é informada no check-in (o painel avisa a equipe).
 */
object Relogio {
    private const val TOLERANCIA = 6 * 60 * 60 * 1000L   // 6 h: cobre fuso e horário de verão

    /** Chamado pela Api a cada resposta do servidor. */
    fun servidor(ctx: Context, horaServidor: Long) {
        if (horaServidor <= 0L) return
        val st = Store(ctx)
        st.relogioDif = horaServidor - System.currentTimeMillis()
        st.relogioMax = horaServidor
    }

    /** Hora a usar nas decisões da trava. */
    fun agora(ctx: Context): Long {
        val st = Store(ctx)
        val aparelho = System.currentTimeMillis()
        // relógio do aparelho atrasado em relação ao servidor: corrige pela diferença medida
        val corrigido = if (kotlin.math.abs(st.relogioDif) > TOLERANCIA) aparelho + st.relogioDif else aparelho
        val max = st.relogioMax
        if (corrigido < max - TOLERANCIA) { st.relogioVoltouEm = max; return max }   // voltaram a data sem internet
        if (corrigido > max) st.relogioMax = corrigido
        return maxOf(corrigido, max - TOLERANCIA)
    }

    fun adulterado(ctx: Context): Boolean {
        val st = Store(ctx)
        return kotlin.math.abs(st.relogioDif) > TOLERANCIA || System.currentTimeMillis() < st.relogioMax - TOLERANCIA
    }

    fun info(ctx: Context): Map<String, Any?> {
        val st = Store(ctx)
        return mapOf("adulterado" to adulterado(ctx), "diferencaMin" to st.relogioDif / 60_000L, "voltouEm" to st.relogioVoltouEm)
    }
}
