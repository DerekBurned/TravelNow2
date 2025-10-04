package com.example.travelnow

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.travelnow.databinding.ItemSafetyReportBinding
import models.SafetyReport

class SafetyReportAdapter(
    private val onUpvoteClick: (SafetyReport) -> Unit,
    private val onDownvoteClick: (SafetyReport) -> Unit,
    private val onItemClick: (SafetyReport) -> Unit
) : RecyclerView.Adapter<SafetyReportAdapter.ReportViewHolder>() {

    private var reports = listOf<SafetyReport>()

    inner class ReportViewHolder(private val binding: ItemSafetyReportBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(report: SafetyReport) {
            val safetyLevel = report.getSafetyLevelEnum()

            binding.safetyIndicator.setBackgroundColor(safetyLevel.color)
            binding.tvSafetyLevel.text = safetyLevel.displayName
            binding.tvSafetyLevel.setTextColor(safetyLevel.color)
            binding.tvTimestamp.text = report.getFormattedDate()
            binding.tvAreaName.text = report.areaName
            binding.tvComment.text = report.comment
            binding.tvUserName.text = report.userName

            val voteCount = report.upvotes - report.downvotes
            binding.tvVoteCount.text = voteCount.toString()

            binding.btnUpvote.setOnClickListener { onUpvoteClick(report) }
            binding.btnDownvote.setOnClickListener { onDownvoteClick(report) }
            binding.root.setOnClickListener { onItemClick(report) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val binding = ItemSafetyReportBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ReportViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        holder.bind(reports[position])
    }

    override fun getItemCount(): Int = reports.size

    fun updateReports(newReports: List<SafetyReport>) {
        val diffCallback = ReportDiffCallback(reports, newReports)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        reports = newReports
        diffResult.dispatchUpdatesTo(this)
    }

    private class ReportDiffCallback(
        private val oldList: List<SafetyReport>,
        private val newList: List<SafetyReport>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos].id == newList[newPos].id
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            return old.latitude == new.latitude &&
                    old.longitude == new.longitude &&
                    old.safetyLevel == new.safetyLevel &&
                    old.comment == new.comment &&
                    old.upvotes == new.upvotes &&
                    old.downvotes == new.downvotes
        }
    }
}