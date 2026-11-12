package br.com.nexumcell.trava

/** "Chave de acesso" de 8 números mostrada na tela de bloqueio. É o mesmo cálculo do painel (FNV-1a do token),
 *  então a loja acha o aparelho pela chave que o cliente lê. Não é segredo: o PIN de liberação é outro. */
object Chave {
    fun de(token: String): String {
        if (token.isEmpty()) return "--------"
        var h = 0x811c9dc5.toInt()
        for (c in token) { h = h xor c.code; h *= 16777619 }
        val u = h.toLong() and 0xffffffffL
        return (u % 100_000_000L).toString().padStart(8, '0')
    }
}
