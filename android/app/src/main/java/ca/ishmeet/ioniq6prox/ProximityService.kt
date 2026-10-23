package ca.ishmeet.ioniq6prox

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.VibratorManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
 * Walk-away detection.
 *
 *   DRIVING  — phone is connected to the car's own Bluetooth. (The ESP32 is usually OFF now:
 *              the power bank cuts its output while the car charges it.)
 *   PARKED   — car Bluetooth dropped (you switched off and got out). The ESP32 boots as the
 *              car cuts USB power; we scan for it actively and connect directly, then watch
 *              its signal.
 *   AWAY     — tag signal faded below the threshold, or the link dropped after fading:
 *              we've told the server, which locks the car.
 *
 * A link that drops while the signal was still STRONG isn't you walking away — it's the ESP32
 * losing power (you started the car, so the bank cut its output). That never locks by itself;
 * if the car's Bluetooth doesn't connect within POWERCUT_WAIT_MS we hand over to the server
 * with check-first, which skips the lock if the car is on.
 *
 * Without a drive (app just started, or AWAY), being right next to the tag for 30 s arms too —
 * e.g. you opened the car to grab something. Every walk-away sends a plain lock (no check
 * first): locking an already-locked car is harmless, while checking first means waking the car,
 * which is what Hyundai rate-limits (build 21). Check-first is kept only where the car may be
 * running (after the tag lost power). If the tag never shows up after parking (dead power
 * bank…), we fall back to car Bluetooth alone with the server's normal grace period.
 *
 *   HOME     — (home mode on) parked at home: on the home Wi-Fi, or within the home circle.
 *              From the house the tag is often still "in range", so walk-away by signal doesn't
 *              work there. Instead: one lock per VISIT to the car. A visit is being right at the
 *              car (signal at or above the "at the car" level for VISIT_MS) — parking counts as
 *              one. Once you've been away from the car for homeDelaySec, it locks once (check-
 *              first, so an already-locked car is left alone). Wandering around the house never
 *              reaches the at-the-car level, so nothing fires.
 */
class ProximityService : Service() {

    companion object {
        const val ACTION_ARM = "ca.ishmeet.ioniq6prox.ARM"
        const val ACTION_PAUSE = "ca.ishmeet.ioniq6prox.PAUSE"
        const val ACTION_RESUME = "ca.ishmeet.ioniq6prox.RESUME"
        const val ACTION_CHECK_SERVER = "ca.ishmeet.ioniq6prox.CHECK"
        const val ACTION_APPROACH_UNLOCK = "ca.ishmeet.ioniq6prox.APPROACH_UNLOCK"
        const val ACTION_APPROACH_CLIMATE = "ca.ishmeet.ioniq6prox.APPROACH_CLIMATE"
        private const val APPROACH_ID = 4
        private const val APPROACH_CHANNEL = "approach"
        private const val APPROACH_AWAY_MS = 5 * 60_000L    // away from the car at least this long…
        private const val APPROACH_RISE_DB = 3              // …then the signal climbs this much, reading after reading = walking up
        private const val CHANNEL = "monitor"
        private const val ALERT_CHANNEL = "alerts"
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2
        private const val LOCK_ID = 3
        private const val LOCK_CHANNEL = "lock"
        // vibration patterns (off, on, off, on…) in ms
        private val BUZZ_SENT = longArrayOf(0, 70, 90, 70)            // two taps: lock sent
        private val BUZZ_LOCKED = longArrayOf(0, 220)                  // one firm buzz: locked
        private val BUZZ_PROBLEM = longArrayOf(0, 450, 180, 450, 180, 450)  // long ×3: NOT locked
        private val BUZZ_APPROACH = longArrayOf(0, 50, 70, 50, 70, 50)      // three quick taps: heading to the car?

        private const val SETTLE_MS = 10_000L        // ignore tag hiccups right after parking
        private const val LINK_HOLD_MS = 5_000L      // link must stay down this long to count as "gone"
        private const val RSSI_HOLD_MS = 3_000L      // signal must stay weak this long
        private const val FALLBACK_MS = 45_000L      // tag never connected after parking → car-Bluetooth fallback
        private const val SCAN_MS = 45_000L          // active search for the ESP32 after parking
        private const val REARM_MS = 30_000L         // near the tag this long without driving → arm
        private const val AWAY_COUNT_RESET_MS = 30 * 60_000L
        private const val POWERCUT_WAIT_MS = 90_000L // after a power cut, wait this long for car Bluetooth
        private const val POWERCUT_MARGIN_DB = 12    // dropped while this much stronger than threshold → power cut
        private const val HEALTH_EVERY_MS = 15 * 60_000L
        private const val HYSTERESIS_DB = 8
        private const val MAX_AWAYS_PER_STOP = 5
        private const val HOME_DETECT_MS = 2 * 60_000L // after parking, look for "home" this long
        private const val LOCATE_RETRY_MS = 20_000L
        private const val VISIT_MS = 5_000L            // at the car this long = a visit
        private const val HERE_EVERY_MS = 2 * 60_000L  // "still here" to the server while near the car
        private const val MOVING_MPS = 2.8f            // > 10 km/h …
        private const val MOVING_HOLD_MS = 8_000L      // … for 8 s = driving
        private const val STILL_MPS = 1.4f             // < 5 km/h …
        private const val STILL_HOLD_MS = 20_000L      // … for 20 s = standing still
        private const val HOME_RECHECK_MS = 60_000L

        fun hasPermissions(c: Context): Boolean {
            val need = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            return need.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }

        fun start(c: Context) {
            if (!hasPermissions(c)) { Status.log("Can't start: Bluetooth permission missing"); return }
            c.startForegroundService(Intent(c, ProximityService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ProximityService::class.java))
        }

        /** Sends an action to the running service (pause, resume, server check, test arm). */
        fun send(c: Context, action: String) {
            c.startService(Intent(c, ProximityService::class.java).setAction(action))
        }
    }

    private enum class Mode(val label: String) { UNKNOWN("Waiting for car"), DRIVING("Driving"), PARKED("Parked — watching"), AWAY("Away"), HOME("At home") }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var net: ExecutorService
    private lateinit var watch: ExecutorService   // follows a sent lock until the server reports the result
    @Volatile private var watchGen = 0
    private var gatt: BluetoothGatt? = null
    private var gattIsDirect = false

    private var mode = Mode.UNKNOWN
    private var espConnected = false
    private var smoothed: Double? = null
    private var parkedAt = 0L
    private var linkDownSince = 0L
    private var dropWasPowerCut = false
    private var powerCutHandled = false
    private var belowSince = 0L
    private var aboveSince = 0L
    private var tagSeenThisStop = false
    private var fallbackSent = false
    private var awaysThisStop = 0
    private var armedByProximity = false   // armed by standing near the car, not by a drive ending
    private var lastAwayAt = 0L
    private var scanning = false
    private var scanGen = 0
    private var lastHealthAt = 0L
    private var lastNotifText = ""
    private var lastNotifAt = 0L
    private var homeCheckUntil = 0L      // >0: still working out whether we parked at home
    private var nextLocateAt = 0L
    private var locating = false
    @Volatile private var wifiName: String? = null
    private var lastAtCarAt = 0L          // last time the signal was at the "at the car" level
    private var atCarSince = 0L
    private var visiting = false          // at home: a visit is open; lock once you've left the car
    private var visitIsParking = false    // the open visit is the one that started with parking
    private var homeCachedAt = 0L
    private var lastHereAt = 0L
    // walking toward the car
    private var farSince = 0L
    private val approachHist = ArrayDeque<Double>()   // the tag's signal over the last few seconds while connected
    private var approachAlerted = false
    private var approachRearm = true     // may alert again: the tag was lost, or you were back at the car since
    private var approachAsking = false   // the "Heading to the car?" question is on screen
    private var motionListener: LocationListener? = null
    private var lastFix: Location? = null   // newest GPS fix while connected to the car: where you parked
    private var fastSince = 0L
    private var slowSince = 0L
    private var homeCached: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        net = Executors.newSingleThreadExecutor()
        watch = Executors.newSingleThreadExecutor()
        createChannels()
        try {
            startForeground(NOTIF_ID, buildNotif("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            Status.log("Couldn't start in background: ${e.message}")
            stopSelf()
            return
        }
        Status.running = true
        registerAclReceiver()
        registerWifiWatch()
        connectTag(direct = false)
        checkCarAlreadyConnected()
        // No "connected" answer within a few seconds → the car isn't being driven with this phone.
        handler.postDelayed({ if (Status.carConnected != true && mode != Mode.DRIVING) notDrivingAtStart() }, 4_000)
        handler.post(tick)
        Status.log("Monitoring started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> arm("armed manually (test)")
            ACTION_PAUSE -> pause(120)
            ACTION_RESUME -> pause(0)
            ACTION_CHECK_SERVER -> checkServer()
            ACTION_APPROACH_UNLOCK -> approachCommand(unlock = true)
            ACTION_APPROACH_CLIMATE -> approachCommand(unlock = false)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopScan()
        try { unregisterReceiver(aclReceiver) } catch (_: Exception) {}
        try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(wifiCb) } catch (_: Exception) {}
        stopMotionWatch()
        try { gatt?.close() } catch (_: SecurityException) {}
        gatt = null
        if (::net.isInitialized) net.shutdown()
        if (::watch.isInitialized) watch.shutdownNow()
        Status.running = false
        Status.mode = "Stopped"
        Status.espConnected = false
        Status.rssi = null
        Status.log("Monitoring stopped")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ car Bluetooth

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.bluetoothDevice() ?: return
            if (dev.address != prefs.carAddress) return
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> handler.post { onCarConnected() }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> handler.post { onCarDisconnected() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun registerAclReceiver() {
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(aclReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(aclReceiver, f)
    }

    private fun adapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    /** At start-up there's no "connected" broadcast for a link that already exists, so ask the audio profiles. */
    private fun checkCarAlreadyConnected() {
        val a = adapter() ?: return
        val car = prefs.carAddress ?: return
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        if (proxy.connectedDevices.any { it.address == car }) handler.post { if (mode != Mode.DRIVING) onCarConnected() }
                    } catch (_: SecurityException) {
                    } finally {
                        a.closeProfileProxy(p, proxy)
                    }
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
    }

    private fun onCarConnected() {
        Status.carConnected = true
        startMotionWatch()
        prefs.carOnAt = now(); Status.changed()
        homeCheckUntil = 0
        stopScan()
        if (mode == Mode.DRIVING) return
        setMode(Mode.DRIVING, "car Bluetooth connected")
        clearApproachQuestion()   // you're in the car: "Heading to the car?" no longer makes sense
        send("/v1/autolock/near", """{"source":"car-bluetooth"}""", "near")
    }

    private fun onCarDisconnected() {
        Status.carConnected = false
        stopMotionWatch()
        prefs.carOffAt = now(); prefs.carOffAtStartup = false; Status.changed()
        arm("car Bluetooth disconnected")
    }

    /** Started (or restarted) while not connected to the car: record that it isn't being driven. */
    private fun notDrivingAtStart() {
        Status.carConnected = false
        if (prefs.carOffAt > 0 && prefs.carOffAt > prefs.carOnAt) return   // already know it's parked
        prefs.carOffAt = now()
        prefs.carOffAtStartup = true
        Status.changed()
        Status.log("Not connected to the car's Bluetooth: treating it as parked")
    }

    private fun arm(why: String) {
        parkedAt = now()
        awaysThisStop = 0
        armedByProximity = false
        fallbackSent = false
        belowSince = 0
        aboveSince = 0
        dropWasPowerCut = false
        powerCutHandled = false
        tagSeenThisStop = espConnected
        linkDownSince = if (espConnected) 0 else now()
        setMode(Mode.PARKED, why)
        if (!espConnected) startScan()
        // Tell the server we parked. If the phone then goes quiet (dead, killed, no signal) without
        // a lock, the server locks on its own after a few minutes.
        send("/v1/autolock/parked", """{"source":"car-bluetooth"}""", "parked")
        lastHereAt = parkedAt
        if (Home.enabled(prefs)) {
            homeCheckUntil = parkedAt + HOME_DETECT_MS
            nextLocateAt = parkedAt
            checkHome(parkedAt)
        } else homeCheckUntil = 0
    }

    // ------------------------------------------------------------------ driving vs standing (GPS)

    /** GPS only while connected to the car, so it costs almost nothing. */
    private fun startMotionWatch() {
        if (motionListener != null) return
        if (!Home.hasLocation(this)) { Status.moving = null; return }
        val lm = getSystemService(LocationManager::class.java) ?: return
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER)
            .firstOrNull { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } } ?: return
        val l = LocationListener { loc ->
            handler.post {
                lastFix = loc
                if (loc.hasSpeed()) { Status.speedKmh = loc.speed * 3.6f; onSpeed(loc.speed) }
            }
        }
        try {
            lm.requestLocationUpdates(provider, 3_000L, 0f, l, Looper.getMainLooper())
            motionListener = l
            fastSince = 0; slowSince = 0
        } catch (e: SecurityException) { Status.moving = null }
    }

    private fun stopMotionWatch() {
        motionListener?.let { l -> try { getSystemService(LocationManager::class.java)?.removeUpdates(l) } catch (_: Exception) {} }
        motionListener = null
        // The last fix before leaving the car's Bluetooth is where it's parked (home screen: "Parked here").
        // A fix more than 5 minutes old (lost GPS in a garage) would point somewhere else: skip it.
        lastFix?.let { f -> if (now() - f.time < 5 * 60_000L) try { prefs.saveParkedSpot(f.latitude, f.longitude, now()) } catch (_: Exception) {} }
        lastFix = null
        Status.speedKmh = null
        if (Status.moving != null) { Status.moving = null; Status.changed() }
    }

    private fun onSpeed(mps: Float) {
        val t = now()
        when {
            mps > MOVING_MPS -> {
                slowSince = 0
                if (fastSince == 0L) fastSince = t
                if (t - fastSince >= MOVING_HOLD_MS && Status.moving != true) { Status.moving = true; Status.changed(); Status.log("Moving: driving") }
            }
            mps < STILL_MPS -> {
                fastSince = 0
                if (slowSince == 0L) slowSince = t
                if (t - slowSince >= STILL_HOLD_MS && Status.moving != false) { Status.moving = false; Status.changed(); Status.log("Standing still (in the car)") }
            }
        }
    }

    // ------------------------------------------------------------------ home

    // Android tells us the Wi-Fi name as it joins (needs location permission, "all the time").
    private val wifiCb = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            val ssid = Home.clean((caps.transportInfo as? WifiInfo)?.ssid) ?: return
            handler.post {
                if (ssid == wifiName) return@post
                wifiName = ssid
                if (homeCheckUntil > 0) checkHome(now())
            }
        }
        override fun onLost(n: Network) { handler.post { wifiName = null } }
    }

    private fun registerWifiWatch() {
        try {
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(req, wifiCb)
        } catch (e: Exception) { Status.log("Can't watch Wi-Fi: ${e.message}") }
    }

    private fun wifiNow(): String? = wifiName ?: Home.currentWifi(this)

    /** While the post-parking window is open: home Wi-Fi now? Otherwise ask for a location fix. */
    private fun checkHome(t: Long) {
        if (homeCheckUntil == 0L || mode == Mode.HOME || mode == Mode.DRIVING) return
        if (t > homeCheckUntil) {
            homeCheckUntil = 0
            Status.log("Not at home (no home Wi-Fi or location within 2 min of parking)")
            return
        }
        val wifi = wifiNow()
        if (Home.isHomeWifi(prefs, wifi)) { enterHome("on home Wi-Fi “$wifi”"); return }
        if (prefs.homeLat == null || locating || t < nextLocateAt) return
        if (!Home.hasLocation(this)) { nextLocateAt = Long.MAX_VALUE; Status.log("Home mode: no location permission — using Wi-Fi only"); return }
        locating = true
        Home.locate(this) { loc ->
            locating = false
            if (homeCheckUntil == 0L || mode == Mode.HOME || mode == Mode.DRIVING) return@locate
            if (loc == null) { nextLocateAt = now() + LOCATE_RETRY_MS; return@locate }
            val d = Home.distance(prefs, loc) ?: return@locate
            if (Home.inside(prefs, loc)) enterHome("parked ${Home.describe(d)} from home")
            else if (loc.accuracy > 300) nextLocateAt = now() + LOCATE_RETRY_MS   // too vague to rule home out
            else { nextLocateAt = Long.MAX_VALUE; homeCheckUntil = 0; Status.log("Parked ${Home.describe(d)} from home — normal walk-away") }
        }
    }

    /**
     * Still working out whether we parked at home (Wi-Fi joining, GPS fix): hold the signal-based
     * walk-away, because at home the 20-second rule is the one that should lock. Usually this
     * takes a second or two; after 30 s we stop waiting.
     */
    private fun homeUndecided(t: Long) = homeCheckUntil > 0 && t - parkedAt < 30_000

    /** Parked at home. Parking is the first visit: lock once you've been away from the car homeDelaySec. */
    private fun enterHome(why: String) {
        if (mode == Mode.HOME) return
        homeCheckUntil = 0
        stopScan()
        belowSince = 0
        aboveSince = 0
        atCarSince = 0
        val alreadySent = awaysThisStop > 0
        awaysThisStop++
        visiting = !alreadySent
        visitIsParking = true
        lastAtCarAt = maxOf(lastAtCarAt, parkedAt)
        setMode(Mode.HOME, if (alreadySent) "$why — already locked; locks again only after you've been at the car"
                           else "$why — locks once you've been away from the car ${prefs.homeDelaySec}s")
    }

    /** Back at home without a drive (app restarted, or you came home on foot): wait for a visit. */
    private fun homeQuiet(why: String) {
        visiting = false
        atCarSince = 0
        aboveSince = 0
        setMode(Mode.HOME, why)
    }

    /** true = home, false = elsewhere, null = can't tell. Re-checked at most once a minute. */
    private fun whereCached(t: Long): Boolean? {
        if (t - homeCachedAt > HOME_RECHECK_MS) { homeCached = Home.where(this, prefs, wifiName); homeCachedAt = t }
        return homeCached
    }

    /** One second of home logic: start visits, and lock once after each. */
    private fun homeTick(t: Long, atCar: Boolean) {
        // The tag lost power at full signal: you're most likely starting the car, so you're still
        // at it. Don't count that as walking away (the car's Bluetooth takes over in a moment).
        if (!espConnected && dropWasPowerCut && linkDownSince > 0 && t - linkDownSince < POWERCUT_WAIT_MS) lastAtCarAt = t
        if (atCar) {
            if (atCarSince == 0L) atCarSince = t
            if (!visiting && t - atCarSince >= VISIT_MS) {
                homeCachedAt = 0
                if (!Home.enabled(prefs) || whereCached(t) == false) {
                    // At the car somewhere that isn't home (a drive the phone missed): normal walk-away.
                    // If we can't tell where we are, we stay in home mode: we were home last.
                    rearm("at the car, not at home — normal walk-away")
                    return
                }
                visiting = true
                visitIsParking = false
                setMode(Mode.HOME, "at the car — locks once you've been away from it ${prefs.homeDelaySec}s")
                send("/v1/autolock/near", """{"source":"esp32-home"}""", "near")
            }
        } else atCarSince = 0
        if (visiting && t - lastAtCarAt >= prefs.homeDelaySec * 1000L) {
            visiting = false
            // First lock after a drive: send straight away. After a later visit: check first, so a car
            // you locked yourself isn't sent a pointless command.
            // And if the tag lost power at full signal (you may have started the car), let the
            // server check too: it skips the lock when the car is on.
            lastAwayAt = t
            setMode(Mode.HOME, "away from the car ${prefs.homeDelaySec}s — locking once")
            // Lock straight away (harmless if it's already locked). Only after a power cut (car
            // maybe running) does the server check first; it skips the lock if the car is on.
            lockAway("home", "you'd been away from the car for ${prefs.homeDelaySec} s (home rule)", "home rule", checkFirst = dropWasPowerCut)
        }
    }

    private fun rearm(why: String) {
        aboveSince = 0
        belowSince = 0
        atCarSince = 0
        visiting = false
        tagSeenThisStop = true
        dropWasPowerCut = false
        armedByProximity = true
        if (now() - lastAwayAt > AWAY_COUNT_RESET_MS) awaysThisStop = 0
        parkedAt = now() - SETTLE_MS // already settled
        setMode(Mode.PARKED, why)
        send("/v1/autolock/near", """{"source":"esp32"}""", "near")
    }

    // ------------------------------------------------------------------ ESP32 tag

    /**
     * direct=false: background "auto" connection — Android reconnects whenever the tag is around
     * (slow but battery-friendly). direct=true: immediate connection to a tag we just saw in a scan.
     */
    private fun connectTag(direct: Boolean) {
        val addr = prefs.espAddress ?: run { Status.log("No ESP32 chosen yet"); return }
        val dev = try { adapter()?.getRemoteDevice(addr) } catch (_: IllegalArgumentException) { null } ?: return
        try {
            gatt?.close()
            gattIsDirect = direct
            gatt = dev.connectGatt(this, !direct, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (!direct) Status.log("Watching ESP32 $addr")
        } catch (e: SecurityException) {
            Status.log("No Bluetooth permission: ${e.message}")
        }
    }

    // Active search right after parking: the ESP32 has only just booted, and Android's background
    // reconnect can take a long time to notice it. A filtered scan finds it within seconds.
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            if (r.device.address != prefs.espAddress) return
            handler.post {
                if (!scanning) return@post
                stopScan()
                if (!espConnected) {
                    Status.log("Found ESP32 (${r.rssi} dBm) — connecting")
                    connectTag(direct = true)
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            handler.post { scanning = false; Status.log("ESP32 scan failed ($errorCode)") }
        }
    }

    private fun startScan() {
        if (scanning || prefs.espAddress == null) return
        val scanner = adapter()?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Ble.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCb)
            scanning = true
            val gen = ++scanGen
            handler.postDelayed({ if (scanning && gen == scanGen) { stopScan(); Status.log("ESP32 not found by scan") } }, SCAN_MS)
        } catch (e: Exception) {
            Status.log("Couldn't scan: ${e.message}")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post   // a connection object we've already replaced
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    espConnected = true
                    linkDownSince = 0
                    dropWasPowerCut = false
                    if (mode == Mode.PARKED) tagSeenThisStop = true
                    Status.espConnected = true
                    stopScan()
                    Status.log("ESP32 connected")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val was = espConnected
                    val last = smoothed
                    espConnected = false
                    smoothed = null
                    linkDownSince = now()
                    // Still strong when it vanished → it lost power (car started), not you walking away.
                    dropWasPowerCut = was && last != null && last > prefs.awayRssi + POWERCUT_MARGIN_DB
                    powerCutHandled = false
                    Status.espConnected = false
                    Status.rssi = null
                    Status.rawRssi = null
                    if (was) Status.log("ESP32 disconnected" + (if (dropWasPowerCut) " at full signal (lost power?)" else "") + " (status $status)")
                    // Go back to the battery-friendly background connection.
                    if (gattIsDirect || status == 133) handler.postDelayed({ connectTag(direct = false) }, 1_000)
                    else try { g.connect() } catch (_: SecurityException) {}
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            handler.post {
                val s = smoothed
                // Exponential smoothing: RSSI swings ±10 dB on its own; this damps it over ~3 readings.
                smoothed = if (s == null) rssi.toDouble() else s * 0.7 + rssi * 0.3
                Status.rawRssi = rssi
                Status.rssi = smoothed?.toInt()
            }
        }
    }

    // ------------------------------------------------------------------ decision loop (1 Hz)

    private val tick = object : Runnable {
        override fun run() {
            try { evaluate() } catch (e: Exception) { Status.log("Error: ${e.message}") }
            handler.postDelayed(this, 1_000)
        }
    }

    private fun evaluate() {
        val t = now()
        if (espConnected) try { gatt?.readRemoteRssi() } catch (_: SecurityException) {}
        if (t - lastHealthAt > HEALTH_EVERY_MS) { lastHealthAt = t; checkServer() }
        val threshold = prefs.awayRssi
        val s = smoothed
        val atCar = espConnected && s != null && s >= prefs.atCarRssi
        if (atCar) lastAtCarAt = t
        if (homeCheckUntil > 0) checkHome(t)
        approachTick(t, s, threshold)
        // Near the car after parking: tell the server "still here" now and then, so its
        // dead-man's switch only fires if the phone really goes quiet.
        if (mode != Mode.DRIVING && mode != Mode.UNKNOWN && espConnected && s != null && s >= threshold && t - lastHereAt > HERE_EVERY_MS) {
            lastHereAt = t
            here()
        }

        when (mode) {
            Mode.PARKED -> {
                if (!tagSeenThisStop && !fallbackSent && t - parkedAt > FALLBACK_MS) {
                    fallbackSent = true
                    awaysThisStop++
                    stopScan()
                    setMode(Mode.AWAY, "ESP32 not reachable — using car Bluetooth only")
                    lockAway("car-bluetooth", "you parked and the ESP32 wasn't found", "parking", useServerGrace = true)
                    return
                }
                val settled = t - parkedAt > SETTLE_MS
                if (!espConnected) {
                    belowSince = 0
                    if (tagSeenThisStop && settled && linkDownSince > 0) {
                        if (dropWasPowerCut) {
                            // Most likely you started the car. Wait for its Bluetooth; if it never
                            // comes, let the server decide with check-first (skips if the car is on).
                            if (!powerCutHandled && t - linkDownSince > POWERCUT_WAIT_MS) {
                                powerCutHandled = true
                                awaysThisStop++
                                setMode(Mode.AWAY, "ESP32 lost power and car Bluetooth never came back")
                                lockAway("esp32-power-lost", "the ESP32 lost power and the car's Bluetooth never came back", "backup rule", checkFirst = true)
                            }
                        } else if (t - linkDownSince > LINK_HOLD_MS) {
                            if (!homeUndecided(t)) walkedAway("ESP32 out of range")
                        }
                    }
                } else if (s != null && s < threshold) {
                    if (belowSince == 0L) belowSince = t
                    if (settled && t - belowSince > RSSI_HOLD_MS && !homeUndecided(t)) walkedAway("signal ${s.toInt()} dBm (threshold $threshold)")
                } else {
                    belowSince = 0
                }
            }
            Mode.HOME -> homeTick(t, atCar)
            // Not driving, but you're right by the car (e.g. you opened it to grab something,
            // or came back after walking away): after REARM_MS close to the tag, arm. The next
            // walk-away sends a plain lock (harmless if it's already locked; see the top).
            // At home that would misfire (the tag reaches the house), so home mode takes over.
            Mode.AWAY, Mode.UNKNOWN -> {
                if (espConnected && s != null && s > threshold + HYSTERESIS_DB) {
                    if (aboveSince == 0L) aboveSince = t
                    if (t - aboveSince > VISIT_MS && Home.enabled(prefs) && whereCached(t) == true) {
                        homeQuiet("at home — locks once after each visit to the car")
                        homeTick(t, atCar)
                    } else if (t - aboveSince > REARM_MS) {
                        rearm(if (mode == Mode.AWAY) "back near the car — re-armed" else "near the car (no drive) — armed")
                    }
                } else {
                    aboveSince = 0
                }
            }
            else -> {}
        }
        refreshNotif()
    }

    private fun walkedAway(reason: String) {
        awaysThisStop++
        belowSince = 0
        aboveSince = 0
        if (awaysThisStop > MAX_AWAYS_PER_STOP) {
            setMode(Mode.AWAY, "$reason — not sending (limit for this stop)")
            return
        }
        setMode(Mode.AWAY, reason)
        lastAwayAt = now()
        // Lock straight away: harmless if it's already locked, and one command costs Hyundai less
        // than waking the car to check first.
        lockAway("esp32", "you walked away: $reason", "walk-away")
    }

    // ------------------------------------------------------------------ lock feedback

    /**
     * Sends "lock" and follows it to the end: a double tap + "Locking your car…" now, then a firm
     * buzz + "Car locked" (or a long buzz + what went wrong) when the server reports the result.
     */
    // Why the current lock was sent, so every message about it can say so.
    private var lockWhy = ""
    private var lockBy = ""
    private var lockDelayed = false

    /** why: the reason in words ("you walked away (signal -96 dBm)"); by: a short label for the home screen. */
    private fun lockAway(source: String, why: String, by: String, checkFirst: Boolean = false, useServerGrace: Boolean = false) {
        val gen = ++watchGen
        lockWhy = why; lockBy = by; lockDelayed = false
        Status.log("Locking because $why")
        Status.locking = true
        Status.lockMessage = "Locking your car…"
        Status.changed()
        buzz(BUZZ_SENT)
        lockNotif("Locking your car…", "Because ${why}.", problem = false)
        val json = buildString {
            append("""{"source":"$source","quiet":true,"reason":${org.json.JSONObject.quote(why)}""")
            if (!useServerGrace) append(""","graceSeconds":0""")
            if (checkFirst) append(""","checkFirst":true""")
            append("}")
        }
        send("/v1/autolock/away", json, "away") { body -> follow(gen, body) }
    }

    /** Runs on the network thread with the server's reply to "away"; polls for the outcome. */
    private fun follow(gen: Int, body: String) {
        val first = try { org.json.JSONObject(body) } catch (_: Exception) { null }
        when (first?.optString("phase")) {
            "disabled" -> { handler.post { lockDone(gen, "Not locked: auto-lock is off on the server", null, problem = true) }; return }
            "paused" -> { handler.post { lockDone(gen, "Not locked: auto-lock is paused", "Resume it in the app", problem = true) }; return }
        }
        val id = first?.optString("id").orEmpty()
        watch.execute {
            val start = now()
            var delayShown = false
            var waitingShown = ""
            while (gen == watchGen && now() - start < 50 * 60_000L) {
                try { Thread.sleep(if (now() - start < 3 * 60_000L) 3_000 else 30_000) } catch (_: InterruptedException) { return@execute }
                val j = try {
                    val r = Api.request(prefs, "GET", "/v1/autolock")
                    if (r.code != 200) continue
                    org.json.JSONObject(r.body)
                } catch (_: Exception) { continue }
                val last = j.optJSONObject("last")
                if (last != null && (id.isEmpty() || last.optString("id") == id)) {
                    val outcome = last.optString("outcome")
                    val detail = last.optString("detail").takeIf { it.isNotBlank() && it != "null" }
                    if (outcome == "waiting") {
                        if (waitingShown != detail) {
                            waitingShown = detail.orEmpty()
                            handler.post { if (gen == watchGen) lockNotif("Waiting to lock", "${detail ?: "Something is open"}. It locks once it's closed.", problem = true) }
                        }
                        continue
                    }
                    handler.post { lockResult(gen, outcome, detail) }
                    return@execute
                }
                if (j.optString("phase") == "delayed" && !delayShown) {
                    delayShown = true
                    lockDelayed = true
                    val at = Ui.localTime(j.optString("locksAt").ifBlank { null })
                    handler.post {
                        if (gen != watchGen) return@post
                        Status.lockMessage = "Lock delayed until $at"; Status.changed()
                        lockNotif("Lock delayed until $at", "Hyundai is busy; the server keeps trying. Lock it yourself if you can.", problem = true)
                        buzz(BUZZ_PROBLEM)
                    }
                }
            }
            handler.post { if (gen == watchGen) lockDone(gen, "Lock sent, no confirmation yet", "Check the car's status in the app", problem = true) }
        }
    }

    private fun lockResult(gen: Int, outcome: String, detail: String?) {
        when (outcome) {
            "locked" -> { prefs.confirmLock(true, lockBy); lockDone(gen, "Car locked", "Locked at ${clockNow()} because $lockWhy." + (if (lockDelayed) " It waited a moment because Hyundai was busy." else ""), problem = false); readSavedSoon() }
            "already-locked" -> { prefs.confirmLock(true, lockBy); lockDone(gen, "Car locked", "It was already locked (checked because $lockWhy).", problem = false); readSavedSoon() }
            "car-on" -> lockDone(gen, "Not locked: the car is on", null, problem = false, quiet = true)
            "cancelled" -> lockDone(gen, "Lock cancelled", "You came back to the car", problem = false, quiet = true)
            "open" -> lockDone(gen, "Car NOT locked", "${detail ?: "Something is open"}. Close it and lock it.", problem = true)
            else -> lockDone(gen, "Car NOT locked", detail ?: "The lock didn't go through. Lock it yourself.", problem = true)
        }
    }

    private fun clockNow() = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())

    /** A minute after a lock, read Hyundai's saved copy (doesn't wake the car): it has usually caught up by then. */
    private fun readSavedSoon() {
        handler.postDelayed({
            if (!prefs.configured) return@postDelayed
            net.execute { try { CarApi.status(prefs); Status.changed() } catch (_: Exception) {} }
        }, 60_000)
    }

    private fun lockDone(gen: Int, title: String, text: String?, problem: Boolean, quiet: Boolean = false) {
        if (gen != watchGen) return
        Status.locking = false
        Status.lockMessage = null
        Status.changed()
        Status.log("Lock result: $title" + (text?.let { " — $it" } ?: ""))
        buzz(if (problem) BUZZ_PROBLEM else if (quiet) longArrayOf(0, 60) else BUZZ_LOCKED)
        lockNotif(title, text ?: java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date()), problem, done = !problem)
    }

    private fun buzz(pattern: LongArray) {
        try {
            getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    /** One notification for the lock, updated in place: sending → result. Pops up on screen. */
    private fun lockNotif(title: String, text: String, problem: Boolean, done: Boolean = false) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, LOCK_CHANNEL)
            .setSmallIcon(if (problem) android.R.drawable.stat_notify_error else android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(if (problem) Ui.CORAL else Ui.GLACIER)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .apply { if (done) setTimeoutAfter(15 * 60_000L) }
            .build()
        getSystemService(NotificationManager::class.java)?.notify(LOCK_ID, n)
    }

    // ------------------------------------------------------------------ server calls

    private fun send(path: String, json: String, label: String, onOk: ((String) -> Unit)? = null) {
        val critical = label == "away"
        val gen = watchGen
        if (!prefs.configured) {
            Status.log("Server not set up — skipped $label")
            if (critical) lockDone(gen, "Car NOT locked", "The app isn't connected to your server yet (Settings).", problem = true)
            return
        }
        net.execute {
            var delay = 5_000L
            var alerted = false
            for (attempt in 1..12) {
                try {
                    val r = Api.request(prefs, "POST", path, json)
                    Status.log("$label → HTTP ${r.code}")
                    if (r.code in 200..299) {
                        serverOk()
                        onOk?.invoke(r.body)
                        return@execute
                    }
                    if (r.code == 401) {
                        serverProblem("server rejected the phone token")
                        if (critical) handler.post { lockDone(gen, "Car NOT locked", "The server rejected the phone token. Check Settings.", problem = true) }
                        return@execute
                    }
                    if (r.code in 400..499 && r.code != 429) {  // our problem; retrying won't help
                        if (critical) handler.post { lockDone(gen, "Car NOT locked", "The server refused the request (HTTP ${r.code}).", problem = true) }
                        return@execute
                    }
                    serverProblem("server error HTTP ${r.code}")
                } catch (e: Exception) {
                    serverProblem("can't reach the server (${e.javaClass.simpleName})")
                    Status.log("$label failed (${e.javaClass.simpleName}); retry in ${delay / 1000}s")
                }
                if (critical && !alerted) {
                    alerted = true
                    handler.post {
                        if (gen != watchGen) return@post
                        Status.lockMessage = "Can't reach the server, retrying"; Status.changed()
                        lockNotif("Can't reach the server", "Still trying to send the lock. Lock the car yourself to be safe.", problem = true)
                        buzz(BUZZ_PROBLEM)
                    }
                }
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return@execute }
                delay = (delay * 2).coerceAtMost(60_000)
            }
            Status.log("$label gave up after 12 tries")
            if (critical) handler.post { lockDone(gen, "Car NOT locked", "Couldn't reach the server. Lock the car yourself.", problem = true) }
        }
    }

    /** One quick "still here", no retries: the next one comes in two minutes anyway. */
    private fun here() {
        if (!prefs.configured) return
        Thread { try { Api.request(prefs, "POST", "/v1/autolock/here", "{}") } catch (_: Exception) {} }.start()
    }

    /** Confirms the server and token work, and picks up the pause state. */
    private fun checkServer() {
        if (!prefs.configured) return
        net.execute {
            val before = Status.serverProblem
            Api.check(prefs)
            if (before != Status.serverProblem) Status.log(Status.serverProblem?.let { "Server problem: $it" } ?: "Server reachable")
            handler.post { refreshNotif(force = true) }
        }
    }

    private fun pause(minutes: Int) {
        if (!prefs.configured) { Status.log("Server not set up — can't pause"); return }
        net.execute {
            Status.log(Api.pause(prefs, minutes))
            handler.post { refreshNotif(force = true) }
        }
    }

    private fun serverOk() {
        if (Status.serverProblem != null) Status.log("Server reachable again")
        Status.serverProblem = null
    }

    private fun serverProblem(what: String) {
        if (Status.serverProblem != what) Status.log("Server problem: $what")
        Status.serverProblem = what
    }

    // ------------------------------------------------------------------ notifications

    private fun setMode(m: Mode, why: String) {
        mode = m
        Status.mode = m.label
        Status.log("${m.label}: $why")
        refreshNotif(force = true)
    }

    private fun describe(): String {
        val tag = if (espConnected) "ESP32 ${Status.rssi ?: "…"} dBm" else "ESP32 not connected"
        val extra = when {
            Status.serverProblem != null -> " · ⚠ ${Status.serverProblem}"
            Status.pausedUntil != null -> " · paused"
            else -> ""
        }
        return "${mode.label} · $tag$extra"
    }

    private fun refreshNotif(force: Boolean = false) {
        val text = describe()
        if (!force && (text == lastNotifText || now() - lastNotifAt < 10_000)) return
        lastNotifText = text
        lastNotifAt = now()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif(text))
    }

    // ------------------------------------------------------------------ walking toward the car

    /**
     * Away from the car for 5+ minutes (the tag out of range, or its signal under the walk-away line),
     * then the tag is back and its signal climbs reading after reading — even while the bar is still
     * far down in the yellow — or it crosses back over the line: you're walking up to the car. Then:
     * one "Heading to the car?" alert with Unlock / Start climate, and the server signs in ahead so a
     * tap goes straight through. Never unlocks by itself. Not while driving, and not at home (or when
     * we can't tell), where the tag reaches the house. Only reads the signal: walk-away lock doesn't
     * depend on anything here.
     */
    private fun approachTick(t: Long, s: Double?, threshold: Int) {
        val far = !espConnected || s == null || s < threshold
        if (!espConnected || s == null) {
            approachHist.clear()
            approachRearm = true                        // out of range: the next return may alert again
        } else {
            approachHist.addLast(s)
            while (approachHist.size > 15) approachHist.removeFirst()
        }
        if (far && farSince == 0L) farSince = t
        val awayFor = if (farSince > 0) t - farSince else 0L
        if (far && awayFor > APPROACH_AWAY_MS && approachRearm) approachAlerted = false
        if (!far) {
            farSince = 0
            approachRearm = true                        // back at the car: leaving and returning counts again
        }
        if (approachAlerted || awayFor < APPROACH_AWAY_MS || s == null || !espConnected) return
        if (!prefs.approachAlert || !prefs.configured) return
        if (mode == Mode.DRIVING || Status.carConnected == true) return
        val n = approachHist.size
        val low = approachHist.minOrNull() ?: return
        val climbing = n >= 3 && approachHist[n - 1] > approachHist[n - 2] && approachHist[n - 2] > approachHist[n - 3] &&
            s - low >= APPROACH_RISE_DB
        if (!climbing && far) return
        if (Home.enabled(prefs) && whereCached(t) != false) return
        approachAlerted = true
        approachRearm = false
        farSince = 0
        Status.log("Walking toward the car (signal ${low.toInt()} → ${s.toInt()} dBm after ${awayFor / 60_000} min away)")
        approachNotif("Heading to the car?", if (prefs.hasAdmin) "Unlock it or start the climate from here." else "Add the admin token in Settings to unlock from here.", buttons = prefs.hasAdmin)
        approachAsking = true
        // The app vibrates itself, like the lock alerts: the channel alone stays silent when the phone
        // is on mute, and a pop-up nobody feels in a pocket is no alert at all.
        buzz(BUZZ_APPROACH)
        if (prefs.configured) Thread { try { Api.request(prefs, "POST", "/v1/prepare", "{}") } catch (_: Exception) {} }.start()
    }

    private fun approachNotif(title: String, text: String, buttons: Boolean = false, problem: Boolean = false, done: Boolean = false) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(label: String, act: String, code: Int, needsUnlock: Boolean) =
            Notification.Action.Builder(null, label,
                PendingIntent.getService(this, code, Intent(this, ProximityService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAuthenticationRequired(needsUnlock)   // unlocking the car needs the phone unlocked
                .build()
        val b = Notification.Builder(this, APPROACH_CHANNEL)
            .setSmallIcon(if (problem) android.R.drawable.stat_notify_error else android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(if (problem) Ui.CORAL else Ui.GLACIER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!buttons)
            .setContentIntent(open)
            .setTimeoutAfter(if (done) 2 * 60_000L else 10 * 60_000L)
        if (buttons) {
            b.addAction(action("Unlock", ACTION_APPROACH_UNLOCK, 11, needsUnlock = true))
            b.addAction(action("Start climate", ACTION_APPROACH_CLIMATE, 12, needsUnlock = false))
        }
        getSystemService(NotificationManager::class.java)?.notify(APPROACH_ID, b.build())
    }

    private fun clearApproachQuestion() {
        if (!approachAsking) return
        approachAsking = false
        getSystemService(NotificationManager::class.java)?.cancel(APPROACH_ID)
    }

    /** A button in the approach alert: run it through the server, show progress in the same alert. */
    private fun approachCommand(unlock: Boolean) {
        approachAsking = false   // a button was tapped: keep showing its progress and result
        if (!prefs.hasAdmin) { approachNotif("Needs the admin token", "Add it in the app's Settings.", problem = true, done = true); return }
        val temp = prefs.climateTemp.toDouble(); val mins = prefs.climateMinutes
        approachNotif(if (unlock) "Unlocking…" else "Starting climate…", "Usually 10–30 seconds.")
        Thread {
            val err = try {
                if (unlock) CarApi.command(prefs, "POST", "/v1/unlock")
                else CarApi.command(prefs, "POST", "/v1/climate/start", """{"temp":$temp,"duration":$mins}""")
                null
            } catch (e: Exception) { e.message ?: "Didn't go through" }
            handler.post {
                if (err == null) {
                    buzz(BUZZ_LOCKED)
                    if (unlock) {
                        prefs.confirmLock(false, "you, walking up")
                        approachNotif("Unlocked", "Unlocked at ${clockNow()}. If you leave without driving, walk-away locks it again.", done = true)
                        rearm("unlocked as you walked up — locks again if you leave without driving")
                    } else {
                        approachNotif("Climate on", "${if (temp % 1.0 == 0.0) temp.toInt() else temp}° for $mins min, started at ${clockNow()}.", done = true)
                    }
                    Status.changed()
                } else {
                    buzz(BUZZ_PROBLEM)
                    approachNotif(if (unlock) "Didn't unlock" else "Climate didn't start", err, problem = true, done = true)
                }
            }
        }.start()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Proximity monitor", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows that walk-away auto-lock is running"
        })
        nm.createNotificationChannel(NotificationChannel(APPROACH_CHANNEL, "Walking to the car", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "\"Heading to the car?\" with Unlock and Start climate buttons"
        })
        nm.createNotificationChannel(NotificationChannel(LOCK_CHANNEL, "Walk-away lock", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Locking your car… → Car locked (or what went wrong)"
            enableVibration(false)   // the app vibrates itself, with a different pattern per result
            setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Auto-lock problems", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When the car might not have locked"
        })
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val paused = Status.pausedUntil != null
        val action = Intent(this, ProximityService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE)
        val actionPi = PendingIntent.getService(this, 1, action, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(when {
                paused -> "Not armed · paused"
                mode == Mode.PARKED -> "Armed · locks when you walk away"
                mode == Mode.HOME -> "Armed (home) · locks once after each visit"
                else -> "Not armed · ${mode.label.lowercase()}"
            })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause 2 h", actionPi).build())
            .build()
    }

    private fun now() = System.currentTimeMillis()
}
