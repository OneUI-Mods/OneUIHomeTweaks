package dev.ryuya.oneuihometweaks

import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class LicensesActivity : AppCompatActivity() {
    public override fun onCreate(saved: Bundle?) {
        AppUiTheme.apply(this)
        super.onCreate(saved)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setBackgroundColor(
            MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorSurface,
            )
        )
        val bar = MaterialToolbar(this)
        bar.setTitle(R.string.license_title)
        bar.setNavigationIcon(R.drawable.ic_back)
        bar.setNavigationContentDescription(R.string.back)
        bar.setNavigationOnClickListener(OnClickListener { v: View? -> finish() })
        root.addView(bar)
        val scroll = ScrollView(this)
        val text = TextView(this)
        text.setTextIsSelectable(true)
        val p = (20 * getResources().getDisplayMetrics().density).toInt()
        text.setPadding(p, p, p, p)
        text.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
        )
        scroll.addView(text)
        root.addView(scroll, LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(
            root,
            OnApplyWindowInsetsListener { view: View?, insets: WindowInsetsCompat? ->
                val safe = insets!!.getInsets(Type.systemBars() or Type.displayCutout())
                view!!.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                WindowInsetsCompat.CONSUMED
            },
        )
        ViewCompat.requestApplyInsets(root)
        try {
            text.setText(
                (getString(R.string.own_license) +
                    "\n\n" +
                    asset("APP_LICENSE.txt") +
                    "\n\n" +
                    asset("THIRD_PARTY_NOTICES.txt"))
            )
        } catch (e: IOException) {
            text.setText(R.string.license_unavailable)
        }
    }

    @Throws(IOException::class)
    private fun asset(name: String?): String? {
        getAssets().open(name!!).use { stream ->
            ByteArrayOutputStream().use { out ->
                val b = ByteArray(4096)
                var n: Int
                while ((stream.read(b).also { n = it }) != -1) out.write(b, 0, n)
                return String(out.toByteArray(), StandardCharsets.UTF_8)
            }
        }
    }
}
