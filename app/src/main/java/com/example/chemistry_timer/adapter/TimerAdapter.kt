package com.example.chemistry_timer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chemistry_timer.R
import com.example.chemistry_timer.data.TimerEntity
import com.example.chemistry_timer.databinding.ItemTimerBinding
import com.example.chemistry_timer.databinding.ItemNewTimerBinding
import com.example.chemistry_timer.util.ChemicalFormulaHelper
import java.util.Locale

class TimerAdapter(
    private val onItemClick: (TimerEntity) -> Unit,
    private val onPlayClick: (TimerEntity) -> Unit,
    private val onStopClick: (TimerEntity) -> Unit,
    private val onResetClick: (TimerEntity) -> Unit,
    private val onDeleteClick: (TimerEntity) -> Unit,
    private val onNewTimerClick: () -> Unit
) : ListAdapter<TimerEntity, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val VIEW_TYPE_TIMER = 0
        private const val VIEW_TYPE_NEW = 1
    }

    inner class TimerViewHolder(val binding: ItemTimerBinding) : RecyclerView.ViewHolder(binding.root)
    inner class NewTimerViewHolder(val binding: ItemNewTimerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return if (position == itemCount - 1) VIEW_TYPE_NEW else VIEW_TYPE_TIMER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_NEW -> {
                val binding = ItemNewTimerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                NewTimerViewHolder(binding)
            }
            else -> {
                val binding = ItemTimerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                TimerViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is NewTimerViewHolder -> {
                holder.binding.root.setOnClickListener { onNewTimerClick() }
            }
            is TimerViewHolder -> {
                val timer = getItem(position)
                holder.binding.apply {
                    tvTimerNumber.text = "#${timer.number}"
                    tvTimerName.text = timer.name.ifEmpty { "Без названия" }

                    if (timer.formula.isNotEmpty()) {
                        tvFormula.visibility = View.VISIBLE
                        tvFormula.text = ChemicalFormulaHelper.parseFormula(timer.formula)
                    } else {
                        tvFormula.visibility = View.GONE
                    }

                    val timeToShow = if (timer.remainingSeconds > 0) timer.remainingSeconds else timer.totalSeconds
                    tvTime.text = formatTime(timeToShow)

                    btnPlay.setImageResource(
                        if (timer.isRunning) android.R.drawable.ic_media_pause
                        else R.drawable.ic_play
                    )

                    btnStop.visibility = if (timer.isRunning) View.VISIBLE else View.GONE
                    btnStop.setImageResource(R.drawable.ic_stop)

                    // ИСПРАВЛЕНО: кнопка сброса видна только если таймер остановлен с ненулевым остатком
                    btnReset.visibility =
                        if (!timer.isRunning && timer.remainingSeconds > 0 && timer.remainingSeconds != timer.totalSeconds)
                            View.VISIBLE
                        else
                            View.GONE
                    btnReset.setOnClickListener { onResetClick(timer) }

                    root.setOnClickListener { onItemClick(timer) }
                    btnPlay.setOnClickListener { onPlayClick(timer) }
                    btnStop.setOnClickListener { onStopClick(timer) }
                    btnDelete.setOnClickListener { onDeleteClick(timer) }
                }
            }
        }
    }

    override fun getItemCount(): Int = super.getItemCount() + 1

    private fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    class DiffCallback : DiffUtil.ItemCallback<TimerEntity>() {
        override fun areItemsTheSame(old: TimerEntity, new: TimerEntity) = old.id == new.id
        override fun areContentsTheSame(old: TimerEntity, new: TimerEntity) = old == new
    }
}