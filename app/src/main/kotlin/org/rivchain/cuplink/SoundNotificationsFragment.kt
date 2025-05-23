package org.rivchain.cuplink

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

class SoundNotificationsFragment: Fragment(R.layout.fragment_settings_sound_notifications) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val settings = database.settings

        view.findViewById<SwitchMaterial>(R.id.enableMicrophoneByDefaultSwitch).apply {
            isChecked = settings.enableMicrophoneByDefault
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.enableMicrophoneByDefault = isChecked
                DatabaseCache.save()
            }
        }

        view.findViewById<SwitchMaterial>(R.id.pushToTalkSwitch).apply {
            isChecked = settings.pushToTalk
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.pushToTalk = isChecked
                DatabaseCache.save()
            }
        }

        setupRadioDialog(view,
            settings.speakerphoneMode,
            R.id.textSpeakerphoneModes,
            R.id.spinnerSpeakerphoneModes,
            R.array.speakerphoneModeLabels,
            R.array.speakerphoneModeValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        settings.speakerphoneMode = newValue
                        DatabaseCache.save()
                    }
                }
            })
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

}