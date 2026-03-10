package com.pr0gramm.app.ui

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.fragment.app.DialogFragment
import com.pr0gramm.app.Settings

class CustomDefaultTagDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()

        val editText = AppCompatEditText(requireContext()).apply {
            val current = Settings.customDefaultTags.orEmpty()
            setText(current)
            setSelection(current.length)

            hint = "Tags eingeben"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.START or Gravity.TOP
        }

        val container = FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(
                editText,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("Standard-Tags")
            .setView(container)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Annehmen") { _, _ ->
                Settings.customDefaultTags = editText.text?.toString().orEmpty()
            }
            .create()
    }
}