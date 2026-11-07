# NexumCell — App de Trava (Android)

App Kotlin que trava o celular financiado quando há parcela em atraso. É instalado como **administrador do dispositivo (Device Owner)** pelo QR code gerado no Portal do Vendedor. Não precisa de Play Store.

## Gerar o APK
1. Abra esta pasta no **Android Studio** e aguarde o *Gradle sync*.
2. **Build → Generate Signed App Bundle or APK → APK → Create new…** (keystore).
   - **Guarde o `.jks` e as senhas fora do GitHub, com backup.** Sem ele você não consegue mais atualizar o app nos celulares já instalados. O `.gitignore` já impede de enviar por engano.
3. Escolha **release**. O APK sai em `app/release/app-release.apk`.

## Publicar o APK (para o QR code baixar)
1. No GitHub: **Releases → Draft a new release** → tag `v1.0` → anexe o APK renomeado para `nexumcell-trava.apk` → *Publish*.
2. Link de download direto:
   `https://github.com/nexumrh/trava/releases/latest/download/nexumcell-trava.apk`
   (o repositório precisa ser **público** para o celular conseguir baixar).
3. SHA-256 da assinatura:
   ```
   keytool -printcert -jarfile nexumcell-trava.apk
   ```
   copie a linha `SHA256:`.
4. No **Painel do Dono → Lojas e configurações → Instalação do app**, cole o link e o SHA-256.

## Atualizar
Aumente `versionCode`/`versionName` em `app/build.gradle.kts`, gere com a **mesma keystore** e publique um novo Release com o mesmo nome de arquivo. O link `latest` passa a apontar para a versão nova.

## Como funciona
- `Decisao.kt` — regra da trava (igual à função `avaliar()` dos portais: se mudar uma, mude a outra).
- `Sync.kt` — a cada 15 min lê o contrato no Firestore (login anônimo, API REST) e aplica; trava também sem internet.
- `Policy.kt` — restrições (sem reset pelas Configurações, sem modo seguro, sem desinstalar, data automática), FRP, tela de bloqueio, remoção na quitação.
- `LockActivity.kt` — tela de bloqueio (parcelas vencidas, PIX, ligar para a loja, emergência, “Já paguei — verificar agora”).

Detalhes de FRP, instalação no celular e testes: veja o LEIA-ME geral do NexumCell.
