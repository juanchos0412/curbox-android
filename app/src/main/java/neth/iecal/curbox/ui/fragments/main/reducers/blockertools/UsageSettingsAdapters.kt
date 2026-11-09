package neth.iecal.curbox.ui.fragments.main.reducers.blockertools

import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import androidx.core.widget.doAfterTextChanged
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import neth.iecal.curbox.databinding.AppBlockerUsageLimitItemBinding

data class UsageDayItem(
    val name: String,
    var isEnabled: Boolean,
    var hours: Int,
    var minutes: Int,
    var isInteractionEnabled: Boolean = true
)

class UsageSettingsAdapter(
    private val items: List<UsageDayItem>,
    private val onUniformToggle: (Boolean) -> Unit,
    private val onDisabledClick: () -> Unit = {}
) : RecyclerView.Adapter<UsageSettingsAdapter.ViewHolder>() {

    class ViewHolder(val binding: AppBlockerUsageLimitItemBinding) : RecyclerView.ViewHolder(binding.root) {
        var hoursWatcher: TextWatcher? = null
        var minutesWatcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = AppBlockerUsageLimitItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding

        binding.daySwitch.setOnCheckedChangeListener(null)
        holder.hoursWatcher?.let { binding.hoursInput.removeTextChangedListener(it) }
        holder.minutesWatcher?.let { binding.minutesInput.removeTextChangedListener(it) }

        binding.dayNameText.text = item.name
        binding.daySwitch.isChecked = item.isEnabled
        
        if (!item.isInteractionEnabled) {
            binding.daySwitch.isEnabled = false
            binding.root.alpha = 0.5f
            binding.root.setOnClickListener {
                onDisabledClick()
            }
            binding.root.isFocusable = true
        } else {
            binding.daySwitch.isEnabled = true
            binding.root.alpha = 1.0f
            binding.root.setOnClickListener(null)
            // setOnClickListener leaves the row clickable even with a null listener, which makes
            // the row take remote focus instead of its switch and time fields.
            binding.root.isClickable = false
            binding.root.isFocusable = false
        }

        binding.hoursInput.setText(if (item.hours > 0) item.hours.toString() else "")
        binding.minutesInput.setText(if (item.minutes > 0) item.minutes.toString() else "")


        if (item.isEnabled) {
            binding.timeInputContainer.visibility = View.VISIBLE
            binding.timeInputContainer.alpha = 1.0f
        } else {
            binding.timeInputContainer.visibility = View.GONE
        }

        binding.daySwitch.setOnCheckedChangeListener { switchView, isChecked ->
            switchView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            item.isEnabled = isChecked
            
            if (isChecked) {
                binding.timeInputContainer.visibility = View.VISIBLE
                binding.timeInputContainer.animate().alpha(1f).setDuration(250).start()
            } else {
                binding.timeInputContainer.animate().alpha(0f).setDuration(200).withEndAction {
                    binding.timeInputContainer.visibility = View.GONE
                    item.hours = 0
                    item.minutes = 0
                    binding.hoursInput.text?.clear()
                    binding.minutesInput.text?.clear()
                }.start()
            }

            if (holder.adapterPosition == 0) {
                onUniformToggle(isChecked)
            }
        }

        holder.hoursWatcher = binding.hoursInput.doAfterTextChanged { s ->
            item.hours = s?.toString()?.toIntOrNull() ?: 0
            if (s?.length == 2) binding.minutesInput.requestFocus()
        }

        holder.minutesWatcher = binding.minutesInput.doAfterTextChanged { s ->
            item.minutes = s?.toString()?.toIntOrNull() ?: 0
        }
    }

    override fun getItemCount() = items.size
}
