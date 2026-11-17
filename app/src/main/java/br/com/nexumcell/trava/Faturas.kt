package br.com.nexumcell.trava

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.text.Normalizer
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Uma parcela com o valor a pagar hoje (desconto antecipado ou multa + juros). Mesma regra do painel (encargos). */
data class Fatura(
    val n: Int, val total: Int, val venc: LocalDate, val valor: Double, val pago: Boolean,
    val aPagar: Double, val desconto: Double, val encargos: Double, val diasAtraso: Long, val diasParaVencer: Long
)

object Faturas {
    val brl: NumberFormat = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
    val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun lista(d: JSONObject, hoje: LocalDate = LocalDate.now()): List<Fatura> {
        val arr = d.optJSONArray("parcelas") ?: return emptyList()
        val desc = d.optDouble("descontoPct", 0.0)
        val multa = d.optDouble("multaPct", 0.0)
        val mora = d.optDouble("moraDiaPct", 0.0)
        val out = mutableListOf<Fatura>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val venc = try { LocalDate.parse(p.optString("venc")) } catch (e: Exception) { continue }
            val valor = p.optDouble("valor", 0.0)
            val pago = p.optBoolean("pago")
            val ate = ChronoUnit.DAYS.between(hoje, venc)          // > 0: ainda vai vencer
            val atraso = if (pago) 0L else maxOf(0L, -ate)
            var aPagar = valor; var d0 = 0.0; var enc = 0.0
            if (!pago && ate > 0) { d0 = r2(valor * desc / 100); aPagar = r2(valor - d0) }
            else if (!pago && atraso > 0) { enc = r2(valor * multa / 100) + r2(valor * mora / 100 * atraso); aPagar = r2(valor + enc) }
            out.add(Fatura(p.optInt("n"), arr.length(), venc, valor, pago, aPagar, d0, enc, atraso, ate))
        }
        return out
    }

    private fun r2(x: Double) = Math.round(x * 100) / 100.0

    fun linkParcela(d: JSONObject, n: Int): String {
        val arr = d.optJSONArray("parcelas") ?: return ""
        for (i in 0 until arr.length()) { val p = arr.optJSONObject(i) ?: continue; if (p.optInt("n") == n) return p.optString("asaasLink") }
        return ""
    }

    /**
     * Quitação antecipada (CDC art. 52, §2º): parcelas ainda não vencidas são cobradas só pela parte do valor
     * financiado, sem os juros. Vencidas entram com multa e juros. Retorna (valor para quitar, economia) ou null.
     */
    fun quitacao(d: JSONObject): Pair<Double, Double>? {
        val fin = d.optDouble("financiado", 0.0); val n = d.optInt("nParcelas", 0)
        if (fin <= 0 || n <= 0) return null
        val principal = fin / n
        var total = 0.0; var cheio = 0.0
        for (f in lista(d)) {
            if (f.pago) continue
            cheio += if (f.diasAtraso > 0) f.aPagar else f.valor
            total += if (f.diasParaVencer > 0) minOf(principal, f.valor) else f.aPagar
        }
        if (total <= 0) return null
        return r2(total) to r2(cheio - total)
    }

    /** PIX copia e cola (BR Code estático com valor). */
    fun pix(d: JSONObject, valor: Double, txid: String): String? {
        val chave = d.optString("chavePix").trim()
        if (chave.isEmpty()) return null
        fun f(id: String, v: String) = id + v.length.toString().padStart(2, '0') + v
        fun limpa(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9 ]"), "").trim()
        val nome = limpa(d.optString("recebedor", "NEXUMCELL")).ifEmpty { "NEXUMCELL" }.take(25)
        val cidade = limpa(d.optString("cidadeRecebedor", "FOZ DO IGUACU")).ifEmpty { "FOZ DO IGUACU" }.take(15)
        val tx = txid.replace(Regex("[^A-Za-z0-9]"), "").take(25).ifEmpty { "***" }
        var p = f("00", "01") + f("26", f("00", "br.gov.bcb.pix") + f("01", chave)) + f("52", "0000") + f("53", "986") +
            f("54", String.format(Locale.US, "%.2f", valor)) + f("58", "BR") + f("59", nome) + f("60", cidade) + f("62", f("05", tx)) + "6304"
        return p + crc16(p)
    }

    private fun crc16(s: String): String {
        var crc = 0xFFFF
        for (b in s.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return String.format("%04X", crc)
    }

    fun qr(txt: String, tam: Int = 640): Bitmap {
        val m = QRCodeWriter().encode(txt, BarcodeFormat.QR_CODE, tam, tam)
        val px = IntArray(tam * tam)
        for (y in 0 until tam) for (x in 0 until tam) px[y * tam + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, tam, tam, Bitmap.Config.RGB_565)
    }

    /** Janela I18n.t("Pagar com PIX"). Se a loja tiver o servidor Asaas, gera PIX com baixa automática; senão, PIX estático. */
    fun mostrarPix(a: Activity, d: JSONObject, valor: Double, descricao: String, txid: String, parcela: Int = 0) {
        val servidor = d.optString("servidorPix").trim().trimEnd('/')
        val token = Store(a).token
        if (parcela > 0 && servidor.startsWith("https://") && token.isNotEmpty()) {
            Toast.makeText(a, I18n.t("Gerando PIX…"), Toast.LENGTH_SHORT).show()
            Thread {
                val r = try {
                    val c = (java.net.URL("$servidor/api/pix").openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 20000
                        setRequestProperty("Content-Type", "application/json")
                    }
                    c.outputStream.use { it.write(JSONObject().put("token", token).put("n", parcela).toString().toByteArray()) }
                    if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
                } catch (e: Exception) { null }
                a.runOnUiThread {
                    val cod = r?.optString("copiaECola").orEmpty()
                    if (cod.isNotEmpty()) dialogo(a, cod, r!!.optDouble("valor", valor), descricao, true)
                    else estatico(a, d, valor, descricao, txid)
                }
            }.start()
            return
        }
        estatico(a, d, valor, descricao, txid)
    }

    private fun estatico(a: Activity, d: JSONObject, valor: Double, descricao: String, txid: String) {
        val codigo = pix(d, valor, txid)
        if (codigo == null) {
            Toast.makeText(a, "Chave PIX não cadastrada. Fale com a loja.", Toast.LENGTH_LONG).show(); return
        }
        dialogo(a, codigo, valor, descricao, false)
    }

    /** Tela de pagamento PIX em painel inferior: valor, QR, código e botão de copiar. */
    private fun dialogo(a: Activity, codigo: String, valor: Double, descricao: String, automatico: Boolean) {
        if (a.isFinishing) return
        fun dp(v: Int) = Ui.dp(a, v)
        val dlg = android.app.Dialog(a)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(22), dp(10), dp(22), dp(22))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); val r = Ui.dpf(a, 24); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        box.addView(android.view.View(a).apply { background = Ui.forma(a, "#D6DDEB", 3); layoutParams = LinearLayout.LayoutParams(dp(40), dp(5)) })
        box.addView(Ui.iconeCirculo(a, R.drawable.ic_pix, "#0E9F8F", "#E6F6F4", 48).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) })
        box.addView(Ui.texto(a, I18n.t("Pagar com PIX"), 14f, Ui.TEXTO2, grav = Gravity.CENTER).apply { layoutParams = Ui.lp(topo = dp(10)) })
        box.addView(Ui.texto(a, brl.format(valor), 30f, Ui.TEXTO, Ui.NEGRITO, Gravity.CENTER).apply { layoutParams = Ui.lp(topo = dp(4)) })
        box.addView(Ui.texto(a, descricao, 13f, Ui.MUDO, grav = Gravity.CENTER).apply { layoutParams = Ui.lp(topo = dp(4)) })
        box.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER; background = Ui.forma(a, "#FFFFFF", 16, Ui.BORDA); setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) }
            addView(ImageView(a).apply { setImageBitmap(qr(codigo)); layoutParams = LinearLayout.LayoutParams(dp(200), dp(200)) })
        })
        box.addView(Ui.texto(a, codigo, 11f, Ui.MUDO, grav = Gravity.CENTER).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; layoutParams = Ui.lp(topo = dp(12)) })
        box.addView(Ui.botao(a, I18n.t("Copiar código PIX"), Ui.VERDE, alt = 54) {
            a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PIX", codigo))
            Toast.makeText(a, I18n.t("Código PIX copiado. Cole no app do seu banco."), Toast.LENGTH_LONG).show()
            dlg.dismiss()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18) })
        box.addView(Ui.texto(a, I18n.t(if (automatico) "O pagamento é confirmado sozinho. Em poucos minutos a parcela aparece como paga e o aparelho é liberado."
                   else "Depois de pagar, envie o comprovante para a loja. Assim que o pagamento for confirmado, o aparelho é liberado automaticamente."), 12f, Ui.TEXTO2, grav = Gravity.CENTER).apply { layoutParams = Ui.lp(topo = dp(12)) })
        box.addView(Ui.texto(a, I18n.t("Fechar"), 14f, Ui.AZUL, Ui.MEDIO, Gravity.CENTER).apply { setPadding(0, dp(14), 0, dp(4)); setOnClickListener { dlg.dismiss() } })
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(android.widget.ScrollView(a).apply { addView(box) })
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dlg.show()
    }

    /** Monta a lista de parcelas: linha compacta por parcela; a próxima e as atrasadas já mostram os botões de pagamento. */
    fun desenhar(a: Activity, alvo: LinearLayout, d: JSONObject) {
        alvo.removeAllViews()
        fun dp(v: Int) = Ui.dp(a, v)
        val num = d.optLong("vendaNumero")
        // Score NexumCell: o cliente vê a nota subir pagando em dia
        d.optJSONObject("score")?.let { sc ->
            val prox = sc.optJSONObject("proxima")
            alvo.addView(Ui.cartao(a).apply {
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(Ui.texto(a, I18n.t("Seu score NexumCell"), 13f, Ui.TEXTO2))
                        addView(Ui.texto(a, "${sc.optInt("valor")}", 26f, Ui.AZUL, Ui.NEGRITO).apply { layoutParams = Ui.lp(topo = dp(4)) })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Ui.chip(a, sc.optString("faixa"), Ui.AZUL, Ui.AZUL_CLARO, false))
                })
                addView(Ui.barra(a, sc.optInt("valor") / 10, Ui.AZUL))
                addView(Ui.texto(a, if (prox != null) "${I18n.t("Faltam")} ${prox.optInt("falta")} ${I18n.t("pontos para")} ${prox.optString("faixa")}. ${I18n.t("Pague em dia para subir e ganhar limite maior.")}" else I18n.t("Nível máximo! Você tem as melhores condições."), 13f, Ui.TEXTO2).apply { layoutParams = Ui.lp(topo = dp(10)) })
            })
        }
        val todas = lista(d)
        val proxN = todas.filter { !it.pago }.minByOrNull { it.venc }?.n
        val card = Ui.cartao(a, 4)
        todas.forEachIndexed { i, f ->
            if (i > 0) card.addView(Ui.divisor(a, 0, 0))
            val (rotulo, cor, fundo) = when {
                f.pago -> Triple("Paga", Ui.VERDE, Ui.VERDE_CLARO)
                f.diasAtraso > 0 -> Triple("Atrasada há ${f.diasAtraso} ${if (f.diasAtraso == 1L) "dia" else "dias"}", Ui.VERMELHO, Ui.VERMELHO_CLARO)
                f.diasParaVencer == 0L -> Triple("Vence hoje", Ui.LARANJA, Ui.LARANJA_CLARO)
                else -> Triple("Em ${f.diasParaVencer} ${if (f.diasParaVencer == 1L) "dia" else "dias"}", Ui.AZUL, Ui.AZUL_CLARO)
            }
            val bloco = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
            bloco.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(a).apply {
                    text = "${f.n}"; textSize = 14f; typeface = Ui.MEDIO; gravity = Gravity.CENTER; setTextColor(Color.parseColor(cor))
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor(fundo)) }
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                })
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0)
                    addView(Ui.texto(a, "Parcela ${f.n} de ${f.total}", 15f, Ui.TEXTO, Ui.MEDIO))
                    addView(Ui.texto(a, "Vencimento ${f.venc.format(fmt)}", 13f, Ui.TEXTO2).apply { layoutParams = Ui.lp(topo = dp(3)) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                    addView(Ui.texto(a, brl.format(if (f.pago) f.valor else f.aPagar), 15f, if (f.pago) Ui.TEXTO2 else Ui.TEXTO, Ui.NEGRITO, Gravity.END))
                    addView(Ui.chip(a, rotulo, cor, fundo, false).apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) } })
                })
            })
            if (!f.pago) {
                val det = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(52), 0, 0, 0) }
                when {
                    f.desconto > 0 -> det.addView(Ui.texto(a, "Valor cheio ${brl.format(f.valor)} · desconto de ${brl.format(f.desconto)} pagando até o vencimento", 12f, Ui.VERDE).apply { layoutParams = Ui.lp(topo = dp(10)) })
                    f.encargos > 0 -> det.addView(Ui.texto(a, "Inclui ${brl.format(f.encargos)} de multa e juros", 12f, Ui.VERMELHO).apply { layoutParams = Ui.lp(topo = dp(10)) })
                }
                val botoes = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = Ui.lp(topo = dp(2)) }
                botoes.addView(Ui.botao(a, I18n.t("Pagar com PIX"), Ui.VERDE, alt = 44) {
                    mostrarPix(a, d, f.aPagar, "Parcela ${f.n}/${f.total} · vencimento ${f.venc.format(fmt)}", "NXC${num}P${f.n}", f.n)
                }.apply { textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { topMargin = dp(10) } })
                val link = linkParcela(d, f.n)
                if (link.startsWith("https://")) botoes.addView(Ui.botao(a, I18n.t("Cartão ou boleto"), contorno = true, texto = Ui.AZUL, alt = 44) {
                    try { a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))) } catch (e: Exception) { }
                }.apply { textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { topMargin = dp(10); marginStart = dp(8) } })
                det.addView(botoes)
                // a próxima e as atrasadas já aparecem abertas; as demais abrem com um toque
                val aberta = f.diasAtraso > 0 || f.n == proxN
                det.visibility = if (aberta) View.VISIBLE else View.GONE
                bloco.addView(det)
                bloco.isClickable = true
                bloco.background = Ui.toque(a, "#FFFFFF", 14)
                bloco.setOnClickListener { det.visibility = if (det.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            }
            card.addView(bloco)
        }
        alvo.addView(card)
    }
}
