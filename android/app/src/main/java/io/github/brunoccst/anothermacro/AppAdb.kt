package io.github.brunoccst.anothermacro

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's own ADB: it pairs with the phone's Wireless debugging once (like Shizuku or LADB),
 * then connects to it on localhost and runs shell commands as the `shell` user. Used to read the
 * touchscreen (`getevent`) while a run is recorded: no Termux, no PC, no root.
 *
 * The key and its certificate live in the app's private storage (files/adb). Wireless debugging
 * must be on to connect (Android turns it off when Wi-Fi drops or the phone restarts; the
 * pairing stays valid, so turning it back on is enough).
 */
object AppAdb {
    private const val PREFS = "app_adb"

    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS)
            val dir = File(context.filesDir, "adb").apply { mkdirs() }
            val keyFile = File(dir, "key.pk8")
            val certFile = File(dir, "cert.der")
            val loaded = runCatching {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())) to
                    certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            }.getOrNull()
            if (loaded != null) {
                key = loaded.first; cert = loaded.second
            } else {
                val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
                val name = X500Name("CN=Another Macro")
                val now = Date()
                val builder = JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(SecureRandom().nextLong() and Long.MAX_VALUE),
                    now, Date(now.time + 20L * 365 * 86_400_000), name, kp.public,
                )
                val signed = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
                key = kp.private
                cert = JcaX509CertificateConverter().getCertificate(signed)
                keyFile.writeBytes(key.encoded)
                certFile.writeBytes(cert.encoded)
            }
        }

        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "Another Macro"
    }

    @Volatile private var manager: Manager? = null

    private fun manager(context: Context): Manager =
        manager ?: synchronized(this) { manager ?: Manager(context.applicationContext).also { manager = it } }

    /** Paired once (the phone keeps the pairing until "Revoke debugging authorizations"). */
    fun paired(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("paired", false)

    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("paired", false).apply()
        val m = synchronized(this) { manager.also { manager = null } } ?: return
        pool.execute { runCatching { m.disconnect() } }
    }

    /** Find the "Pair device with pairing code" port (shown while that dialog is open) by mDNS. */
    fun findPairingPort(context: Context, timeoutMs: Long = 8000): Int? = discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs)

    private fun discover(context: Context, type: String, timeoutMs: Long): Int? {
        val port = AtomicInteger(-1)
        val found = CountDownLatch(1)
        val mdns = AdbMdns(context.applicationContext, type) { _, p -> if (p > 0) { port.set(p); found.countDown() } }
        mdns.start()
        try { found.await(timeoutMs, TimeUnit.MILLISECONDS) } finally { mdns.stop() }
        return port.get().takeIf { it > 0 }
    }

    /**
     * Pair with the six-digit code from Wireless debugging → "Pair device with pairing code".
     * [port] is that dialog's port; null finds it by mDNS. Returns null when paired, else what went wrong.
     * Blocking: call off the main thread.
     */
    fun pair(context: Context, code: String, port: Int? = null): String? {
        val p = port ?: findPairingPort(context)
            ?: return "couldn't find the pairing port: keep the \"Pair device with pairing code\" dialog open"
        return try {
            manager(context).pair("127.0.0.1", p, code.trim())
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("paired", true).apply()
            MacroLog.i("adb: paired with Wireless debugging")
            null
        } catch (e: Exception) {
            MacroLog.e("adb: pairing failed", e)
            "pairing failed (${e.javaClass.simpleName}: ${e.message}); check the code and try again"
        }
    }

    /** The last port that connected (Wireless debugging's "IP address & Port"; it changes when it is turned off). */
    fun savedPort(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("port", 0).takeIf { it > 0 }

    private fun savePort(context: Context, port: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("port", port).apply()

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "app-adb").apply { isDaemon = true } }

    private class PairingRequired : Exception()

    /**
     * Try one port, giving up after [timeoutMs]. A connection that hangs keeps libadb's lock, so the
     * manager is dropped (a new one loads the same key) instead of being reused.
     */
    private fun tryPort(context: Context, port: Int, timeoutMs: Long): Boolean {
        val m = manager(context)
        val f = pool.submit<Boolean> { m.connect("127.0.0.1", port) || m.isConnected }
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS).also { if (it) { savePort(context, port); MacroLog.i("adb: connected on port $port") } }
        } catch (e: TimeoutException) {
            MacroLog.i("adb: port $port timed out after ${timeoutMs}ms")
            f.cancel(true); drop(m); false
        } catch (e: ExecutionException) {
            if (e.cause is io.github.muntashirakon.adb.AdbPairingRequiredException) throw PairingRequired()
            MacroLog.i("adb: port $port failed: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
            drop(m); false
        }
    }

    private fun drop(m: Manager) = synchronized(this) { if (manager === m) manager = null }

    /** Open TCP ports on localhost in Wireless debugging's range (refused ports answer at once on loopback). */
    private fun openPorts(skip: Set<Int>): List<Int> {
        val parts = (30000..65535).chunked(2200)
        return parts.map { range ->
            pool.submit<List<Int>> {
                range.filter { p ->
                    p !in skip && runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 30) }; true }.getOrDefault(false)
                }
            }
        }.flatMap { runCatching { it.get(20, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
    }

    /**
     * Connect if not connected (Wireless debugging must be on). Tries [port] (typed in the setup
     * screen), then the last port that worked, then mDNS, then the open localhost ports. Gives up
     * within about a minute and a half. Returns null when connected, else why not. Blocking.
     */
    fun connect(context: Context, port: Int? = null): String? {
        manager?.let { if (it.isConnected) return null }
        val tried = HashSet<Int>()
        fun attempt(p: Int?, timeoutMs: Long): Boolean = p != null && p > 0 && tried.add(p) && tryPort(context, p, timeoutMs)
        return try {
            if (attempt(port, 10_000) || attempt(savedPort(context), 10_000)) return null
            MacroLog.i("adb: looking for the Wireless debugging port by mDNS")
            if (attempt(discover(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 4000), 10_000)) return null
            val open = openPorts(tried)
            MacroLog.i("adb: open localhost ports: $open")
            if (open.take(10).any { attempt(it, 5000) }) return null
            "couldn't connect: turn on Wireless debugging (Settings → Developer options), or type the port shown under \"IP address & Port\" in Set up recording"
        } catch (e: PairingRequired) {
            forget(context)
            "pair the app again (Wireless debugging forgot it)"
        } catch (e: Exception) {
            MacroLog.e("adb: connect failed", e)
            "couldn't connect (${e.javaClass.simpleName}): is Wireless debugging on?"
        }
    }

    /** Run [command] as the shell user and return its output (gives up after [timeoutMs]). Blocking; connect first. */
    fun shell(context: Context, command: String, timeoutMs: Long = 15_000): String {
        val f = pool.submit<String> { manager(context).openStream("shell:$command").use { s -> s.openInputStream().bufferedReader().readText() } }
        return try { f.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: TimeoutException) {
            f.cancel(true); throw java.io.IOException("adb shell timed out: $command")
        } catch (e: ExecutionException) { throw e.cause ?: e }
    }

    /** A running shell [command] whose output is read as it comes (close it to stop the command). */
    fun stream(context: Context, command: String): AdbStream = manager(context).openStream("shell:$command")
}
