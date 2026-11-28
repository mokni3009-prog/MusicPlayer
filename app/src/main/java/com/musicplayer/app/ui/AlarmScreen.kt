package com.musicplayer.app.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.musicplayer.app.R
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.databinding.DialogAlarmEditBinding
import com.musicplayer.app.databinding.ItemAlarmBinding
import com.musicplayer.app.databinding.LayoutAlarmTabBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * v1.0.13: the alarm app screen (approved design 3) — big clock + date,
 * glowing next-alarm card, compact list of the other alarms, + FAB.
 * Shared by the ALARME tab (MainActivity) and the standalone AlarmActivity.
 */
class AlarmScreen(
    private val activity: AppCompatActivity,
    private val binding: LayoutAlarmTabBinding
) {

    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunning = false
    private var tickCount = 0
    private var nextAlarm: MusicAlarm? = null
    private var nextTriggerMs: Long = 0L

    private val adapter = AlarmAdapter(
        onToggle = { alarm, checked -> setEnabled(alarm, checked) },
        onClick = { alarm -> openEditDialog(alarm) }
    )

    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            tickCount++
            updateClock()
            // Recompute the list every 30 s (a new day, an alarm passing).
            if (tickCount % 30 == 0) refresh()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        binding.alarmList.layoutManager = LinearLayoutManager(activity)
        binding.alarmList.adapter = adapter
        binding.alarmFab.setOnClickListener { openEditDialog(null) }
        binding.alarmNextCard.setOnClickListener {
            nextAlarm?.let { openEditDialog(it) }
        }
        binding.alarmNextToggle.setOnCheckedChangeListener { _, checked ->
            nextAlarm?.let { setEnabled(it, checked) }
        }
    }

    /** Start the clock ticker (call from the host's onStart). */
    fun start() {
        if (tickerRunning) return
        tickerRunning = true
        tickCount = 0
        refresh()
        handler.post(ticker)
    }

    /** Stop the clock ticker (call from the host's onStop). */
    fun stop() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
    }

    /** Reload the alarms and redraw everything. */
    fun refresh() {
        val alarms = AlarmStore.load(activity)
        val now = System.currentTimeMillis()
        updateClock()
        val next = AlarmStore.nextAlarm(alarms, now)
        nextAlarm = next?.first
        nextTriggerMs = next?.second ?: 0L
        if (next == null) {
            binding.alarmNextCard.visibility = View.GONE
            binding.alarmEmptyHint.visibility = View.VISIBLE
            binding.alarmUpcomingTitle.visibility = View.GONE
            binding.alarmList.visibility = View.GONE
            binding.alarmNoOtherHint.visibility = View.GONE
            adapter.submit(emptyList())
            return
        }
        val (alarm, trigger) = next
        binding.alarmNextCard.visibility = View.VISIBLE
        binding.alarmEmptyHint.visibility = View.GONE
        binding.alarmNextTime.text =
            String.format(Locale.US, "%02d:%02d", alarm.hour, alarm.minute)
        binding.alarmNextInfo.text =
            "${alarm.name} • ${AlarmStore.formatCountdown(activity, trigger, now)}"
        binding.alarmNextToggle.setOnCheckedChangeListener(null)
        binding.alarmNextToggle.isChecked = alarm.enabled
        binding.alarmNextToggle.setOnCheckedChangeListener { _, checked ->
            setEnabled(alarm, checked)
        }
        val others = alarms.filter { it.id != alarm.id }
        binding.alarmUpcomingTitle.visibility = View.VISIBLE
        binding.alarmList.visibility = View.VISIBLE
        adapter.submit(others)
        binding.alarmNoOtherHint.visibility =
            if (others.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateClock() {
        val now = System.currentTimeMillis()
        binding.alarmClock.text =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        binding.alarmDate.text =
            SimpleDateFormat("EEEE d MMM yyyy", Locale.getDefault()).format(now)
        val next = nextAlarm
        if (next != null && nextTriggerMs > 0) {
            binding.alarmNextInfo.text =
                "${next.name} • ${AlarmStore.formatCountdown(activity, nextTriggerMs, now)}"
        }
    }

    private fun setEnabled(alarm: MusicAlarm, checked: Boolean) {
        val updated = alarm.copy(enabled = checked)
        AlarmStore.saveAlarm(activity, updated)
        if (checked) {
            AlarmScheduler.schedule(activity, updated)
        } else {
            AlarmScheduler.cancel(activity, updated)
        }
        refresh()
    }

    // ----- add / edit sheet -----

    private fun openEditDialog(existing: MusicAlarm?) {
        val dialog = BottomSheetDialog(activity)
        val d = DialogAlarmEditBinding.inflate(activity.layoutInflater)
        dialog.setContentView(d.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        var hour = existing?.hour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        var minute = existing?.minute ?: 0
        var days = existing?.days ?: 0
        var songId = existing?.songId ?: -1L
        var songTitle = existing?.songTitle ?: ""
        var fadeIn = existing?.fadeIn ?: true

        d.dialogAlarmTitle.setText(
            if (existing == null) R.string.alarm_new else R.string.alarm_edit
        )
        d.alarmNameInput.setText(existing?.name ?: "")
        d.alarmFadeSwitch.isChecked = fadeIn

        fun updateTime() {
            d.alarmHourText.text = String.format(Locale.US, "%02d", hour)
            d.alarmMinuteText.text = String.format(Locale.US, "%02d", minute)
        }
        updateTime()
        d.alarmHourUp.setOnClickListener { hour = (hour + 1) % 24; updateTime() }
        d.alarmHourDown.setOnClickListener { hour = (hour + 23) % 24; updateTime() }
        d.alarmMinuteUp.setOnClickListener { minute = (minute + 1) % 60; updateTime() }
        d.alarmMinuteDown.setOnClickListener { minute = (minute + 59) % 60; updateTime() }

        // Repeat-day chips (Monday first).
        val dayNames = AlarmStore.shortDayNames()
        val chipViews = ArrayList<TextView>(7)
        fun refreshChips() {
            for (i in 0..6) {
                val selected = days and (1 shl i) != 0
                chipViews[i].setBackgroundResource(
                    if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
                )
                chipViews[i].setTextColor(
                    activity.getColor(
                        if (selected) R.color.zeno_on_gradient else R.color.zeno_ink2
                    )
                )
            }
        }
        for (i in 0..6) {
            val chip = TextView(activity).apply {
                text = dayNames[i]
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                val size = dp(40)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            }
            chip.setOnClickListener {
                days = days xor (1 shl i)
                refreshChips()
            }
            d.alarmDaysRow.addView(chip)
            chipViews.add(chip)
        }
        refreshChips()

        fun refreshSongLabel() {
            d.alarmSongLabel.text =
                if (songTitle.isNotEmpty()) songTitle
                else activity.getString(R.string.alarm_no_song)
        }
        refreshSongLabel()
        d.alarmChooseBtn.setOnClickListener {
            showSongPicker(songId) { id, title ->
                songId = id
                songTitle = title
                refreshSongLabel()
            }
        }

        d.alarmFadeSwitch.setOnCheckedChangeListener { _, checked -> fadeIn = checked }

        d.alarmCancelBtn.setOnClickListener { dialog.dismiss() }
        d.alarmSaveBtn.setOnClickListener {
            if (songId < 0) {
                Toast.makeText(
                    activity, R.string.alarm_pick_song_first, Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val name = d.alarmNameInput.text?.toString()?.trim().orEmpty()
                .ifEmpty { activity.getString(R.string.alarm_default_name) }
            val alarm = MusicAlarm(
                id = existing?.id ?: UUID.randomUUID().toString(),
                hour = hour,
                minute = minute,
                name = name,
                days = days,
                songId = songId,
                songTitle = songTitle,
                fadeIn = fadeIn,
                enabled = existing?.enabled ?: true
            )
            AlarmStore.saveAlarm(activity, alarm)
            if (alarm.enabled) AlarmScheduler.schedule(activity, alarm)
            dialog.dismiss()
            refresh()
            Toast.makeText(
                activity,
                activity.getString(
                    R.string.alarm_saved,
                    String.format(Locale.US, "%02d:%02d", hour, minute)
                ),
                Toast.LENGTH_SHORT
            ).show()
        }

        if (existing != null) {
            d.alarmDeleteBtn.visibility = View.VISIBLE
            d.alarmDeleteBtn.setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle(existing.name)
                    .setMessage(
                        String.format(Locale.US, "%02d:%02d", existing.hour, existing.minute) +
                            " · " + AlarmStore.daysSummary(activity, existing.days)
                    )
                    .setPositiveButton(R.string.delete) { _, _ ->
                        AlarmScheduler.cancel(activity, existing)
                        AlarmStore.deleteAlarm(activity, existing.id)
                        dialog.dismiss()
                        refresh()
                        Toast.makeText(
                            activity, R.string.alarm_deleted, Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        dialog.show()
    }

    private fun showSongPicker(currentSongId: Long, onPick: (Long, String) -> Unit) {
        val songs = try {
            MusicRepository.loadSongs(activity)
        } catch (e: Exception) {
            emptyList()
        }
        if (songs.isEmpty()) {
            Toast.makeText(activity, R.string.alarm_no_library, Toast.LENGTH_SHORT).show()
            return
        }
        val titles = songs.map { "${it.title} — ${it.artist}" }.toTypedArray()
        val checked = songs.indexOfFirst { it.id == currentSongId }.coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(R.string.alarm_pick_song)
            .setSingleChoiceItems(titles, checked) { dialog, which ->
                onPick(songs[which].id, songs[which].title)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    // ----- alarm list -----

    private inner class AlarmAdapter(
        private val onToggle: (MusicAlarm, Boolean) -> Unit,
        private val onClick: (MusicAlarm) -> Unit
    ) : RecyclerView.Adapter<AlarmAdapter.Holder>() {

        private var items: List<MusicAlarm> = emptyList()

        fun submit(list: List<MusicAlarm>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(val b: ItemAlarmBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemAlarmBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val alarm = items[position]
            holder.b.itemAlarmTime.text =
                String.format(Locale.US, "%02d:%02d", alarm.hour, alarm.minute)
            holder.b.itemAlarmName.text =
                "${alarm.name} · ${AlarmStore.daysSummary(activity, alarm.days)}"
            holder.b.itemAlarmToggle.setOnCheckedChangeListener(null)
            holder.b.itemAlarmToggle.isChecked = alarm.enabled
            holder.b.itemAlarmToggle.setOnCheckedChangeListener { _, checked ->
                onToggle(alarm, checked)
            }
            holder.b.root.setOnClickListener { onClick(alarm) }
        }

        override fun getItemCount(): Int = items.size
    }
}
