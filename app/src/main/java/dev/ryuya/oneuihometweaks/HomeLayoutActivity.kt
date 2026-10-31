package dev.ryuya.oneuihometweaks

import android.content.ClipData
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/* User controls and an explicit, local-only export for home layout diagnostics. */
class HomeLayoutActivity : AppCompatActivity() {
    private val worker: ExecutorService? = Executors.newSingleThreadExecutor()
    private var status: TextView? = null

    public override fun onCreate(saved: Bundle?) {
        AppUiTheme.apply(this)
        super.onCreate(saved)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        val toolbar = MaterialToolbar(this)
        toolbar.setTitle(R.string.layout_diagnostics)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationContentDescription(R.string.back)
        toolbar.setNavigationOnClickListener(
            android.view.View.OnClickListener { v: View? -> finish() }
        )
        root.addView(toolbar)
        val scroll = ScrollView(this)
        val content = LinearLayout(this)
        content.setOrientation(LinearLayout.VERTICAL)
        val padding = (20 * getResources().getDisplayMetrics().density).toInt()
        content.setPadding(padding, padding, padding, padding)
        scroll.addView(content)
        root.addView(scroll, LayoutParams(-1, 0, 1f))
        val note = TextView(this)
        note.setText(R.string.layout_note)
        note.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
        )
        content.addView(note)
        addSwitch(content, "layout_protect", R.string.layout_protect)
        addSwitch(content, "layout_logging", R.string.layout_logging)
        val privacy = TextView(this)
        privacy.setText(R.string.layout_privacy)
        privacy.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
        )
        content.addView(privacy)
        status = TextView(this)
        status!!.setPadding(0, padding, 0, padding)
        status!!.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
        )
        content.addView(status)
        val share = MaterialButton(this)
        share.setText(R.string.layout_share)
        share.setOnClickListener(android.view.View.OnClickListener { v: View? -> export() })
        content.addView(share)
        val clear =
            MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
        clear.setText(R.string.layout_clear)
        clear.setOnClickListener(
            android.view.View.OnClickListener { v: View? ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.layout_clear_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(
                        android.R.string.ok,
                        android.content.DialogInterface.OnClickListener { _, w: Int ->
                            worker!!.execute(
                                Runnable {
                                    try {
                                        HomeLayoutJournal.clear(this)
                                        runOnUiThread(Runnable { this.refresh() })
                                    } catch (error: Exception) {
                                        fail()
                                    }
                                }
                            )
                        },
                    )
                    .show()
            }
        )
        content.addView(clear)
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
        refresh()
    }

    private fun addSwitch(content: LinearLayout, key: String?, title: Int) {
        val prefs = getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        val control = MaterialSwitch(this)
        control.setText(title)
        control.setMinHeight((64 * getResources().getDisplayMetrics().density).toInt())
        control.setChecked(prefs.getBoolean(key, true))
        control.setOnCheckedChangeListener(
            OnCheckedChangeListener { button: CompoundButton?, checked: Boolean ->
                prefs.edit().putBoolean(key, checked).apply()
                getContentResolver().notifyChange(ConfigProvider.URI!!, null)
            }
        )
        content.addView(control)
    }

    private fun refresh() {
        worker!!.execute(
            Runnable {
                val bytes = HomeLayoutJournal.bytes(this)
                runOnUiThread(
                    Runnable { status!!.setText(getString(R.string.layout_log_size, bytes / 1024)) }
                )
            }
        )
    }

    private fun export() {
        worker!!.execute(
            Runnable {
                try {
                    val file = HomeLayoutJournal.export(this)
                    val uri =
                        FileProvider.getUriForFile(this, getPackageName() + ".diagnostics", file)
                    val share =
                        Intent(Intent.ACTION_SEND)
                            .setType("application/x-ndjson")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    share.setClipData(ClipData.newRawUri("Home layout diagnostics", uri))
                    runOnUiThread(
                        Runnable {
                            startActivity(
                                Intent.createChooser(share, getString(R.string.layout_share))
                            )
                        }
                    )
                } catch (error: Exception) {
                    fail()
                }
            }
        )
    }

    private fun fail() {
        runOnUiThread(
            Runnable { Toast.makeText(this, R.string.layout_io_error, Toast.LENGTH_LONG).show() }
        )
    }

    override fun onDestroy() {
        worker!!.shutdown()
        super.onDestroy()
    }
}
