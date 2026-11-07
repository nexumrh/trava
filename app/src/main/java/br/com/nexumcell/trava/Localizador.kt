package br.com.nexumcell.trava

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Localização SOMENTE quando o próprio cliente comunicou roubo, furto ou perda pelo portal
 * e autorizou a localização no formulário (roubo.localizar = true). Em uso normal o app
 * nunca lê a localização.
 */
object Localizador {
    @SuppressLint("MissingPermission")
    fun obter(ctx: Context): Map<String, Any?>? = try {
        if (Policy.isOwner(ctx) && Build.VERSION.SDK_INT >= 30) {
            try { Policy.dpm(ctx).setLocationEnabled(Policy.admin(ctx), true) } catch (e: Throwable) { }
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        var melhor: Location? = null
        if (lm != null) {
            for (p in lm.getProviders(true)) {
                val l = try { lm.getLastKnownLocation(p) } catch (e: Throwable) { null } ?: continue
                if (melhor == null || l.time > melhor!!.time) melhor = l
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val prov = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (prov != null) {
                    val latch = CountDownLatch(1); var nova: Location? = null
                    val ex = Executors.newSingleThreadExecutor()
                    try { lm.getCurrentLocation(prov, null, ex) { nova = it; latch.countDown() }; latch.await(20, TimeUnit.SECONDS) } catch (e: Throwable) { }
                    ex.shutdown()
                    if (nova != null) melhor = nova
                }
            }
        }
        melhor?.let { mapOf("lat" to it.latitude, "lng" to it.longitude, "precisao" to it.accuracy.toDouble(), "em" to it.time) }
    } catch (e: Throwable) { null }
}
