package com.pairdrop.android.intro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.CornerFamily
import com.pairdrop.android.R

class IntroView(
    private val context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun requestNotifications()
        fun openBatterySettings()
        fun openAppSettings()
        fun finishIntro()
    }

    private var step = 0
    private lateinit var root: FrameLayout
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var image: ImageView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var primaryButton: MaterialButton
    private lateinit var secondaryButton: MaterialButton
    private lateinit var tertiaryButton: MaterialButton
    private lateinit var statusContainer: LinearLayout

    fun create(): View {
        root = FrameLayout(context).apply {
            setBackgroundColor(0xFFF8FAFF.toInt())
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        val card = MaterialCardView(context).apply {
            radius = dp(28).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(0x1F3367D6)
            setCardBackgroundColor(0xFFFFFFFF.toInt())
            shapeAppearanceModel = shapeAppearanceModel
                .toBuilder()
                .setAllCorners(CornerFamily.ROUNDED, dp(28).toFloat())
                .build()
        }

        val cardContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }

        progress = LinearProgressIndicator(context).apply {
            max = 3
            progress = 1
            trackThickness = dp(6)
            setIndicatorColor(0xFF3367D6.toInt())
            trackColor = 0xFFE2E8F7.toInt()
        }

        image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(260)
            ).apply {
                topMargin = dp(20)
                bottomMargin = dp(22)
            }
            clipToOutline = true
        }

        title = TextView(context).apply {
            textSize = 28f
            setTextColor(0xFF162033.toInt())
            setPadding(0, 0, 0, dp(10))
        }

        body = TextView(context).apply {
            textSize = 16f
            setTextColor(0xFF4B5568.toInt())
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }

        statusContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(18), 0, 0)
        }

        primaryButton = MaterialButton(context).apply {
            cornerRadius = dp(18)
            minHeight = dp(52)
            text = "Weiter"
        }

        secondaryButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            cornerRadius = dp(18)
            minHeight = dp(48)
            visibility = View.GONE
        }

        tertiaryButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            cornerRadius = dp(18)
            minHeight = dp(44)
            visibility = View.GONE
        }

        cardContent.addView(progress)
        cardContent.addView(image)
        cardContent.addView(title)
        cardContent.addView(body)
        cardContent.addView(statusContainer)
        cardContent.addView(primaryButton, buttonLayout(top = 24))
        cardContent.addView(secondaryButton, buttonLayout(top = 10))
        cardContent.addView(tertiaryButton, buttonLayout(top = 2))
        card.addView(cardContent)

        content.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            width = minOf(context.resources.displayMetrics.widthPixels - dp(40), dp(620))
        })

        scroll.addView(content)
        root.addView(scroll)
        render()
        return root
    }

    fun refresh() {
        if (::primaryButton.isInitialized && step == 1) renderPermissions()
    }

    private fun render() {
        progress.progress = step + 1
        statusContainer.visibility = View.GONE
        secondaryButton.visibility = View.GONE
        tertiaryButton.visibility = View.GONE

        when (step) {
            0 -> renderWelcome()
            1 -> renderPermissions()
            else -> renderReady()
        }
    }

    private fun renderWelcome() {
        setImage("pairdrop/images/pairdrop_screenshot_mobile_1.png")
        title.text = "Dateien teilen, ohne Umwege"
        body.text = "PairDrop findet Geraete in deiner Naehe und sendet Bilder, Videos, PDFs oder Links direkt. Ohne Konto, ohne Cloud-Zwang. Wenn Internet verfuegbar ist, koennen gekoppelte Geraete auch ausserhalb deines WLANs auftauchen."
        primaryButton.text = "Weiter"
        primaryButton.isEnabled = true
        primaryButton.setOnClickListener {
            step = 1
            render()
        }
    }

    private fun renderPermissions() {
        setImage("pairdrop/images/pairdrop_screenshot_mobile_3.png")
        title.text = "Bereit fuer den Hintergrund"
        body.text = "Damit die Quick-Settings-Kachel zuverlaessig funktioniert, braucht PairDrop Benachrichtigungen und sollte nicht vom Akku-Manager beendet werden."

        statusContainer.visibility = View.VISIBLE
        statusContainer.removeAllViews()
        statusContainer.addView(statusRow("Benachrichtigungen", notificationsAllowed()))
        statusContainer.addView(statusRow("Akku-Optimierung deaktiviert", batteryOptimizationDisabled()))
        statusContainer.addView(statusRow("Hintergrundnutzung in App-Info pruefen", true))

        primaryButton.text = "Berechtigungen pruefen"
        primaryButton.isEnabled = notificationsAllowed() && batteryOptimizationDisabled()
        primaryButton.setOnClickListener {
            step = 2
            render()
        }

        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = if (notificationsAllowed()) "Benachrichtigungen erlaubt" else "Benachrichtigungen erlauben"
        secondaryButton.isEnabled = !notificationsAllowed()
        secondaryButton.setOnClickListener { callbacks.requestNotifications() }

        tertiaryButton.visibility = View.VISIBLE
        tertiaryButton.text = "Akku- und Hintergrundnutzung oeffnen"
        tertiaryButton.setOnClickListener {
            if (!batteryOptimizationDisabled()) callbacks.openBatterySettings() else callbacks.openAppSettings()
        }
    }

    private fun renderReady() {
        setImage("pairdrop/images/pairdrop_screenshot_mobile_6.png")
        title.text = "Los geht's"
        body.text = "Du bist startbereit. Oeffne PairDrop zum Senden, oder aktiviere die Kachel in den Quick Settings, damit dieses Geraet im Hintergrund empfangen kann."
        primaryButton.text = "PairDrop starten"
        primaryButton.isEnabled = true
        primaryButton.setOnClickListener { callbacks.finishIntro() }
    }

    private fun statusRow(label: String, ok: Boolean): TextView {
        return TextView(context).apply {
            text = "${if (ok) "OK" else "!"}  $label"
            textSize = 15f
            setTextColor(if (ok) 0xFF087F5B.toInt() else 0xFF8A5A00.toInt())
            setPadding(0, dp(5), 0, dp(5))
        }
    }

    private fun setImage(assetPath: String) {
        runCatching {
            context.assets.open(assetPath).use { stream ->
                image.setImageBitmap(BitmapFactory.decodeStream(stream))
            }
        }.onFailure {
            image.setImageResource(R.drawable.ic_pairdrop)
        }
    }

    private fun notificationsAllowed(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun batteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun buttonLayout(top: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
