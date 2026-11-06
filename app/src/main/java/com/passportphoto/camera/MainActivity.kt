package com.passportphoto.camera

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
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
import android.media.ExifInterface
import android.media.MediaActionSound
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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
        const val PORT = 8766
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
    private var host = ""
    private var token = ""
    private var sentCount = 0

    @Volatile private var busy = false
    @Volatile private var connected = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else say("Camera ki permission chahiye. Phone Settings mein allow karein.", "#fb7185")
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
        host = prefs.getString("host", "") ?: ""
        token = prefs.getString("token", "") ?: ""

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

        val find = Button(this)
        find.text = "PC dobara dhoondho"
        find.setOnClickListener {
            connected = false
            connect()
        }
        settingsPanel.addView(find, LinearLayout.LayoutParams(match, dp(50)))

        val manual = Button(this)
        manual.text = "IP / link khud daalein"
        manual.setOnClickListener { manualDialog() }
        val lpM = LinearLayout.LayoutParams(match, dp(50))
        lpM.topMargin = dp(8)
        settingsPanel.addView(manual, lpM)

        val lpC = LinearLayout.LayoutParams(wrap, wrap)
        lpC.topMargin = dp(14)
        settingsPanel.addView(check("Photo PC par apne aap bhejein", "auto", true) { }, lpC)
        settingsPanel.addView(check("Photo phone ki gallery mein bhi rakhein", "keep", false) { }, lpC)
        settingsPanel.addView(check("Grid dikhayein", "grid", true) { on ->
            grid.visibility = if (on) View.VISIBLE else View.GONE
        }, lpC)

        val hint = TextView(this)
        hint.text = "Pehli baar jodne ke liye: PC par Passport Photo tool mein Mobile icon dabayein, phir yahan 'PC dobara dhoondho' dabayein."
        hint.textSize = 12f
        hint.setTextColor(Color.parseColor("#9fb3cf"))
        hint.setPadding(0, dp(18), 0, dp(18))
        settingsPanel.addView(hint)

        val close = Button(this)
        close.text = "Band karein"
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!connected && !busy) connect()
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
            say("Camera nahi khul paya: " + e.message, "#fb7185")
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
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PassportCamera")
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v
        ).build()

        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                capturing = false
                val uri = output.savedUri
                if (uri == null) {
                    say("Photo save nahi hui.", "#fb7185")
                    return
                }
                if (prefs.getBoolean("auto", true)) {
                    upload(uri)
                } else {
                    say("Photo gallery mein save ho gayi.", "#4ade80")
                }
            }

            override fun onError(exc: ImageCaptureException) {
                capturing = false
                say("Photo nahi khinchi: " + exc.message, "#fb7185")
            }
        })
    }

    // ---------------- finding the PC ----------------

    private fun connect() {
        if (busy) return
        busy = true
        say("PC dhoondh raha hu...", "#7dd3fc")
        io.execute {
            try {
                var h = host
                if (h.isEmpty() || !hello(h)) {
                    h = scan() ?: ""
                }
                if (h.isEmpty()) {
                    say("PC nahi mila. PC par bridge chalu karein (tool mein Mobile icon dabayein) aur phone-PC ek hi WiFi par rakhein.", "#fb7185")
                    return@execute
                }
                host = h
                prefs.edit().putString("host", h).apply()
                if (token.isEmpty()) {
                    val t = pair(h)
                    if (t == null) {
                        say("Pehli baar jodne ke liye PC par tool mein Mobile icon dabayein, phir Settings mein 'PC dobara dhoondho' dabayein.", "#fbbf24")
                        return@execute
                    }
                    token = t
                    prefs.edit().putString("token", t).apply()
                }
                connected = true
                say("\u25CF PC se juda hua ($h)", "#4ade80")
            } finally {
                busy = false
            }
        }
    }

    private fun localIpv4(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        for (la in lp.linkAddresses) {
            val a = la.address
            if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
        }
        return null
    }

    private fun portOpen(h: String): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(h, PORT), 350) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hello(h: String): Boolean {
        return try {
            val c = URL("http://$h:$PORT/hello").openConnection() as HttpURLConnection
            c.connectTimeout = 700
            c.readTimeout = 1200
            val ok = c.responseCode == 200 && c.inputStream.bufferedReader().use { it.readText() }.contains("phonebridge")
            c.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun scan(): String? {
        val ip = localIpv4() ?: return null
        val prefix = ip.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(48)
        try {
            val tasks = (1..254).map { i ->
                Callable<String?> {
                    val cand = "$prefix.$i"
                    if (cand != ip && portOpen(cand) && hello(cand)) cand else null
                }
            }
            for (f in pool.invokeAll(tasks)) {
                val r = f.get()
                if (r != null) return r
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    private fun pair(h: String): String? {
        return try {
            val c = URL("http://$h:$PORT/pair").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 3000
            if (c.responseCode != 200) {
                c.disconnect()
                null
            } else {
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                Regex("\"token\"\\s*:\\s*\"([0-9a-f]+)\"").find(body)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun manualDialog() {
        val et = EditText(this)
        et.hint = "192.168.1.5  ya poora link"
        et.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle("PC ka IP ya link")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val t = et.text.toString().trim()
                val h = t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
                val tk = Regex("[?&]t=([0-9a-f]+)").find(t)?.groupValues?.get(1)
                if (h.isNotEmpty()) {
                    host = h
                    prefs.edit().putString("host", h).apply()
                    if (tk != null) {
                        token = tk
                        prefs.edit().putString("token", tk).apply()
                    }
                    connected = false
                    connect()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- upload ----------------

    private fun upload(uri: Uri) {
        if (host.isEmpty() || token.isEmpty()) {
            say("PC se nahi juda. Photo gallery mein save hai. Settings mein 'PC dobara dhoondho' dabayein.", "#fbbf24")
            return
        }
        say("Photo bhej raha hu...", "#7dd3fc")
        io.execute {
            try {
                val bytes = prepare(uri)
                var code = post(bytes)
                if (code == 403) {
                    val t = pair(host)
                    if (t != null) {
                        token = t
                        prefs.edit().putString("token", t).apply()
                        code = post(bytes)
                    }
                }
                if (code == 200) {
                    sentCount++
                    say("\u2713 Photo PC par pahunch gayi (" + sentCount + ")", "#4ade80")
                    if (!prefs.getBoolean("keep", false)) {
                        try { contentResolver.delete(uri, null, null) } catch (e: Exception) { }
                    }
                } else if (code == 403) {
                    say("Link badal gaya hai. PC par tool mein Mobile icon dabayein, phir dobara photo khinchein. (Photo gallery mein hai)", "#fbbf24")
                } else {
                    say("Bhej nahi paya (code $code). Photo gallery mein hai.", "#fb7185")
                }
            } catch (e: Exception) {
                connected = false
                say("PC se connection toot gaya. Settings mein 'PC dobara dhoondho' dabayein. (Photo gallery mein hai)", "#fb7185")
            }
        }
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

    private fun post(bytes: ByteArray): Int {
        val c = URL("http://$host:$PORT/upload?t=$token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 3000
        c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "image/jpeg")
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        c.disconnect()
        return code
    }
}
