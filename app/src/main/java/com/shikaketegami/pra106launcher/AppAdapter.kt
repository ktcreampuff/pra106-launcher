package com.shikaketegami.pra106launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * グリッド（item_app）とドック（item_dock）の両方で使うアダプタ。
 * 並べ替えモード中はタップ・長押しメニューを無効にし、ドラッグだけを受け付ける。
 */
class AppAdapter(
    private val layoutRes: Int,
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (View, AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private val apps = ArrayList<AppInfo>()

    /** 並べ替えモード中は true（タップ起動・長押しメニューを無効化） */
    var reorderMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /** アイコンの大きさ(dp)。変更すると全体を再バインドする */
    var iconSizeDp: Int = 64
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /** アイコンの倍率（ドックは 0.85） */
    var iconScale: Float = 1f

    /** アイコンの上限(dp)。0 以下なら上限なし（ドックは 72） */
    var iconMaxDp: Int = 0

    /** 0 より大きいとき、項目の幅を「アイコン + この値(dp)」に動的設定する（ドック用） */
    var itemExtraWidthDp: Int = 0

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView? = view.findViewById(R.id.appLabel)
    }

    fun submit(list: List<AppInfo>) {
        apps.clear()
        apps.addAll(list)
        notifyDataSetChanged()
    }

    fun items(): List<AppInfo> = apps.toList()

    fun move(from: Int, to: Int) {
        if (from !in apps.indices || to !in apps.indices) return
        val item = apps.removeAt(from)
        apps.add(to, item)
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.icon.contentDescription = app.label
        holder.label?.text = app.label
        val density = holder.itemView.resources.displayMetrics.density
        var iconDp = iconSizeDp * iconScale
        if (iconMaxDp > 0) iconDp = minOf(iconDp, iconMaxDp.toFloat())
        val px = (iconDp * density).toInt()
        if (itemExtraWidthDp > 0) {
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                width = ((iconDp + itemExtraWidthDp) * density).toInt()
            }
        }
        holder.icon.layoutParams = holder.icon.layoutParams.apply {
            width = px
            height = px
        }
        holder.label?.textSize = labelSp(iconSizeDp)
        if (reorderMode) {
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isClickable = false
            holder.itemView.isLongClickable = false
        } else {
            holder.itemView.setOnClickListener { onClick(app) }
            holder.itemView.setOnLongClickListener {
                onLongClick(it, app)
                true
            }
        }
    }

    override fun getItemCount(): Int = apps.size

    private fun labelSp(dp: Int): Float = when {
        dp <= 48 -> 12f
        dp <= 64 -> 13f
        dp <= 80 -> 14f
        else -> 16f
    }
}
