package tf.arm165

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var prefs: android.content.SharedPreferences
    private var all: List<String> = emptyList()
    private var shown: List<String> = emptyList()
    private lateinit var list: ListView
    private lateinit var status: TextView
    @Volatile private var busy = false

    private fun armed(): MutableSet<String> =
        HashSet(prefs.getStringSet("armed", emptySet())!!)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("arm165", Context.MODE_PRIVATE)

        all = packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it != packageName }
            .sorted()
        shown = all

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { setPadding(24, 24, 24, 8); textSize = 15f }
        root.addView(status)

        val search = EditText(this).apply {
            hint = "search apps…"
            setSingleLine()
            setPadding(24, 8, 24, 8)
        }
        root.addView(search)

        fun row(vararg btns: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            btns.forEach { addView(it) }
        }
        fun btn(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        }

        root.addView(row(
            btn("Re-arm saved") { reArmSaved() },
            btn("Arm EVERYTHING") { armAll() },
        ))
        root.addView(row(
            btn("Clear all") { clearAll() },
        ))

        list = ListView(this)
        list.adapter = adapter()
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val q = e?.toString()?.trim()?.lowercase() ?: ""
                shown = if (q.isEmpty()) all else all.filter {
                    it.lowercase().contains(q) || labelOf(it).lowercase().contains(q)
                }
                (list.adapter as BaseAdapter).notifyDataSetChanged()
            }
        })

        reArmSaved(quiet = true) // covers reboot-without-boot-receiver
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun refreshStatus() {
        status.text = "Armed: ${armed().size} app(s) @165Hz\nToggle a row to arm; toggle again to disarm."
    }

    private fun withBusy(toast: String, block: () -> Unit) {
        if (busy) { Toast.makeText(this, "busy…", Toast.LENGTH_SHORT).show(); return }
        busy = true
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
        thread {
            block()
            runOnUiThread { busy = false; refreshStatus(); (list.adapter as BaseAdapter).notifyDataSetChanged() }
        }
    }

    private fun reArmSaved(quiet: Boolean = false) {
        if (quiet) { armed().forEach { RateLock.arm(it) }; return }
        withBusy("re-arming…") {
            var ok = 0
            val set = armed()
            set.forEach { if (RateLock.arm(it)) ok++ }
            runOnUiThread { Toast.makeText(this@MainActivity, "armed $ok/${set.size}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun armAll() {
        confirmFirstTime {
            withBusy("arming ${all.size} apps…") {
                val set = armed()
                all.forEach {
                    if (RateLock.arm(it)) set.add(it)
                    prefs.edit().putStringSet("armed", set).apply()
                }
                runOnUiThread { Toast.makeText(this@MainActivity, "all armed", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun clearAll() {
        withBusy("clearing…") {
            val set = armed()
            set.forEach { RateLock.arm(it) } // identical call toggles override off
            runOnUiThread {
                prefs.edit().clear().apply()
                (list.adapter as BaseAdapter).notifyDataSetChanged()
                Toast.makeText(this@MainActivity, "cleared ${set.size}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmFirstTime(onYes: () -> Unit) {
        if (prefs.getBoolean("warned", false)) { onYes(); return }
        AlertDialog.Builder(this)
            .setTitle("Risk alert")
            .setMessage("Pins display votes at 165Hz via an undocumented vendor IPC. Higher battery use and heat. Continue?")
            .setPositiveButton("Turn on") { _, _ -> prefs.edit().putBoolean("warned", true).apply(); onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun adapter() = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, convertView: View?, parent: ViewGroup): View {
            val pkg = getItem(i)
            val row = (convertView ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 16, 24, 16)
                addView(TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Switch(context))
            }) as LinearLayout
            val label = row.getChildAt(0) as TextView
            val sw = row.getChildAt(1) as Switch
            label.text = labelOf(pkg) + "\n" + pkg
            label.setTextColor(if (pkg.startsWith("com.android") || pkg.startsWith("com.oplus")) Color.GRAY else Color.BLACK)
            row.setOnClickListener { sw.toggle() }
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = pkg in armed()
            sw.setOnCheckedChangeListener { button, checked ->
                if (checked) {
                    confirmFirstTime {
                        val set = armed()
                        if (RateLock.arm(pkg)) {
                            set.add(pkg); prefs.edit().putStringSet("armed", set).apply()
                        } else {
                            Toast.makeText(this@MainActivity, "arm failed", Toast.LENGTH_SHORT).show()
                        }
                        refreshStatus(); notifyDataSetChanged()
                    }
                    button.isChecked = pkg in armed() // revert until confirmed
                } else {
                    RateLock.arm(pkg)
                    val set = armed()
                    set.remove(pkg); prefs.edit().putStringSet("armed", set).apply()
                    refreshStatus()
                }
            }
            return row
        }
    }
}
