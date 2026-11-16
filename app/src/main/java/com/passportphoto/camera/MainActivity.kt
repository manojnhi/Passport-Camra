package com.passportphoto.camera

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.ExifInterface
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.Executors
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class GridView(c: Context) : View(c) {
    private val p = Paint()

    init {
        p.color = Color.argb(150, 255, 255, 255)
        p.strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w / 3, 0f, w / 3, h, p)
        canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, p)
        canvas.drawLine(0f, h / 3, w, h / 3, p)
        canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, p)
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        const val RELAY = "https://ntfy.sh"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var previewView: PreviewView
    private lateinit var topStatus: TextView
    private lateinit var setStatus: TextView
    private lateinit var flashBtn: TextView
    private lateinit var flashView: View
    private lateinit var grid: GridView
    private lateinit var settingsPanel: LinearLayout

    private val io = Executors.newCachedThreadPool()
    private val sound = MediaActionSound()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var useBack = true
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var capturing = false
    private var topic = ""
    private var key = ""
    private var sentCount = 0

    // Online: photos go through the internet relay (encrypted). Offline: photos go straight to the shop PC over the same WiFi.
    private var mode = "online"
    private var lanBase = ""
    private var lanToken = ""
    private val sessionId = java.lang.Long.toHexString(System.currentTimeMillis())
    private lateinit var modeOnBtn: Button
    private lateinit var modeOffBtn: Button
    private lateinit var testBtn: Button
    private lateinit var hintView: TextView

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] == true || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else say("Camera permission is needed. Please allow it in the phone Settings.", "#fb7185")
    }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) uploadPicked(uris)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pill(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 15f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(8), dp(14), dp(8))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#66000000"))
        bg.cornerRadius = dp(22).toFloat()
        t.background = bg
        return t
    }

    private fun check(label: String, key: String, def: Boolean, onChange: (Boolean) -> Unit): CheckBox {
        val cb = CheckBox(this)
        cb.text = label
        cb.setTextColor(Color.WHITE)
        cb.isChecked = prefs.getBoolean(key, def)
        cb.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(key, on).apply()
            onChange(on)
        }
        return cb
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("pb", Context.MODE_PRIVATE)
        topic = prefs.getString("topic", "") ?: ""
        key = prefs.getString("key", "") ?: ""
        mode = prefs.getString("mode", "online") ?: "online"
        lanBase = prefs.getString("lanBase", "") ?: ""
        lanToken = prefs.getString("lanToken", "") ?: ""

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // ---- preview area (4:3, same as the saved photo) ----
        val sw = resources.displayMetrics.widthPixels
        val box = FrameLayout(this)
        root.addView(box, FrameLayout.LayoutParams(sw, sw * 4 / 3, Gravity.CENTER))

        previewView = PreviewView(this)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        box.addView(previewView, FrameLayout.LayoutParams(match, match))

        grid = GridView(this)
        grid.visibility = if (prefs.getBoolean("grid", true)) View.VISIBLE else View.GONE
        box.addView(grid, FrameLayout.LayoutParams(match, match))

        flashView = View(this)
        flashView.setBackgroundColor(Color.WHITE)
        flashView.alpha = 0f
        box.addView(flashView, FrameLayout.LayoutParams(match, match))

        // pinch to zoom + tap to focus
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val c = camera ?: return true
                val z = c.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                c.cameraControl.setZoomRatio(z * detector.scaleFactor)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val c = camera ?: return true
                val pt = previewView.meteringPointFactory.createPoint(e.x, e.y)
                c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                return true
            }
        })
        previewView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            tap.onTouchEvent(ev)
            true
        }

        // ---- top bar ----
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(12), dp(36), dp(12), dp(8))

        flashBtn = pill("\u26A1 Off")
        flashBtn.setOnClickListener { cycleFlash() }
        top.addView(flashBtn)

        topStatus = TextView(this)
        topStatus.textSize = 12f
        topStatus.gravity = Gravity.CENTER
        topStatus.maxLines = 2
        topStatus.setTextColor(Color.parseColor("#9fb3cf"))
        topStatus.setPadding(dp(8), 0, dp(8), 0)
        top.addView(topStatus, LinearLayout.LayoutParams(0, wrap, 1f))

        val gear = pill("\u2699")
        gear.textSize = 20f
        gear.setOnClickListener { showSettings(true) }
        top.addView(gear)

        root.addView(top, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))

        // ---- bottom: shutter + flip ----
        val shutter = FrameLayout(this)
        val ring = View(this)
        val ringBg = GradientDrawable()
        ringBg.shape = GradientDrawable.OVAL
        ringBg.setStroke(dp(4), Color.WHITE)
        ringBg.setColor(Color.TRANSPARENT)
        ring.background = ringBg
        shutter.addView(ring, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
        val dot = View(this)
        val dotBg = GradientDrawable()
        dotBg.shape = GradientDrawable.OVAL
        dotBg.setColor(Color.WHITE)
        dot.background = dotBg
        shutter.addView(dot, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER))
        shutter.setOnClickListener { capture() }
        val lpS = FrameLayout.LayoutParams(dp(84), dp(84), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lpS.bottomMargin = dp(36)
        root.addView(shutter, lpS)

        val flip = pill("\u21BB")
        flip.textSize = 26f
        flip.setOnClickListener {
            useBack = !useBack
            bindCamera()
        }
        val lpF = FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.END)
        lpF.bottomMargin = dp(52)
        lpF.rightMargin = dp(28)
        root.addView(flip, lpF)

        val gal = pill("\uD83D\uDDBC Gallery")
        gal.setOnClickListener { pickLauncher.launch("image/*") }
        val lpG = FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.START)
        lpG.bottomMargin = dp(60)
        lpG.leftMargin = dp(28)
        root.addView(gal, lpG)

        // ---- settings panel ----
        settingsPanel = LinearLayout(this)
        settingsPanel.orientation = LinearLayout.VERTICAL
        settingsPanel.setBackgroundColor(Color.parseColor("#F20f172a"))
        settingsPanel.setPadding(dp(24), dp(56), dp(24), dp(24))
        settingsPanel.isClickable = true
        settingsPanel.visibility = View.GONE

        val title = TextView(this)
        title.text = "Settings"
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        settingsPanel.addView(title)

        setStatus = TextView(this)
        setStatus.textSize = 14f
        setStatus.setTextColor(Color.parseColor("#9fb3cf"))
        setStatus.setPadding(0, dp(10), 0, dp(14))
        settingsPanel.addView(setStatus)

        // Online / Offline switch
        val modeRow = LinearLayout(this)
        modeRow.orientation = LinearLayout.HORIZONTAL
        modeOnBtn = Button(this)
        modeOnBtn.text = "\uD83C\uDF10 Online"
        modeOnBtn.setTextColor(Color.WHITE)
        modeOnBtn.setOnClickListener { setMode("online") }
        modeOffBtn = Button(this)
        modeOffBtn.text = "\uD83D\uDCF6 Offline"
        modeOffBtn.setTextColor(Color.WHITE)
        modeOffBtn.setOnClickListener { setMode("offline") }
        val lpMode = LinearLayout.LayoutParams(0, dp(50), 1f)
        lpMode.rightMargin = dp(4)
        modeRow.addView(modeOnBtn, lpMode)
        val lpMode2 = LinearLayout.LayoutParams(0, dp(50), 1f)
        lpMode2.leftMargin = dp(4)
        modeRow.addView(modeOffBtn, lpMode2)
        val lpRow = LinearLayout.LayoutParams(match, wrap)
        lpRow.bottomMargin = dp(10)
        settingsPanel.addView(modeRow, lpRow)

        val paste = Button(this)
        paste.text = "Paste QR link"
        paste.setOnClickListener { linkDialog() }
        settingsPanel.addView(paste, LinearLayout.LayoutParams(match, dp(50)))

        val unlink = Button(this)
        unlink.text = "Unlink shop QR"
        unlink.setOnClickListener {
            topic = ""
            key = ""
            lanBase = ""
            lanToken = ""
            prefs.edit().remove("topic").remove("key").remove("lanBase").remove("lanToken").apply()
            refreshStatus()
        }
        val lpM = LinearLayout.LayoutParams(match, dp(50))
        lpM.topMargin = dp(8)
        settingsPanel.addView(unlink, lpM)

        testBtn = Button(this)
        testBtn.text = "Test offline connection"
        testBtn.setOnClickListener { testLan() }
        val lpT = LinearLayout.LayoutParams(match, dp(50))
        lpT.topMargin = dp(8)
        settingsPanel.addView(testBtn, lpT)

        val lpC = LinearLayout.LayoutParams(wrap, wrap)
        lpC.topMargin = dp(14)
        settingsPanel.addView(check("Send photo to the shop automatically", "auto", true) { }, lpC)
        settingsPanel.addView(check("Also keep the photo in the phone gallery", "keep", false) { }, lpC)
        settingsPanel.addView(check("Show grid", "grid", true) { on ->
            grid.visibility = if (on) View.VISIBLE else View.GONE
        }, lpC)

        hintView = TextView(this)
        hintView.textSize = 12f
        hintView.setTextColor(Color.parseColor("#9fb3cf"))
        hintView.setPadding(0, dp(18), 0, dp(18))
        settingsPanel.addView(hintView)
        styleMode()

        val close = Button(this)
        close.text = "Close"
        close.setTextColor(Color.WHITE)
        close.setBackgroundColor(Color.parseColor("#2269e8"))
        close.setOnClickListener { showSettings(false) }
        settingsPanel.addView(close, LinearLayout.LayoutParams(match, dp(52)))

        root.addView(settingsPanel, FrameLayout.LayoutParams(match, match))

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsPanel.visibility == View.VISIBLE) {
                    showSettings(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (android.os.Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.isEmpty()) {
            startCamera()
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun showSettings(show: Boolean) {
        settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun say(text: String, color: String = "#9fb3cf") {
        runOnUiThread {
            topStatus.text = text
            topStatus.setTextColor(Color.parseColor(color))
            setStatus.text = text
            setStatus.setTextColor(Color.parseColor(color))
        }
    }

    // ---------------- camera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()
        val selector = if (useBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, capture)
            imageCapture = capture
        } catch (e: Exception) {
            say("Could not open the camera: " + e.message, "#fb7185")
        }
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        flashBtn.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "\u26A1 Auto"
            ImageCapture.FLASH_MODE_ON -> "\u26A1 On"
            else -> "\u26A1 Off"
        }
    }

    private fun capture() {
        val ic = imageCapture
        if (ic == null || capturing) return
        capturing = true
        sound.play(MediaActionSound.SHUTTER_CLICK)
        flashView.alpha = 0.85f
        flashView.animate().alpha(0f).setDuration(220).start()

        val v = ContentValues()
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "passport_" + System.currentTimeMillis() + ".jpg")
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PassportCamera")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v
        ).build()

        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                capturing = false
                val uri = output.savedUri
                if (uri == null) {
                    say("The photo was not saved.", "#fb7185")
                    return
                }
                if (prefs.getBoolean("auto", true)) {
                    upload(uri)
                } else {
                    say("Photo saved in the gallery.", "#4ade80")
                }
            }

            override fun onError(exc: ImageCaptureException) {
                capturing = false
                say("Could not take the photo: " + exc.message, "#fb7185")
            }
        })
    }

    // ---------------- shop QR link (works on any network) ----------------

    private fun b64uDecode(s: String): ByteArray? {
        return try {
            Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    // The QR link looks like  https://.../sender.html#t=<topic>&k=<public key>
    private fun parseLink(text: String): Pair<String, String>? {
        val t = Regex("[#&?]t=([A-Za-z0-9_-]{8,64})").find(text)?.groupValues?.get(1) ?: return null
        val k = Regex("[#&]k=([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1) ?: return null
        val raw = b64uDecode(k) ?: return null
        if (raw.size != 65 || raw[0].toInt() != 4) return null
        return Pair(t, k)
    }

    // Accepts the online link (https://.../sender.html#t=..&k=..) and the offline link (http://192.168.x.x:5006/send#m=lan&k=..)
    // Result: Triple(isOnline, topic-or-address, key-or-token)
    private fun parseAny(text: String): Triple<Boolean, String, String>? {
        val s0 = text.trim()
        if (Regex("[#&]m=lan(&|\$)").containsMatchIn(s0)) {
            val base = Regex("(https?://[^/\\s#?]+)").find(s0)?.groupValues?.get(1) ?: return null
            val tok = Regex("[#&]k=([A-Za-z0-9_-]{8,64})").find(s0)?.groupValues?.get(1) ?: return null
            return Triple(false, base, tok)
        }
        val p = parseLink(s0) ?: return null
        return Triple(true, p.first, p.second)
    }

    private fun linked(): Boolean {
        return if (mode == "offline") lanBase.isNotEmpty() && lanToken.isNotEmpty() else topic.isNotEmpty() && key.isNotEmpty()
    }

    private fun setMode(m: String) {
        mode = m
        prefs.edit().putString("mode", m).apply()
        styleMode()
        refreshStatus()
    }

    private fun styleMode() {
        val off = mode == "offline"
        modeOnBtn.setBackgroundColor(Color.parseColor(if (!off) "#2269e8" else "#334155"))
        modeOffBtn.setBackgroundColor(Color.parseColor(if (off) "#15803d" else "#334155"))
        testBtn.visibility = if (off) View.VISIBLE else View.GONE
        hintView.text = if (off) {
            "Offline mode: no internet needed. The phone and the shop PC must be on the same WiFi (or the PC on this phone's hotspot). On the shop PC open the QR window, choose Offline, tap 'Copy link', send that link to this phone (for example by WhatsApp), copy it here, then tap 'Paste QR link'. If the PC's WiFi address changes, copy and paste the link again. Photos are not encrypted in this mode (local network only)."
        } else {
            "Online mode: on the shop PC open the QR window and tap 'Copy link'. Send that link to this phone (for example by WhatsApp), copy it here, then tap 'Paste QR link'. After that it works on any network, including mobile data."
        }
    }

    private fun refreshStatus() {
        if (mode == "offline") {
            if (lanBase.isNotEmpty() && lanToken.isNotEmpty()) {
                say("\u25CF Offline mode: linked to the shop PC ($lanBase). Same WiFi needed.", "#4ade80")
            } else {
                say("Offline mode: not linked yet. In the tool's QR window choose Offline, tap 'Copy link', then 'Paste QR link' here.", "#fbbf24")
            }
        } else if (topic.isNotEmpty() && key.isNotEmpty()) {
            say("\u25CF Online mode: linked to the shop QR (works on any network)", "#4ade80")
        } else {
            say("Online mode: not linked yet. Open Settings and tap 'Paste QR link'.", "#fbbf24")
        }
    }

    private fun failText(): String {
        return if (mode == "offline") {
            "Could not reach the shop PC. Same WiFi? Is Start Scan Bridge running? The photo is saved in the gallery."
        } else {
            "No internet connection. The photo is saved in the gallery."
        }
    }

    // On a WiFi that has no internet, Android may keep using mobile data. For the shop PC we must use the WiFi network itself.
    private fun lanConnection(url: URL): HttpURLConnection {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return n.openConnection(url) as HttpURLConnection
                }
            }
        } catch (e: Exception) {
        }
        return url.openConnection() as HttpURLConnection
    }

    private fun testLan() {
        if (lanBase.isEmpty() || lanToken.isEmpty()) {
            say("Offline mode: not linked yet. Tap 'Paste QR link' first.", "#fbbf24")
            return
        }
        say("Testing the connection to the shop PC...", "#7dd3fc")
        io.execute {
            try {
                val c = lanConnection(URL("$lanBase/ping"))
                c.connectTimeout = 5000
                c.readTimeout = 5000
                val code = c.responseCode
                c.disconnect()
                if (code in 200..299) {
                    say("\u2713 The shop PC answered. Offline mode is ready.", "#4ade80")
                } else {
                    say("The shop PC answered with code $code.", "#fbbf24")
                }
            } catch (e: Exception) {
                say("Could not reach the shop PC at $lanBase. Same WiFi? Is Start Scan Bridge running? Did Windows Firewall allow Python on Private networks?", "#fb7185")
            }
        }
    }

    // Sends one photo straight to the shop PC (offline mode). Same meta as the phone web page.
    private fun putLan(bytes: ByteArray, idx: Int): Int {
        val meta = JSONObject()
        meta.put("v", 3)
        meta.put("k", "photo")
        meta.put("s", sessionId)
        meta.put("i", idx)
        meta.put("name", "passport_" + System.currentTimeMillis() + ".jpg")
        meta.put("type", "image/jpeg")
        val hdr = URLEncoder.encode(meta.toString(), "UTF-8").replace("+", "%20")
        val c = lanConnection(URL(lanBase + "/upload?k=" + URLEncoder.encode(lanToken, "UTF-8")))
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 8000
        c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("X-Meta", hdr)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        c.disconnect()
        return code
    }

    private fun deliver(jpeg: ByteArray, idx: Int): Int {
        return if (mode == "offline") putLan(jpeg, idx) else put(seal(jpeg))
    }

    private fun linkDialog() {
        val et = EditText(this)
        et.hint = "https://...#t=...&k=...  or  http://192.168...#m=lan&k=..."
        et.setSingleLine()
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            if (parseAny(clip) != null) et.setText(clip)
        } catch (e: Exception) {
        }
        AlertDialog.Builder(this)
            .setTitle("Shop QR link")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val p = parseAny(et.text.toString())
                if (p == null) {
                    say("That link is not valid. Use 'Copy link' in the QR window on the shop PC.", "#fb7185")
                } else if (p.first) {
                    topic = p.second
                    key = p.third
                    prefs.edit().putString("topic", topic).putString("key", key).apply()
                    setMode("online")
                } else {
                    lanBase = p.second
                    lanToken = p.third
                    prefs.edit().putString("lanBase", lanBase).putString("lanToken", lanToken).apply()
                    setMode("offline")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- upload ----------------

    private fun upload(uri: Uri) {
        if (!linked()) {
            say("Not linked to the shop. The photo is saved in the gallery. Open Settings and tap 'Paste QR link'.", "#fbbf24")
            return
        }
        say(if (mode == "offline") "Sending the photo to the shop PC..." else "Locking and sending the photo...", "#7dd3fc")
        io.execute {
            try {
                val code = deliver(prepare(uri), sentCount)
                if (code in 200..299) {
                    sentCount++
                    say("\u2713 Photo sent to the shop (" + sentCount + ")", "#4ade80")
                    if (!prefs.getBoolean("keep", false)) {
                        try { contentResolver.delete(uri, null, null) } catch (e: Exception) { }
                    }
                } else if (code == 413) {
                    say("The photo is too large to send. It is saved in the gallery.", "#fb7185")
                } else if (code == 403 && mode == "offline") {
                    say("The shop QR link is old. In the tool tap 'Copy link' again and paste it here. (Photo is in the gallery)", "#fbbf24")
                } else if (code == 429) {
                    say("Too many uploads. Wait a minute and try again. (Photo is in the gallery)", "#fbbf24")
                } else {
                    say("Could not send (code $code). The photo is saved in the gallery.", "#fb7185")
                }
            } catch (e: Exception) {
                say(failText(), "#fb7185")
            }
        }
    }

    // Photos picked from the phone gallery: sent to the shop, the originals are never deleted.
    private fun uploadPicked(uris: List<Uri>) {
        if (!linked()) {
            say("Not linked to the shop. Open Settings and tap 'Paste QR link'.", "#fbbf24")
            return
        }
        io.execute {
            var ok = 0
            var lastCode = 0
            for ((i, u) in uris.withIndex()) {
                say((if (mode == "offline") "Sending photo " else "Locking and sending photo ") + (i + 1) + " of " + uris.size + "...", "#7dd3fc")
                try {
                    val code = deliver(prepare(u), i)
                    lastCode = code
                    if (code in 200..299) {
                        ok++
                    } else if (code == 429) {
                        break
                    }
                } catch (e: Exception) {
                    lastCode = -1
                }
            }
            sentCount += ok
            if (ok == uris.size) {
                say("\u2713 " + ok + " photo(s) sent to the shop", "#4ade80")
            } else if (lastCode == 429) {
                say("Too many uploads. " + ok + " of " + uris.size + " sent. Wait a minute and send the rest again.", "#fbbf24")
            } else if (lastCode == -1) {
                say(ok.toString() + " of " + uris.size + " sent. " + (if (mode == "offline") "Could not reach the shop PC (same WiFi? Start Scan Bridge running?) or the photo could not be read." else "No internet connection or the photo could not be read."), "#fb7185")
            } else {
                say(ok.toString() + " of " + uris.size + " sent (code " + lastCode + "). Please try again.", "#fb7185")
            }
        }
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val out = ByteArray(32)
        if (b.size >= 32) {
            System.arraycopy(b, b.size - 32, out, 0, 32)
        } else {
            System.arraycopy(b, 0, out, 32 - b.size, b.size)
        }
        return out
    }

    // Same format as the sender web page (v1): [1][ephemeral public key 65][iv 12][AES-GCM ciphertext]
    // ECDH (P-256) + AES-256-GCM, the ephemeral public key is also the authenticated data.
    private fun seal(jpeg: ByteArray): ByteArray {
        val pcRaw = b64uDecode(key) ?: throw IllegalStateException("bad key")
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        val spec = ap.getParameterSpec(ECParameterSpec::class.java)
        val x = BigInteger(1, pcRaw.copyOfRange(1, 33))
        val y = BigInteger(1, pcRaw.copyOfRange(33, 65))
        val pcPub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec))

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val eph = kpg.generateKeyPair()
        val ephPub = eph.public as ECPublicKey
        val ephRaw = ByteArray(65)
        ephRaw[0] = 4
        System.arraycopy(fixed32(ephPub.w.affineX), 0, ephRaw, 1, 32)
        System.arraycopy(fixed32(ephPub.w.affineY), 0, ephRaw, 33, 32)

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(eph.private)
        ka.doPhase(pcPub, true)
        val secret = ka.generateSecret()

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(ephRaw)
        val ct = cipher.doFinal(jpeg)

        val out = ByteArray(1 + 65 + 12 + ct.size)
        out[0] = 1
        System.arraycopy(ephRaw, 0, out, 1, 65)
        System.arraycopy(iv, 0, out, 66, 12)
        System.arraycopy(ct, 0, out, 78, ct.size)
        return out
    }

    private fun prepare(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 3200) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        var bmp: Bitmap = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }!!
        val orient = contentResolver.openInputStream(uri)!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val m = Matrix()
        when (orient) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val big = maxOf(bmp.width, bmp.height)
        if (big > 3200) {
            val k = 3200f / big
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 93, out)
        return out.toByteArray()
    }

    // Upload to the free relay (ntfy.sh). The tag lets the shop's Passport tool pick it up (documents are tagged "doc").
    private fun put(bytes: ByteArray): Int {
        val c = URL("$RELAY/$topic").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("Tags", "photo")
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        c.disconnect()
        return code
    }
}
