package br.com.nexumcell.trava

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Notificação instantânea: quando a loja trava/libera ou o cliente paga, o servidor manda um
 * aviso pelo Firebase Cloud Messaging e o aparelho sincroniza na hora (sem esperar os 15 min).
 * Sem o ID do app Android configurado no painel (ou sem Google Play), continua só a verificação periódica.
 */
object Push {
    private const val TAG = "NexumPush"

    fun iniciar(ctx: Context) {
        try {
            val st = Store(ctx)
            if (st.finalizado || st.fcmAppId.isEmpty() || st.fcmSender.isEmpty() || st.apiKey.isEmpty() || st.projectId.isEmpty()) return
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                val op = FirebaseOptions.Builder()
                    .setApplicationId(st.fcmAppId).setApiKey(st.apiKey).setProjectId(st.projectId).setGcmSenderId(st.fcmSender)
                    .build()
                FirebaseApp.initializeApp(ctx, op)
            }
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                if (!t.isNullOrEmpty() && t != st.pushToken) { st.pushToken = t; Agendador.agora(ctx) }
            }
        } catch (e: Throwable) { Log.w(TAG, "push: ${e.message}") }
    }

    /** Lê a configuração que vem no documento do aparelho (campo fcm: {appId, senderId}). */
    fun configurar(ctx: Context, d: org.json.JSONObject) {
        val f = d.optJSONObject("fcm") ?: return
        val st = Store(ctx)
        val app = f.optString("appId"); val snd = f.optString("senderId")
        if (app.isEmpty() || snd.isEmpty()) return
        if (app != st.fcmAppId || snd != st.fcmSender) { st.fcmAppId = app; st.fcmSender = snd }
        if (st.pushToken.isEmpty()) iniciar(ctx)
    }
}

class NexumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Push.iniciar(this)
    }
}

class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        // roda em segundo plano: sincroniza e aplica (travar/liberar) na hora
        try { Sync.executar(applicationContext) } catch (e: Throwable) { Log.w("NexumPush", "sync: ${e.message}") }
    }

    override fun onNewToken(token: String) {
        Store(applicationContext).pushToken = token
        Agendador.agora(applicationContext)
    }
}
