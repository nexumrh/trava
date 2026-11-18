import json, time, sys
from xml.sax.saxutils import escape
travado = len(sys.argv) > 1 and sys.argv[1] == "travado"
meses = ["2026-08-10","2026-09-10","2026-10-10","2026-11-10","2026-12-10","2027-01-10","2027-02-10","2027-03-10","2027-04-10","2027-05-10","2027-06-10","2027-07-10"]
pagas = 3 if travado else 4
parc = [{"n": i + 1, "venc": v, "valor": 226.67, "pago": i < pagas, **({"pagoEm": v} if i < pagas else {})} for i, v in enumerate(meses)]
d = {"clienteNome": "Maria Souza", "marca": "Realme", "modelo": "C71", "vendaNumero": 10, "status": "ATIVO",
     "lojaNome": "NexumCell Centro", "lojaTelefone": "(45) 99999-0000", "parcelas": parc, "etapaTotalDias": 5, "etapaParcialDias": 3, "etapaAvisoDias": 1,
     "descontoPct": 5, "multaPct": 2, "moraDiaPct": 0.033, "financiado": 2000, "nParcelas": 12,
     "maxDiasOffline": 15, "chavePix": "pix@nexumcell.com.br", "nomePix": "NEXUMCELL", "cidadePix": "FOZ DO IGUACU",
     "portal": "https://nexumrh.github.io/cliente/"}
agora = int(time.time() * 1000)
print("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>")
print('    <string name="cacheJson">%s</string>' % escape(json.dumps(d, ensure_ascii=False), {'"': "&quot;"}))
print('    <long name="ultimoSyncOk" value="%d" />' % agora)
print('    <boolean name="avisoOfflineVisto" value="true" />')
print('    <boolean name="modoLink" value="true" />')
print('    <string name="token">demo0000000000000000000000guia</string>')
print('    <string name="apiKey">demo</string>')
print('    <string name="projectId">demo</string>')
if travado: print('    <boolean name="travado" value="true" />')
print("</map>")
