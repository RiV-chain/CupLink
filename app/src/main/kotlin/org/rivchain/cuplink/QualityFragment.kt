package org.rivchain.cuplink

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textview.MaterialTextView
import org.rivchain.cuplink.DatabaseCache.Companion.database
import org.rivchain.cuplink.renderer.DescriptiveTextView
import org.rivchain.cuplink.util.Log

class QualityFragment: Fragment(R.layout.fragment_settings_quality) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val settings = database.settings

        setupRadioDialog(view,
            settings.videoDegradationMode,
            R.id.videoDegradationModeText,
            R.id.spinnerVideoDegradationModes,
            R.array.videoDegradationModeLabels,
            R.array.videoDegradationModeValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        settings.videoDegradationMode = newValue
                        applyVideoDegradationMode(view, newValue)
                    }
                }
            })
        view.findViewById<SwitchMaterial>(R.id.disableProximitySensorSwitch).apply {
            isChecked = settings.disableProximitySensor
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.disableProximitySensor = isChecked
                DatabaseCache.save()
            }
        }
        view.findViewById<SwitchMaterial>(R.id.disableCpuOveruseDetectionSwitch).apply {
            isChecked = settings.disableCpuOveruseDetection
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.disableCpuOveruseDetection = isChecked
                DatabaseCache.save()
            }
        }
    }

    private interface SpinnerItemSelected {
        fun call(newValue: String?)
    }

    private fun setupRadioDialog(view: View,
                                 currentValue: String,
                                 titleTextViewId: Int,
                                 inputTextViewId: Int,
                                 arrayId: Int,
                                 arrayValuesId: Int,
                                 callback: SpinnerItemSelected
    ) {
        val arrayValues = resources.getStringArray(arrayValuesId)
        val arrayLabels = resources.getStringArray(arrayId)

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_select_one_radio, null)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radioGroupNightModes)
        val titleTextView = dialogView.findViewById<TextView>(R.id.selectDialogTitle)
        val textViewId = view.findViewById<View>(titleTextViewId)
        if(textViewId is TextView){
            titleTextView.text = textViewId.text
        }
        if(textViewId is DescriptiveTextView){
            titleTextView.text = textViewId.titleTextView.text
        }
        val autoCompleteTextView = view.findViewById<MaterialTextView>(inputTextViewId)
        autoCompleteTextView.text = currentValue

        arrayLabels.forEachIndexed { index, label ->
            val radioButton = RadioButton(activity).apply {
                text = label
                id = index
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.radio_button_margin_bottom)
                }

                if (arrayValues[index] == currentValue) {
                    isChecked = true
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.light_light_grey))
                } else {
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.light_grey))
                }
            }
            radioGroup.addView(radioButton)
        }

        val dialog = (activity as BaseActivity).createBlurredPPTCDialog(dialogView)

        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            if (checkedId >= 0 && checkedId < arrayValues.size) {
                val selectedValue = arrayValues[checkedId]
                callback.call(selectedValue)
                autoCompleteTextView.text = selectedValue
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            val selectedId = radioGroup.checkedRadioButtonId
            if (selectedId >= 0 && selectedId < arrayValues.size) {
                val selectedValue = arrayValues[selectedId]
                callback.call(selectedValue)
                autoCompleteTextView.text = selectedValue
            }
        }

        textViewId.setOnClickListener {
            dialog.show()
        }
        autoCompleteTextView.setOnClickListener {
            dialog.show()
        }
    }

    // grey out resolution/framerate spinners that are not considered by certain
    // degradation modes. We still allow those two values to be changed though.
    private fun applyVideoDegradationMode(view: View, degradation: String) {
        val videoDegradationModeText = view.findViewById<DescriptiveTextView>(R.id.videoDegradationModeText)
        val cameraResolutionText = view.findViewById<DescriptiveTextView>(R.id.cameraResolutionText)
        val cameraFramerateText = view.findViewById<DescriptiveTextView>(R.id.cameraFramerateText)
        val enabledColor = videoDegradationModeText.titleTextView.currentTextColor
        val disabledColor = Color.parseColor("#d3d3d3")

        when (degradation) {
            "Balanced" -> {
                cameraResolutionText.titleTextView.setTextColor(disabledColor)
                cameraFramerateText.titleTextView.setTextColor(disabledColor)
            }
            "Maintain resolution" -> {
                cameraResolutionText.titleTextView.setTextColor(enabledColor)
                cameraFramerateText.titleTextView.setTextColor(disabledColor)
            }
            "Maintain framerate" -> {
                cameraResolutionText.titleTextView.setTextColor(disabledColor)
                cameraFramerateText.titleTextView.setTextColor(enabledColor)
            }
            "Disabled" -> {
                cameraResolutionText.titleTextView.setTextColor(enabledColor)
                cameraFramerateText.titleTextView.setTextColor(enabledColor)
            }
            else -> {
                Log.w(this, "applyVideoDegradationMode() unhandled degradation=$degradation")
            }
        }
    }
}