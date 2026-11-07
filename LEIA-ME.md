# NexumCell — crediário de celulares com trava

Pacote:

| Pasta / arquivo | O que é |
|---|---|
| `painel/index.html` | Página inicial com o link de cada portal. |
| `painel/vendedor.html` | **Portal do Vendedor** — nova venda, suas vendas, contrato/assinatura, QR da trava, chamados. |
| `painel/financeiro.html` | **Portal do Financeiro** — análise de crédito, baixas, cobrança, trava, chamados. |
| `painel/suporte.html` | **Portal do Suporte** — aparelhos e trava, chamados. |
| `painel/gerente.html` | **Portal do Gerente** — resultados, vendas e cobrança da loja, vendedores. |
| `painel/dono.html` | **Painel do Dono** — todas as lojas, equipe e configurações. |
| `painel/nexumcell-config.js` | **Único lugar** onde se cola o `firebaseConfig` (vale para todos os portais). |
| `src/` | Modelo único dos portais + `build.py` (para quem for alterar o código: edite o modelo e rode `python3 src/build.py`). |
| `app-trava/` | Projeto Android (Kotlin) do app que trava o celular. Abrir no Android Studio. |
| `firestore.rules` | Regras de segurança do Firestore. |

Tudo funciona no **plano grátis (Spark)** do Firebase, sem Cloud Functions, e o app é instalado pelo QR code (não precisa de conta na Play Store).

---

## Como funciona (fluxo da venda)

1. **Dono** cria o acesso do vendedor em *Equipe* (nome, e-mail, telefone, loja). O sistema gera o **ID** (ex.: VND-4821) e uma **senha temporária de 6 dígitos**, e abre o **e-mail pronto** para enviar. O vendedor entra com **ID + senha temporária** e, no primeiro acesso, cria a própria senha. **Esqueci minha senha**: o Firebase manda para o e-mail cadastrado um link para criar uma senha nova (em *Authentication → Templates* deixe o idioma em português).
2. **Vendedor → Nova venda**, em 8 passos:
   1. **Documento** (RG, CIN ou CNH): foto tirada na hora ou enviada. Foto escura, tremida, estourada ou pequena é **recusada** no próprio celular; depois a **IA lê** nome, CPF, nascimento, RG e mãe e recusa documento ilegível.
   2. **Biometria facial**: só câmera ao vivo. A IA confere se é um rosto real (não foto de tela/papel) e se bate com o documento. Fica guardada só para Dono e Financeiro.
   3. **Contato**: 2 telefones do cliente — não podem ser iguais, nem de alguém da equipe, nem de outro cliente. Endereço por CEP.
   4. **Análise**: o vendedor fica vendo uma bolinha carregando **ao vivo**. O Dono aprova ou reprova no Painel do Dono → aparece na tela do vendedor **“Parabéns, você foi aprovado! Vamos escolher o aparelho”** ou **“Este cliente é inelegível para uma proposta de crédito no momento.”**
   5. **Aparelho**: Samsung, Motorola, Realme, Redmi, Oppo, Honor, Infinix → modelo → memória. As linhas Android dessas marcas já vêm cadastradas; o Dono só **libera** em *Aparelhos à venda* os que a loja tem e coloca o valor. **O vendedor não altera o valor.** Entrada mínima de **30%**.
   6. **Contrato** com assinatura na tela.
   7. **Entrada**: o aparelho só sai da loja depois de registrar a entrada recebida.
   8. **Trava**: vídeo/passo a passo de instalação da marca + QR code; a tela mostra ao vivo quando a trava foi instalada.
3. **Equipe → Confirmações**: em até 3 dias, mandar WhatsApp para os 2 telefones confirmando o cadastro, e confirmar o valor da entrada paga. Problema vira chamado.
4. **App do cliente** (no celular travado): *Minhas faturas* com vencimentos e parcelas, **PIX copia e cola/QR com 5% de desconto** pagando antes do vencimento e **multa + juros** depois. Atrasou além da tolerância → **bloqueio**. O Financeiro dá a baixa → o aparelho **libera** e mostra **“Parabéns! Suas faturas estão em dia. Continue pagando e evite atrasos.”**

> Confirmação automática do PIX (sem o Financeiro dar baixa) exige um gateway (ex.: Asaas) com servidor recebendo o aviso de pagamento — não dá só com o plano grátis do Firebase.

### IA (Groq)
Crie uma chave em <https://console.groq.com/keys> e cole em `nexumcell-config.js` (`NEXUMCELL_IA.chave`) no repositório do vendedor. A chave fica visível no navegador: use uma chave só para isso e acompanhe o uso no painel do Groq.

---

## 1. Firebase (uma vez)

1. Crie um projeto em <https://console.firebase.google.com> (plano Spark).
2. **Authentication → Método de login**: ative **E-mail/senha** e **Anônimo** (o anônimo é o login dos celulares).
3. **Firestore Database** → criar (modo produção, região `southamerica-east1`).
4. **Firestore → Regras**: cole o conteúdo de `firestore.rules` → **Publicar**.
5. **Configurações do projeto → Seus apps → Web (`</>`)** → copie o `firebaseConfig` e cole em `painel/nexumcell-config.js`.
6. Publique a pasta `painel` inteira (GitHub Pages ou domínio próprio — dá para pôr cada portal num subdomínio, como no NEXUM, desde que o `nexumcell-config.js` vá junto). Em *Authentication → Configurações → Domínios autorizados*, adicione o domínio.
7. Crie em *Authentication → Usuários → Adicionar usuário* o e-mail do dono (**wevellenerrera21@gmail.com**, definido em `nexumcell-config.js` e nas regras). Esse e-mail entra em **todos os portais** como Dono; os demais acessos são criados por ele em *Equipe*.
8. No Painel do Dono, em **Lojas e configurações**: cadastre as lojas, juros, tolerância, chave PIX, telefone do suporte. Em **Equipe**, crie os acessos.

> Não restrinja a chave de API (apiKey) por site/domínio no Google Cloud: os celulares usam a mesma chave.

Cada pessoa só entra no portal do seu perfil (o Dono entra em todos); quem tentar o portal errado recebe o link do certo. Ao criar um acesso em *Equipe*, o sistema já mostra o link do portal daquela pessoa.

Sem `apiKey` os portais abrem em **modo demonstração** (dados de exemplo, só no navegador) — bom para treinar a equipe.

---

## 2. Gerar o APK (uma vez, e a cada atualização)

1. Instale o **Android Studio** e abra a pasta `app-trava`. Aguarde o *Gradle sync* (ele baixa o que falta).
2. Menu **Build → Generate Signed App Bundle or APK → APK → Create new…** (keystore).
   - **Guarde o arquivo .jks e as senhas em lugar seguro (e com backup).** Se perder, não dá mais para atualizar o app nos celulares já instalados.
3. Escolha **release** → *Create*. O APK sai em `app-trava/app/release/app-release.apk`. Renomeie para `nexumcell-trava.apk`.
4. **Hospede o APK** em um link `https` de download direto (ex.: GitHub Releases, ou o mesmo repositório do GitHub Pages: `https://seusite/nexumcell-trava.apk`).
5. **SHA-256 da assinatura** — no terminal (o `keytool` vem com o Android Studio, pasta `jbr/bin`):

   ```
   keytool -printcert -jarfile nexumcell-trava.apk
   ```

   Copie a linha **SHA256:** (ex.: `AB:CD:12:…`, 32 pares).
6. No Painel do Dono → **Lojas e configurações → Instalação do app**: cole o link do APK e o SHA-256 → *Salvar*. O painel converte para o formato do QR.

Atualizações: aumente `versionCode` em `app/build.gradle.kts`, gere o APK com a **mesma keystore** e substitua o arquivo no link. Celulares novos já pegam a versão nova; nos antigos, a atualização exige reinstalar via ADB/MDM (melhoria futura: atualização automática pelo próprio app).

---

## 3. Proteção contra formatação pelo recovery (FRP) — importante

As restrições bloqueiam o “restaurar de fábrica” pelas Configurações. Mas pelo **recovery** (botões volume+power) qualquer celular pode ser formatado. Para isso não remover a trava, o app configura o **FRP da empresa**: depois de formatado, o celular **só passa da tela inicial com uma conta Google da loja**.

1. Crie uma conta Google só para isso (ex.: `trava.nexumcell@gmail.com`).
2. Pegue o **ID numérico** dela: abra <https://developers.google.com/oauthplayground>, logado nessa conta → em *Step 1* digite o escopo `https://www.googleapis.com/auth/userinfo.profile` → *Authorize* → *Exchange authorization code* → em *Step 3* faça GET em
   `https://people.googleapis.com/v1/people/me?personFields=metadata`
   → o `resourceName` vem como `people/123456789012345678901`. O número é o ID.
3. Cole o número em **Configurações → Trava → IDs das contas Google (FRP)** (pode ter mais de um, separado por vírgula).

Se um celular formatado aparecer na loja, entre com essa conta na tela de “verificar conta” e reinstale pelo QR.

---

## 4. Instalar no celular do cliente

1. Confira o IMEI (`*#06#`) com o do contrato.
2. Se o aparelho já foi ligado/configurado: **restaurar de fábrica**.
3. Na primeira tela (“Bem-vindo / Começar”), **toque 6 vezes** no mesmo ponto → abre o leitor de QR.
4. Conecte ao Wi-Fi (ou cadastre o Wi-Fi da loja nas configurações do painel, que ele vai dentro do QR) e leia o **QR code da venda**.
5. O aparelho baixa o app, configura como “aparelho gerenciado” e continua o assistente normal. O cliente coloca a conta Google dele.
6. Abra o app **NexumCell**: deve mostrar “Contrato nº X — ativo”. No painel o aparelho passa de “Aguardando instalação” para “Liberado”.

Funciona em Android 8 ou mais novo (o QR com 6 toques existe a partir do Android 7). **iPhone não é compatível.**

---

## 5. Teste antes de usar com cliente (use um aparelho de teste!)

No modo de teste, ajuste no painel: tolerância `0` e “sem internet” `1` dia.

- [ ] Instalar pelo QR e ver “Liberado” no painel.
- [ ] *Travar agora* no painel → tocar “Já paguei — verificar” no celular → trava. Botão Home, Voltar, Recentes e barra de notificações não funcionam.
- [ ] Ligação de emergência abre o discador.
- [ ] Reiniciar o celular → volta travado.
- [ ] *Voltar ao automático* com parcela em dia → destrava.
- [ ] Criar venda com 1º vencimento ontem → trava sozinho.
- [ ] Dar baixa → destrava.
- [ ] Configurações → Restaurar de fábrica → bloqueado. Modo de segurança → bloqueado. Desinstalar o app → bloqueado.
- [ ] Formatar pelo recovery → pede conta Google da loja (FRP).
- [ ] Quitar todas as parcelas → app mostra “Contrato encerrado” e pode ser desinstalado.

Teste em **cada marca** que vocês vendem (Samsung, Motorola, Xiaomi…): fabricantes mudam detalhes do Android.

> Se travar um aparelho de teste e quiser sair: no painel, *Cancelar venda* (Dono/Gerente) → no próximo contato o app remove tudo. Sem internet, a única saída é formatar pelo recovery (e aí entra a conta do FRP).

---

## 6. Limites (honestos)

- **Regravar o firmware** (Odin, EDL, Mi Flash) remove o app. Pega técnico, não o cliente comum. Para Samsung, a alternativa mais forte é o **Samsung Knox Guard** (trava no firmware), que exige cadastro de empresa na Samsung.
- A verificação roda a cada 15 min; em economia de bateria (Doze) o Android pode atrasar. Para liberar na hora, o cliente toca em “Já paguei — verificar agora”.
- Durante a trava o discador fica disponível (para emergência e para ligar para a loja); nele dá para ligar para qualquer número.
- Cota grátis do Firestore: cada celular lê o contrato a cada 15 min (~96 leituras/dia) e grava o check-in só quando muda o estado ou a cada 3 h. Isso dá folga para **~450 aparelhos ativos** no plano grátis (limite de 50 mil leituras/dia). Acima disso, aumente o intervalo para 30 min em `Sync.kt` (`Agendador`) ou ative o Blaze.

## 7. Jurídico (validar com advogado)

- Vender **os próprios aparelhos a prazo** (crediário com reserva de domínio) é venda a prazo. **Emprestar dinheiro ou financiar compras de terceiros** é atividade de instituição financeira e exige autorização do Banco Central (ou parceria com uma).
- O bloqueio precisa estar **no contrato, com aceite claro** (cláusula 5) e preservar **ligações de emergência**. Não é permitido usar bloqueio de IMEI na operadora/Anatel por inadimplência.
- O contrato do painel é um **modelo**: revise juros, multa (CDC, art. 52: máx. 2%), foro e textos com o advogado antes de usar.
- O app só coleta IMEI, modelo, bateria, versão e estado da trava. Não acessa fotos, mensagens, contatos nem localização (cláusula 6 / LGPD).
