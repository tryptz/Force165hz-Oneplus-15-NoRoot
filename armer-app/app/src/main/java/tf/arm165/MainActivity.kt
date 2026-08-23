package tf.arm165

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
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

    // dp helper — the old code used raw pixels, which were invisible on this screen
    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

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

        val pad = dp(16)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- header ----
        root.addView(TextView(this).apply {
            text = "165 Armer"
            textSize = 22f
            setTypeface(Typeface.create(typeface, Typeface.BOLD))
            setTextColor(getColor(R.color.text_primary))
            setPadding(pad, dp(20), pad, dp(4))
        })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(pad, 0, pad, dp(12))
        }
        root.addView(status)

        // ---- search ----
        val search = EditText(this).apply {
            hint = "Search apps…"
            setSingleLine()
            textSize = 15f
            setBackgroundResource(R.drawable.bg_search)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = pad; lp.marginEnd = pad; lp.bottomMargin = dp(12)
            layoutParams = lp
        }
        root.addView(search)

        // ---- buttons ----
        fun btn(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6); marginEnd = dp(6)
            }
            setOnClickListener { action() }
        }
        fun row(vararg btns: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(10); lp.marginEnd = dp(10); lp.bottomMargin = dp(8)
            layoutParams = lp
            btns.forEach { addView(it) }
        }

        root.addView(row(
            btn("Re-arm saved") { reArmSaved() },
            btn("Arm EVERYTHING") { armAll() },
        ))
        root.addView(row(
            btn("Clear all") { clearAll() },
        ))

        // ---- list ----
        list = ListView(this).apply {
            dividerHeight = dp(1)
            setDivider(android.graphics.drawable.ColorDrawable(0x1E000000))
            clipToPadding = false
            setPadding(0, 0, 0, dp(24))
        }
        list.adapter = adapter()
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // empty state for search
        val empty = TextView(this).apply {
            text = "No apps match."
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(getColor(R.color.text_disabled))
            setPadding(pad, dp(48), pad, 0)
        }
        list.emptyView = empty
        root.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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

        refreshStatus()
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
            runOnUiThread {
                busy = false
                refreshStatus()
                (list.adapter as BaseAdapter).notifyDataSetChanged()
            }
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
                setPadding(dp(16), dp(10), dp(12), dp(10))

                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {   // app label
                        textSize = 16f
                        setTextColor(getColor(R.color.text_primary))
                        setTypeface(Typeface.create(typeface, Typeface.BOLD))
                    })
                    addView(TextView(context).apply {   // package name
                        textSize = 12f
                        setTextColor(getColor(R.color.text_secondary))
                    })
                }
                addView(texts)
                addView(Switch(context))
            }) as LinearLayout

            val texts = row.getChildAt(0) as LinearLayout
            val label = texts.getChildAt(0) as TextView
            val sub = texts.getChildAt(1) as TextView
            val sw = row.getChildAt(1) as Switch

            label.text = labelOf(pkg)
            sub.text = pkg
            val system = pkg.startsWith("com.android") || pkg.startsWith("com.oplus")
            label.setTextColor(getColor(if (system) R.color.text_disabled else R.color.text_primary))

            sw.setOnCheckedChangeListener(null)
            sw.isChecked = pkg in armed()
            row.setOnClickListener { sw.toggle() }
            sw.setOnCheckedChangeListener { button, checked ->
                if (checked) {
                    confirmFirstTime {
                        val set = armed()
                        if (RateLock.arm(pkg)) {
                            set.add(pkg); prefs.edit().putStringSet("armed", set).apply()
                        } else {
                            Toast.makeText(this@MainActivity, "arm failed", Toast.LENGTH_SHORT).show()
                        }
                        refreshStatus()
                        notifyDataSetChanged() // repaint switch to actual state immediately
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
