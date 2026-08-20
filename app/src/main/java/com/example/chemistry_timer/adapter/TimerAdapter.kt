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
import com.example.chemistry_timer.util.ChemicalFormulaHelper

class TimerAdapter(
    private val onItemClick: (TimerEntity) -> Unit,
    private val onPlayClick: (TimerEntity) -> Unit,
    private val onStopClick: (TimerEntity) -> Unit,
    private val onDeleteClick: (TimerEntity) -> Unit
) : ListAdapter<TimerEntity, TimerAdapter.TimerViewHolder>(DiffCallback()) {

    class TimerViewHolder(val binding: ItemTimerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimerViewHolder {
        val binding = ItemTimerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TimerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TimerViewHolder, position: Int) {
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

            // Показываем кнопку Play или Pause
            btnPlay.setImageResource(
                if (timer.isRunning) android.R.drawable.ic_media_pause
                else R.drawable.ic_play
            )

            // Показываем кнопку Stop только если таймер запущен
            btnStop.visibility = if (timer.isRunning) View.VISIBLE else View.GONE
            btnStop.setImageResource(R.drawable.ic_stop)

            root.setOnClickListener { onItemClick(timer) }
            btnPlay.setOnClickListener { onPlayClick(timer) }
            btnStop.setOnClickListener { onStopClick(timer) }
            btnDelete.setOnClickListener { onDeleteClick(timer) }
        }
    }

    // Метод для обновления времени без полной перерисовки
    fun updateTimerTime(timerId: Long, remainingSeconds: Long) {
        val position = currentList.indexOfFirst { it.id == timerId }
        if (position != -1) {
            val timer = currentList[position].copy(remainingSeconds = remainingSeconds)
            notifyItemChanged(position)
        }
    }

    private fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    class DiffCallback : DiffUtil.ItemCallback<TimerEntity>() {
        override fun areItemsTheSame(old: TimerEntity, new: TimerEntity) = old.id == new.id
        override fun areContentsTheSame(old: TimerEntity, new: TimerEntity) = old == new
    }
}