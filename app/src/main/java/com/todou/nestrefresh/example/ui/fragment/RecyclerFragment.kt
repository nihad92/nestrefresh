package com.todou.nestrefresh.example.ui.fragment

import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.todou.nestrefresh.example.R
import com.todou.nestrefresh.example.ui.adapter.RecyclerAdapterScroll
import com.todou.nestrefresh.example.ui.widget.ItemDecoration

import java.util.Collections

class RecyclerFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecyclerAdapterScroll

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_recycler, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.addItemDecoration(
            ItemDecoration(context, RecyclerView.VERTICAL
                , resources.getDimensionPixelSize(R.dimen.margin_normal))
        )
        adapter = RecyclerAdapterScroll()
        recyclerView.adapter = adapter
        adapter.updateDatas(Collections.nCopies(20, Any()))
    }

    companion object {
        fun newInstance(): RecyclerFragment {
            return RecyclerFragment()
        }
    }
}
