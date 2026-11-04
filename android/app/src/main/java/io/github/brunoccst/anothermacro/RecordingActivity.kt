package io.github.brunoccst.anothermacro

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * After ⏹: name the recording (e.g. cat-acteul, dogma-tower-map-1) and save it, or discard it.
 * Saving also makes a macro of the same name that replays it (group "Recorded").
 */
class RecordingActivity : Activity() {

    private lateinit var dir: File
    private lateinit var name: EditText
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = intent.getStringExtra(EXTRA_DIR)?.let { File(it) }?.takeIf { File(it, "recording.json").isFile } ?: return finish()
        val pad = (12 * resources.displayMetrics.density).toInt()
        val data = runCatching { JSONObject(File(dir, "recording.json").readText()) }.getOrNull() ?: return finish()
        val moves = data.getJSONArray("gestures")
        val labels = (0 until moves.length()).mapNotNull { moves.getJSONObject(it).optString("label").takeIf { l -> l.isNotBlank() } }
        val cut = (0 until moves.length()).count { !moves.getJSONObject(it).optBoolean("echo_ok", true) }

        result = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = buildString {
                append("Recorded ${moves.length()} moves in ${data.optLong("end_ms") / 1000}s.")
                if (labels.isNotEmpty()) append("\nTapped: ${labels.joinToString(" › ")}")
                if (cut > 0) append("\n⚠ $cut moves were cut short (a touch while a move was still playing): check the replay.")
                append("\n\nName it, e.g. cat-<place> or <dungeon>-map-1. A macro with the same name replays it.")
            }
        }
        name = EditText(this).apply {
            hint = "name, e.g. cat-acteul"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(labels.lastOrNull()?.let { "cat-" + Recorder.suggestName(it) } ?: "")
        }
        val save = Button(this).apply { text = "Save"; setOnClickListener { save() } }
        val discard = Button(this).apply {
            text = "Discard"
            setOnClickListener { dir.deleteRecursively(); MacroLog.i("recording discarded"); finish() }
        }
        val saved = TextView(this).apply {
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            val list = Recorder.list(this@RecordingActivity)
            text = if (list.isEmpty()) "" else "\nSaved recordings (a name used again is replaced): ${list.joinToString()}" +
                "\nFolder: ${Recorder.root(this@RecordingActivity).path}"
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E272C"))
            addView(LinearLayout(this@RecordingActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(result)
                addView(name)
                addView(LinearLayout(this@RecordingActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(save)
                    addView(discard)
                })
                addView(saved)
            })
        })
    }

    // the floating buttons would sit on top of this screen's buttons
    override fun onResume() {
        super.onResume()
        InputService.instance?.showControls(false)
    }

    override fun onPause() {
        if (MacroService.capture != null) InputService.instance?.showControls(true)
        super.onPause()
    }

    private fun save() {
        val n = Recorder.suggestName(name.text.toString())
        if (!Recorder.validName(n)) {
            result.text = "Name: lowercase letters, digits, - and _ (e.g. cat-acteul)."
            return
        }
        try {
            Recorder.save(this, dir, n)
            LastRun.save(this, n, emptyMap()) // ▶ on the floating buttons now replays it
            finish()
        } catch (e: Exception) {
            result.text = "Could not save: ${e.message}"
        }
    }

    companion object {
        const val EXTRA_DIR = "dir"
    }
}
