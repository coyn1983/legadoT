package io.legado.app.ui.main.bookshelf.style2

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.databinding.ItemBookshelfGridGroupBinding
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.databinding.ItemBookshelfListGroupBinding
import io.legado.app.lib.theme.cardBackgroundColor
import io.legado.app.ui.main.bookshelf.bindBook
import splitties.views.onLongClick

/**
 * style2 书架适配器：列表/网格两款合一，[isGrid] 选款，viewType 区分 Book(0)/BookGroup(1)。
 * 书籍项绑定复用 [bindBook]（style2 列表不显示"最近更新时间"列，withLastUpdateTime=false）。
 */
class BooksAdapter(
    context: Context,
    callBack: CallBack,
    private val isGrid: Boolean,
) : BaseBooksAdapter<RecyclerView.ViewHolder>(context, callBack) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when {
            viewType == 1 && isGrid ->
                GridGroupViewHolder(ItemBookshelfGridGroupBinding.inflate(inflater, parent, false))

            viewType == 1 ->
                ListGroupViewHolder(ItemBookshelfListGroupBinding.inflate(inflater, parent, false))

            isGrid ->
                GridBookViewHolder(ItemBookshelfGridBinding.inflate(inflater, parent, false))

            else ->
                ListBookViewHolder(ItemBookshelfListBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val item = getItem(position) ?: return
        when {
            holder is ListBookViewHolder && item is Book -> {
                registerListener(holder, item)
                holder.onBind(item, payloads)
            }

            holder is GridBookViewHolder && item is Book -> {
                registerListener(holder, item)
                holder.onBind(item, payloads)
            }

            holder is ListGroupViewHolder && item is BookGroup -> {
                registerListener(holder, item)
                holder.onBind(item, payloads)
            }

            holder is GridGroupViewHolder && item is BookGroup -> {
                registerListener(holder, item)
                holder.onBind(item, payloads)
            }
        }
    }

    private fun registerListener(holder: RecyclerView.ViewHolder, item: Any) {
        val cover: View? = when (holder) {
            is ListBookViewHolder -> holder.binding.ivCover
            is GridBookViewHolder -> holder.binding.ivCover
            else -> null
        }
        holder.itemView.setOnClickListener {
            callBack.onItemClick(item)
        }
        holder.itemView.onLongClick {
            callBack.onItemLongClick(item, cover)
        }
    }

    inner class ListBookViewHolder(val binding: ItemBookshelfListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book, payloads: MutableList<Any>) {
            binding.bindBook(
                item,
                isUpdate = callBack.isUpdate(item.bookUrl),
                withLastUpdateTime = false,
                payloads = payloads,
            )
        }
    }

    inner class GridBookViewHolder(val binding: ItemBookshelfGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book, payloads: MutableList<Any>) {
            binding.bindBook(
                item,
                isUpdate = callBack.isUpdate(item.bookUrl),
                payloads = payloads,
            )
        }
    }

    inner class ListGroupViewHolder(val binding: ItemBookshelfListGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: BookGroup, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                tvName.text = item.groupName
                ivCover.load(item.cover)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "groupName" -> tvName.text = item.groupName
                            "cover" -> ivCover.load(item.cover)
                        }
                    }
                }
            }
        }
    }

    inner class GridGroupViewHolder(val binding: ItemBookshelfGridGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: BookGroup, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                tvName.text = item.groupName
                ivCover.load(item.cover)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "groupName" -> tvName.text = item.groupName
                            "cover" -> ivCover.load(item.cover)
                        }
                    }
                }
            }
        }
    }
}
