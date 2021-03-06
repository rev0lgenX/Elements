package com.otaliastudios.elements.extensions

import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.otaliastudios.elements.Adapter
import com.otaliastudios.elements.Pager
import com.otaliastudios.elements.Presenter
import com.otaliastudios.elements.Source


public fun RecyclerView.setAdapter(lifecycleOwner: LifecycleOwner, block: (Adapter.Builder.() -> Unit)) {
    Adapter.Builder(lifecycleOwner).apply(block).into(this)
}

operator fun Adapter.Builder.plus(pager: Pager): Adapter.Builder {
    return setPager(pager)
}

operator fun Adapter.Builder.plus(source: Source<*>): Adapter.Builder {
    return addSource(source)
}

operator fun Adapter.Builder.plus(presenter: Presenter<*>): Adapter.Builder {
    return addPresenter(presenter)
}