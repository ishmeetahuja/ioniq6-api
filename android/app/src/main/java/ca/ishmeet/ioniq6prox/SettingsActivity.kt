package ca.ishmeet.ioniq6prox

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ca.ishmeet.ioniq6prox.Sheets.sheet
import ca.ishmeet.ioniq6prox.Ui.card
import ca.ishmeet.ioniq6prox.Ui.darkWindow
import ca.ishmeet.ioniq6prox.Ui.dp
import ca.ishmeet.ioniq6prox.Ui.dpf
import ca.ishmeet.ioniq6prox.Ui.field
import ca.ishmeet.ioniq6prox.Ui.pillButton
import ca.ishmeet.ioniq6prox.Ui.pressable
import ca.ishmeet.ioniq6prox.Ui.row
import ca.ishmeet.ioniq6prox.Ui.text

/** Everything that sets the app up: server, tokens, car, photo, ESP32, distance, background, log. */
class SettingsActivity : Activity() {

    companion object { private const val PICK_PHOTO = 7 }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bar: Sheets.MessageBar

    private lateinit var urlEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var adminEdit: EditText
    private lateinit var carValue: TextView
    private lateinit var photoValue: TextView
    private lateinit var espValue: TextView
    private lateinit var thrEdit: EditText
    private lateinit var liveSignal: TextView
    private lateinit var logView: TextView
    private lateinit var homeValue: TextView
    private lateinit var locValue: TextView
    private lateinit var wifiEdit: EditText
    private lateinit var spotEdit: EditText
    private lateinit var delayEdit: EditText
    private lateinit var atCarEdit: EditText
    private lateinit var approachValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        darkWindow()
        buildUi()
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1_000) }
    }

    // ------------------------------------------------------------------ layout

    private fun section(col: LinearLayout, title: String, note: String? = null, build: LinearLayout.() -> Unit) {
        col.addView(text(title, 15f, Ui.MUTED, Ui.medium), Ui.lp(top = dp(26)).apply { marginStart = dp(6) })
        col.addView(card().apply {
            if (note != null) addView(text(note, 14f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }, Ui.lp())
            build()
        }, Ui.lp(top = dp(10)))
    }

    private fun LinearLayout.label(t: String) = addView(text(t, 13f, Ui.MUTED, Ui.medium), Ui.lp(top = dp(14)).apply { marginStart = dp(4) })

    /** A row: name on the left, current value under it, an action on the right. */
    private fun LinearLayout.item(name: String, action: String, onClick: () -> Unit): TextView {
        val value = text("", 14f, Ui.MUTED)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(name, 16f, Ui.TEXT, Ui.medium))
                addView(value, Ui.lp(top = dp(4)))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pillButton(action) { onClick() }.apply { textSize = 14f; minHeight = dp(40) })
        }, Ui.lp(top = if (childCount == 0) 0 else dp(16)))
        return value
    }

    private fun buildUi() {
        val host = FrameLayout(this)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        host.addView(scroll)
        bar = Sheets.MessageBar(host)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(dp(16), b.top + dp(12), dp(16), b.bottom + dp(32))
            insets
        }
        setContentView(host)

        val back = FrameLayout(this).apply {
            background = pressable(Ui.SURFACE, dpf(999f))
            contentDescription = "Back"
            isClickable = true
            setOnClickListener { finish() }
            addView(View(context).apply { background = Icon(Icon.Kind.BACK, Ui.TEXT, dp(22)) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(text("Settings", 24f, Ui.TEXT, Ui.semibold), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) })
        })

        section(col, "Server", "The installer printed the address and the phone token. The admin token is in the server's .env file (/opt/ioniq6/.env, or /mnt/user/appdata/ioniq6/.env on Unraid).") {
            label("Server address")
            urlEdit = field("https://…sslip.io", prefs.serverUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            addView(urlEdit, Ui.lp(top = dp(6)))
            label("Phone token, for walk-away lock")
            tokenEdit = field("Phone token", prefs.token, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            addView(tokenEdit, Ui.lp(top = dp(6)))
            label("Admin token, for unlock, climate, charging and find car (optional)")
            adminEdit = field("Admin token", prefs.adminToken, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            addView(adminEdit, Ui.lp(top = dp(6)))
            addView(text("With the admin token saved, anyone who has your unlocked phone can unlock the car.", 13f, Ui.FAINT).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(top = dp(8)).apply { marginStart = dp(4) })
            addView(row(dp(10), pillButton("Save") { saveServer(test = false) }, pillButton("Save and test", Ui.GLACIER, Ui.ON_ACCENT) { saveServer(test = true) }), Ui.lp(top = dp(16)))
        }

        section(col, "Car") {
            carValue = item("Car Bluetooth", "Choose") { chooseCar() }
            photoValue = item("Car photo", "Choose") { photoOptions() }
        }

        section(col, "ESP32 tag", "Keep it powered and close by, then search.") {
            espValue = item("Tag", "Find") { findEsp() }
        }

        section(col, "Walk-away distance", "Stand where you want the car to lock, then use the current signal.") {
            liveSignal = text("", 28f, Ui.TEXT, Ui.display)
            addView(liveSignal, Ui.lp(top = dp(12)))
            label("Lock when the signal is weaker than (dBm)")
            thrEdit = field("-85", prefs.awayRssi.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
            addView(thrEdit, Ui.lp(top = dp(6)))
            addView(row(dp(10), pillButton("Save") { saveThreshold() }, pillButton("Use current signal", Ui.GLACIER, Ui.ON_ACCENT) { useCurrentSignal() }), Ui.lp(top = dp(16)))
        }

        section(col, "Home", "Nothing to switch on: once your home Wi-Fi name (or location) is saved, the app knows by itself when you're home. There, it locks once per visit to the car (after you park, or after you've been right at the car) instead of every time the signal drops, because the tag often reaches the house.") {
            homeValue = item("Home detection", "Am I home?") { checkHomeNow() }
            locValue = item("Location access", "Allow") { askLocation() }
            label("Home Wi-Fi name")
            wifiEdit = field("Wi-Fi name", prefs.homeWifi)
            addView(wifiEdit, Ui.lp(top = dp(6)))
            label("Home location: latitude, longitude")
            val spot = prefs.homeLat?.let { "$it, ${prefs.homeLon}" } ?: ""
            spotEdit = field("43.65107, -79.34702", spot, InputType.TYPE_CLASS_TEXT)
            addView(spotEdit, Ui.lp(top = dp(6)))
            addView(row(dp(10), pillButton("Use current Wi-Fi") { useCurrentWifi() }, pillButton("Use current location") { useCurrentSpot() }), Ui.lp(top = dp(12)))
            label("“At the car”: signal at least (dBm). Stand at the driver's door and use the current signal")
            atCarEdit = field(prefs.atCarRssi.toString(), prefs.atCarRssiSet?.toString() ?: "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
            addView(atCarEdit, Ui.lp(top = dp(6)))
            addView(pillButton("Use current signal") {
                val r = Status.rssi ?: run { bar.show("No live signal. Turn walk-away lock on and connect the ESP32.", Ui.AMBER); return@pillButton }
                atCarEdit.setText(r.toString())
                if (storeHome(quiet = true)) bar.show("At the car = $r dBm or stronger", Ui.GLACIER)
            }, Ui.lp(top = dp(12)))
            label("Lock once you've been away from the car this many seconds")
            delayEdit = field("60", prefs.homeDelaySec.toString(), InputType.TYPE_CLASS_NUMBER)
            addView(delayEdit, Ui.lp(top = dp(6)))
            addView(row(dp(10), pillButton("Save") { saveHome() }, pillButton("Am I home?", Ui.GLACIER, Ui.ON_ACCENT) { checkHomeNow() }), Ui.lp(top = dp(16)))
        }

        section(col, "Car status updates", "Lock state, driving and parked come from your phone (car Bluetooth, GPS, the ESP32) and from confirmed locks, so they're always current. Battery, range and climate come from Hyundai: the app reads Hyundai's saved copy at most twice an hour and a minute after each lock (neither wakes the car). It asks the car itself when you tap Refresh, and on its own only a few times a day when the data is clearly out of date, never overnight.") {}

        section(col, "Walking to the car", "Away from home, when you come back to the car after 5+ minutes away, your phone asks \"Heading to the car?\" with Unlock and Start climate buttons. It never unlocks by itself, and Unlock needs your phone to be unlocked. Off at home, because the ESP32 reaches the house.") {
            approachValue = item("Approach alert", "Switch") {
                prefs.approachAlert = !prefs.approachAlert
                render()
                bar.show(if (prefs.approachAlert) "Approach alert on" else "Approach alert off", Ui.GLACIER)
            }
        }

        section(col, "Background and testing") {
            item("Battery", "Allow") { askBatteryExemption() }.text = "Let it keep watching while the screen is off"
            item("Test", "Arm") {
                if (Status.running) ProximityService.send(this@SettingsActivity, ProximityService.ACTION_ARM).also { bar.show("Armed. Walk away to test.", Ui.GLACIER) }
                else bar.show("Turn walk-away lock on first.", Ui.AMBER)
            }.text = "Act as if you just parked"
        }

        col.addView(text("Log", 15f, Ui.MUTED, Ui.medium), Ui.lp(top = dp(26)).apply { marginStart = dp(6) })
        logView = text("", 12f, Ui.MUTED).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            background = Ui.rounded(Ui.SURFACE, dpf(18f))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        col.addView(logView, Ui.lp(top = dp(10)))
        render()
    }

    private fun render() {
        carValue.text = prefs.carName?.let { "$it" } ?: "Not chosen"
        photoValue.text = if (CarPhoto.file(this).exists()) "Your photo" else "Default illustration"
        espValue.text = prefs.espAddress?.let { a -> if (Status.espConnected) "$a, connected" else a } ?: "Not chosen"
        liveSignal.text = Status.rssi?.let { "Now: $it dBm" } ?: "No live signal"
        liveSignal.setTextColor(if (Status.rssi != null) Ui.TEXT else Ui.FAINT)
        logView.text = Status.logText().ifEmpty { "Nothing yet." }
        val on = Home.enabled(prefs)
        homeValue.text = when {
            !on -> "Off until you save your home Wi-Fi name or location below"
            !Home.hasBackground(this) -> "Needs location “Allow all the time” (below) to work with the screen off"
            else -> "Automatic, by " + listOfNotNull(prefs.homeWifi.takeIf { it.isNotBlank() }?.let { "Wi-Fi “$it”" }, prefs.homeLat?.let { "location" }).joinToString(" or ")
        }
        approachValue.text = when {
            !prefs.approachAlert -> "Off"
            !prefs.hasAdmin -> "On, but Unlock and Climate need the admin token (Server, above)"
            else -> "On"
        }
        approachValue.setTextColor(if (prefs.approachAlert) Ui.GLACIER else Ui.MUTED)
        homeValue.setTextColor(if (on && Home.hasBackground(this)) Ui.GLACIER else Ui.AMBER)
        locValue.text = when {
            Home.hasBackground(this) -> "All the time"
            Home.hasLocation(this) -> "Only while the app is open. Tap Allow, then pick “Allow all the time”"
            else -> "Not allowed (needed to see the Wi-Fi name and location)"
        }
        locValue.setTextColor(if (Home.hasBackground(this)) Ui.MUTED else Ui.AMBER)
    }

    // ------------------------------------------------------------------ home

    /** Saves the fields. Returns false (and says why) if something doesn't make sense. */
    private fun storeHome(quiet: Boolean = false): Boolean {
        val spotText = spotEdit.text.toString().trim()
        val spot = if (spotText.isEmpty()) null else Home.parse(spotText)
        if (spotText.isNotEmpty() && spot == null) { bar.show("Location should look like: 43.65107, -79.34702", Ui.CORAL); return false }
        val atCarText = atCarEdit.text.toString().trim()
        val atCar = if (atCarText.isEmpty()) null else atCarText.toIntOrNull()
        if (atCarText.isNotEmpty() && (atCar == null || atCar !in -100..-30)) { bar.show("“At the car” should be between -100 and -30", Ui.CORAL); return false }
        if (atCar != null && atCar <= prefs.awayRssi) { bar.show("“At the car” must be stronger than the walk-away level (${prefs.awayRssi})", Ui.CORAL); return false }
        val delay = delayEdit.text.toString().trim().toIntOrNull()
        if (delay == null || delay !in 0..600) { bar.show("Use 0 to 600 seconds", Ui.CORAL); return false }
        prefs.homeWifi = wifiEdit.text.toString()
        prefs.setHomeSpot(spot)
        prefs.homeDelaySec = delay
        prefs.atCarRssiSet = atCar
        if (!quiet) bar.show("Saved", Ui.GLACIER)
        render()
        return true
    }

    private fun saveHome() {
        if (!storeHome(quiet = true)) return
        if (Home.enabled(prefs) && !Home.hasBackground(this)) { bar.show("Saved. Now allow location all the time.", Ui.AMBER); askLocation() }
        else bar.show("Saved", Ui.GLACIER)
    }

    private fun askLocation() {
        when {
            Home.hasBackground(this) -> bar.show("Already allowed all the time", Ui.GLACIER)
            !Home.hasLocation(this) -> requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
            else -> sheet("One more step", "Android opens the app's location setting next. Choose “Allow all the time” so the app can see your Wi-Fi name and location after you park, with the screen off.") { d ->
                addView(pillButton("Continue", Ui.GLACIER, Ui.ON_ACCENT) {
                    d.dismiss()
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 3)
                }, Ui.lp(top = dp(18)))
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && Home.hasLocation(this)) askLocation()   // on to "all the time"
        render()
    }

    private fun useCurrentWifi() {
        if (!Home.hasLocation(this)) { bar.show("Allow location first: Android hides the Wi-Fi name without it", Ui.AMBER); askLocation(); return }
        val w = Home.currentWifi(this) ?: run { bar.show("Not connected to Wi-Fi right now", Ui.AMBER); return }
        wifiEdit.setText(w)
        storeHome(quiet = true)
        bar.show("Home Wi-Fi: $w. Home detection is on.", Ui.GLACIER)
        if (!Home.hasBackground(this)) askLocation()
    }

    private fun useCurrentSpot() {
        if (!Home.hasLocation(this)) { askLocation(); return }
        bar.show("Getting your location…", ms = 30_000)
        Home.locate(this) { loc ->
            if (loc == null) { bar.show("Couldn't get a location. Is location turned on?", Ui.CORAL); return@locate }
            spotEdit.setText(String.format(java.util.Locale.US, "%.6f, %.6f", loc.latitude, loc.longitude))
            storeHome(quiet = true)
            bar.show("Home location set (accurate to about ${loc.accuracy.toInt()} m). Home detection is on.", Ui.GLACIER)
            if (!Home.hasBackground(this)) askLocation()
        }
    }

    private fun checkHomeNow() {
        if (!storeHome(quiet = true)) return
        if (!Home.hasLocation(this)) { bar.show("Allow location first", Ui.AMBER); askLocation(); return }
        val wifi = Home.currentWifi(this)
        if (Home.isHomeWifi(prefs, wifi)) { bar.show("Yes: on home Wi-Fi “$wifi”", Ui.GLACIER, 5_000); return }
        if (prefs.homeLat == null) { bar.show("No: Wi-Fi is ${wifi?.let { "“$it”" } ?: "off"} and no home location is set", Ui.AMBER, 5_000); return }
        bar.show("Checking location…", ms = 30_000)
        Home.locate(this) { loc ->
            if (loc == null) { bar.show("Couldn't get a location", Ui.CORAL); return@locate }
            val d = Home.describe(Home.distance(prefs, loc) ?: 0f)
            if (Home.inside(prefs, loc)) bar.show("Yes: $d from the home spot", Ui.GLACIER, 5_000)
            else bar.show("No: $d from the home spot, Wi-Fi ${wifi?.let { "“$it”" } ?: "off"}", Ui.AMBER, 5_000)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun restartIfRunning() {
        if (Status.running) {
            ProximityService.stop(this)
            handler.postDelayed({ ProximityService.start(this) }, 500)
        }
    }

    private fun saveServer(test: Boolean) {
        val url = urlEdit.text.toString().trim()
        if (!url.startsWith("https://")) { bar.show("The server address must start with https://", Ui.CORAL); return }
        prefs.serverUrl = url
        prefs.token = tokenEdit.text.toString()
        prefs.adminToken = adminEdit.text.toString()
        if (!test) { bar.show("Saved"); return }
        bar.show("Testing…")
        Thread {
            val msg = Api.check(prefs)
            var adminMsg = ""
            if (prefs.hasAdmin) {
                adminMsg = try {
                    val r = Api.request(prefs, "GET", "/v1/auth", token = prefs.adminToken)
                    when (r.code) { 200 -> " Admin token works."; 401, 403 -> " The admin token was rejected."; else -> "" }
                } catch (_: Exception) { "" }
            }
            Status.log(msg + adminMsg)
            runOnUiThread { bar.show(msg + adminMsg, if (Status.serverProblem == null && !adminMsg.contains("rejected")) Ui.GLACIER else Ui.CORAL, 5_000) }
        }.start()
    }

    private fun photoOptions() {
        if (!CarPhoto.file(this).exists()) { pickPhoto(); return }
        sheet("Car photo") { d ->
            addView(row(dp(10),
                pillButton("Use illustration") { CarPhoto.clear(this@SettingsActivity); d.dismiss(); bar.show("Back to the illustration"); render() },
                pillButton("Choose another", Ui.GLACIER, Ui.ON_ACCENT) { d.dismiss(); pickPhoto() }), Ui.lp(top = dp(16)))
        }
    }

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        @Suppress("DEPRECATION") startActivityForResult(i, PICK_PHOTO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (CarPhoto.save(this, uri)) bar.show("Photo set. It shows on the home screen.", Ui.GLACIER)
        else bar.show("Couldn't read that photo. Try another.", Ui.CORAL)
        render()
    }

    private fun chooseCar() {
        if (!ProximityService.hasPermissions(this)) { askPermissions(); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        try {
            val devices = adapter.bondedDevices.toList().sortedBy { it.name ?: "" }
            if (devices.isEmpty()) { bar.show("No paired Bluetooth devices. Pair the phone with the car first.", Ui.AMBER); return }
            sheet("Which one is your car?", "From the phone's paired Bluetooth devices.") { d ->
                for (dev in devices) {
                    addView(pillButton(dev.name ?: dev.address) {
                        prefs.carAddress = dev.address
                        prefs.carName = dev.name ?: dev.address
                        d.dismiss(); restartIfRunning(); render()
                        bar.show("Car set to ${prefs.carName}", Ui.GLACIER)
                    }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, Ui.lp(top = dp(8)))
                }
            }
        } catch (e: SecurityException) { askPermissions() }
    }

    private fun findEsp() {
        if (!ProximityService.hasPermissions(this)) { askPermissions(); return }
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: run { bar.show("Turn Bluetooth on", Ui.AMBER); return }
        val found = LinkedHashMap<String, Int>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { found[r.device.address] = r.rssi }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Ble.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { scanner.startScan(listOf(filter), settings, cb) } catch (e: SecurityException) { askPermissions(); return }
        bar.show("Looking for the ESP32 for 8 seconds…", ms = 8_000)
        handler.postDelayed({
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            if (found.isEmpty()) {
                sheet("No ESP32 found", "Make sure it's powered (the blue light blinks every 2 seconds) and close by. If this phone is already connected to it, it stops showing up: turn walk-away lock off and try again.") { d ->
                    addView(pillButton("OK") { d.dismiss() }, Ui.lp(top = dp(18)))
                }
                return@postDelayed
            }
            val list = found.entries.sortedByDescending { it.value }
            sheet("Pick your ESP32", "Strongest signal first.") { d ->
                for (e in list) {
                    addView(pillButton("${e.key}   ${e.value} dBm") {
                        prefs.espAddress = e.key
                        Status.log("ESP32 set to ${e.key}")
                        d.dismiss(); restartIfRunning(); render()
                        bar.show("ESP32 set", Ui.GLACIER)
                    }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, Ui.lp(top = dp(8)))
                }
            }
        }, 8_000)
    }

    private fun saveThreshold() {
        val v = thrEdit.text.toString().trim().toIntOrNull()
        if (v == null || v > -30 || v < -110) { bar.show("Use a number between -110 and -30", Ui.CORAL); return }
        prefs.awayRssi = v
        bar.show("Locks below $v dBm", Ui.GLACIER)
    }

    private fun useCurrentSignal() {
        val r = Status.rssi ?: run { bar.show("No live signal. Turn walk-away lock on and connect the ESP32.", Ui.AMBER); return }
        thrEdit.setText(r.toString())
        saveThreshold()
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) { bar.show("Already allowed to run in the background", Ui.GLACIER); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
