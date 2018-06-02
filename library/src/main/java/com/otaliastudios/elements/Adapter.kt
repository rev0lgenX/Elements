package com.otaliastudios.elements

import android.arch.lifecycle.*
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.util.SparseArray
import android.view.ViewGroup

public final class Adapter private constructor(
        private val lifecycleOwner: LifecycleOwner,
        private val sources: MutableList<Source<*>>,
        private val presenters: List<Presenter<*>>,
        private val pageSizeHint: Int
) : RecyclerView.Adapter<Presenter.Holder>(), LifecycleOwner, LifecycleObserver {

    public class Builder(private val lifecycleOwner: LifecycleOwner, private val pageSizeHint: Int = Int.MAX_VALUE) {
        private val sources = mutableListOf<Source<*>>()
        private val presenters = mutableListOf<Presenter<*>>()

        public fun addSource(source: Source<*>): Builder {
            sources.add(source)
            return this
        }

        public fun addPresenter(presenter: Presenter<*>): Builder {
            presenters.add(presenter)
            return this
        }

        public fun build() = Adapter(lifecycleOwner, sources, presenters, pageSizeHint)

        public fun into(recyclerView: RecyclerView) {
            recyclerView.adapter = build()
        }
    }

    companion object {
        fun builder(lifecycleOwner: LifecycleOwner, expectedPageSize: Int = Int.MAX_VALUE) = Builder(lifecycleOwner, expectedPageSize)

        private val TAG = Adapter::class.java.simpleName

        private fun log(what: String) {
            if (true) Log.e(TAG, what)
        }
    }

    override fun getLifecycle() = lifecycleOwner.lifecycle

    init {
        lifecycle.addObserver(this)
        for (presenter in presenters) {
            presenter.owner = lifecycleOwner
            presenter.adapter = this
        }
    }

    private val typeMap: SparseArray<Presenter<*>> = SparseArray()
    private val dependencyMap: MutableMap<Source<*>, MutableSet<Source<*>>> = mutableMapOf()
    private val reverseDependencyMap: MutableMap<Source<*>, MutableSet<Source<*>>> = mutableMapOf()

    internal fun getDependencies(source: Source<*>): MutableSet<Source<*>> {
        if (!dependencyMap.containsKey(source)) {
            dependencyMap[source] = mutableSetOf()
        }
        return dependencyMap[source]!!
    }

    internal fun hasDependencies(source: Source<*>) = getDependencies(source).isNotEmpty()

    internal fun getReverseDependencies(source: Source<*>): MutableSet<Source<*>> {
        if (!reverseDependencyMap.containsKey(source)) {
            reverseDependencyMap[source] = mutableSetOf()
        }
        return reverseDependencyMap[source]!!
    }

    internal fun dependsOn(source: Source<*>, other: Source<*>) = getDependencies(source).contains(other)

    init {
        // This ensures that every couple is processed. There must be some Kotlin
        // magic to do this in a more concise way, but this is OK for now.
        for (i in 0 until sources.size - 1) {
            for (j in i + 1 until sources.size) {
                val source1 = sources[i]
                val source2 = sources[j]
                val dep1 = source1.dependsOn(source2)
                val dep2 = source2.dependsOn(source1)
                if (dep1 && dep2) {
                    throw IllegalArgumentException("Circular dependency. Source " +
                            source1::class.java.simpleName + " and source " +
                            source2::class.java.simpleName + " both depend on each other.")
                } else if (dep1) {
                    // Surely source2 does not depend on source1.
                    // But we must check recursively that none
                    // of source2 dependencies depends on source1.
                    checkCircularDependencies(source1, source2)
                    getDependencies(source1).add(source2)
                    getReverseDependencies(source2).add(source1)
                } else if (dep2) {
                    // Same
                    checkCircularDependencies(source2, source1)
                    getDependencies(source2).add(source1)
                    getReverseDependencies(source1).add(source2)
                }
            }
        }

        sources.sortWith(Comparator { source1, source2 ->
            when {
                dependsOn(source1, source2) -> 1
                dependsOn(source2, source1) -> -1
                else -> 0
            }
        })
    }

    private fun checkCircularDependencies(source: Source<*>, target: Source<*>) {
        if (target.dependsOn(source)) throw IllegalArgumentException("Indirect circular dependency detected.")
        for (dependency in getDependencies(target)) {
            checkCircularDependencies(source, dependency)
        }
    }

    // Must be done after the init() so it gets sorted sources.
    private val pager: Pager

    init {
        // Now that sources are OK, let's subscribe to any LiveData they already have.
        var existingPage: Page? = null
        sources.forEach { source ->
            source.getCurrentResults().forEach {
                existingPage = it.key
                onSourceLiveData(it.key, source, it.value)
            }
        }

        // If we are restoring, get the existing pager. If not, create a new one.
        pager = existingPage?.pager ?: Pager(sources)
        pager.bind(this)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    private fun onDestroy() {
        pager.unbind()
    }

    /**
     * Got a new page.
     */
    internal fun onPageCreated(page: Page) {
        sources.filter { !hasDependencies(it) }.forEach { source ->
            val data = source.openPage(page, listOf())
            onSourceLiveData(page, source, data)
        }
    }

    /**
     * This source has results.
     * 1. Update the Recycler
     * 2. Notify sources depending on this source
     */
    private fun onSourceResults(page: Page, source: Source<*>, result: List<Element<*>>) {
        pager.setResults(page, source, result)
        // These are the sources that might be interested in this.
        val dependents = getReverseDependencies(source)
        for (dependent in dependents) {
            val dependencies = getDependencies(dependent)
            if (dependencies.all { it.hasResultsForPage(page) }) {
                val list = mutableListOf<Element<*>>()
                dependencies.forEach { list.addAll(it.getResultsForPage(page)) }
                if (dependent.knowsPage(page)) {
                    dependent.onPageChanged(page, list)
                } else {
                    val data = dependent.openPage(page, list)
                    onSourceLiveData(page, dependent, data)
                }
            }
        }
    }

    /**
     * This source provided us with a new LiveData object. Let's subscribe
     * to stay updated. This might happen after an openPage, or after rebinding to
     * sources that have values already.
     */
    private fun onSourceLiveData(page: Page, source: Source<*>, data: LiveData<out List<Element<*>>>) {
        @Suppress("UNCHECKED_CAST")
        val cast = data as LiveData<List<Element<*>>>
        cast.observe(this, Observer {
            if (it != null) onSourceResults(page, source, it)
        })
    }

    override fun getItemCount() = pager.elementCount()

    override fun getItemViewType(position: Int): Int {
        return pager.elementAt(position).element!!.type
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Presenter.Holder {
        // Find a reasonable Presenter for this elementType. Try with cache map.
        var presenter = typeMap.get(viewType)
        if (presenter == null) {
            presenter = presenters.firstOrNull { it.elementTypes.contains(viewType) }
            typeMap.put(viewType, presenter)
        }
        Log.i("Adapter", "onCreateViewHolder, type: $viewType")
        return presenter.createHolder(parent, viewType)
    }

    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: Presenter.Holder, position: Int) {
        val presenter = typeMap.get(holder.itemViewType) as Presenter<Any>
        val query = pager.elementAt(position)
        val page = query.page!!
        val element = query.element as Element<Any>
        Log.w("Adapter", "onBindViewHolder, type: ${holder.itemViewType}, " +
                "elementType: ${element.type}, " +
                "position: $position, " +
                "presenter: ${presenter.javaClass.simpleName}, " +
                "data: ${element.data?.javaClass?.simpleName}, " +
                "source: ${element.source.javaClass.simpleName}")
        if (element.type != holder.itemViewType) {
            throw RuntimeException("Something is wrong here...")
        }
        presenter.onBind(page, holder, element)

        if (query.positionInPage == pageSizeHint - 1 && page.isLastPage()) {
            pager.requestPage()
        }
    }

    /**
     * Request a new page to be opened.
     * This can be used in conjunction with an infinite [pageSizeHint],
     * in order to disable automatic page opening.
     */
    public fun openPage() {
        pager.requestPage()
    }

}
