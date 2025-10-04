package com.example.travelnow

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import models.SafetyReport

class SafetyReportAdapter(
    private var reports: List<SafetyReport>,
    private val onUpvoteClick: (SafetyReport) -> Unit,
    private val onDownvoteClick: (SafetyReport) -> Unit,
    private val onItemClick: (SafetyReport) -> Unit
) : RecyclerView.Adapter<SafetyReportAdapter.ReportViewHolder>() {

    inner class ReportViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val safetyIndicator: View = itemView.findViewById(R.id.safetyIndicator)
        val tvSafetyLevel: TextView = itemView.findViewById(R.id.tvSafetyLevel)
        val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        val tvAreaName: TextView = itemView.findViewById(R.id.tvAreaName)
        val tvComment: TextView = itemView.findViewById(R.id.tvComment)
        val tvUserName: TextView = itemView.findViewById(R.id.tvUserName)
        val btnUpvote: ImageButton = itemView.findViewById(R.id.btnUpvote)
        val tvVoteCount: TextView = itemView.findViewById(R.id.tvVoteCount)
        val btnDownvote: ImageButton = itemView.findViewById(R.id.btnDownvote)

        fun bind(report: SafetyReport) {
            val safetyLevel = report.getSafetyLevelEnum()

            safetyIndicator.setBackgroundColor(safetyLevel.color)

            tvSafetyLevel.text = safetyLevel.displayName
            tvSafetyLevel.setTextColor(safetyLevel.color)

            tvTimestamp.text = report.getFormattedDate()

            tvAreaName.text = report.areaName

            tvComment.text = report.comment

            tvUserName.text = report.userName

            val voteCount = report.upvotes - report.downvotes
            tvVoteCount.text = voteCount.toString()

            btnUpvote.setOnClickListener { onUpvoteClick(report) }
            btnDownvote.setOnClickListener { onDownvoteClick(report) }
            itemView.setOnClickListener { onItemClick(report) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_safety_report, parent, false)
        return ReportViewHolder(view)
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

