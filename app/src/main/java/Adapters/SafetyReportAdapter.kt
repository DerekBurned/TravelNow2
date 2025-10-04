package com.example.travelnow

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.travelnow.databinding.ItemSafetyReportBinding
import models.SafetyReport

class SafetyReportAdapter(
    private var reports: List<SafetyReport>,
    private val onUpvoteClick: (SafetyReport) -> Unit,
    private val onDownvoteClick: (SafetyReport) -> Unit,
    private val onItemClick: (SafetyReport) -> Unit
) : RecyclerView.Adapter<SafetyReportAdapter.ReportViewHolder>() {

    inner class ReportViewHolder(private val binding: ItemSafetyReportBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(report: SafetyReport) {
            val safetyLevel = report.getSafetyLevelEnum()

            // Set indicator color
            binding.safetyIndicator.setBackgroundColor(safetyLevel.color)

            // Set safety level text and color
            binding.tvSafetyLevel.text = safetyLevel.displayName
            binding.tvSafetyLevel.setTextColor(safetyLevel.color)

            // Set timestamp
            binding.tvTimestamp.text = report.getFormattedDate()

            // Set area name, comment, and user name
            binding.tvAreaName.text = report.areaName
            binding.tvComment.text = report.comment
            binding.tvUserName.text = report.userName

            // Set vote count
            val voteCount = report.upvotes - report.downvotes
            binding.tvVoteCount.text = voteCount.toString()

            // Click listeners
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
        reports = newReports
        notifyDataSetChanged()
    }
}
