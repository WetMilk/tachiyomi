package eu.kanade.tachiyomi.ui.catalogue

import android.content.res.Configuration
import android.os.Bundle
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.SearchView
import android.support.v7.widget.Toolbar
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import com.afollestad.materialdialogs.MaterialDialog
import com.f2prateek.rx.preferences.Preference
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.ui.base.adapter.FlexibleViewHolder
import eu.kanade.tachiyomi.ui.base.fragment.BaseRxFragment
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaActivity
import eu.kanade.tachiyomi.util.getResourceDrawable
import eu.kanade.tachiyomi.util.snack
import eu.kanade.tachiyomi.util.toast
import eu.kanade.tachiyomi.widget.DividerItemDecoration
import eu.kanade.tachiyomi.widget.EndlessScrollListener
import kotlinx.android.synthetic.main.fragment_catalogue.*
import kotlinx.android.synthetic.main.toolbar.*
import nucleus.factory.RequiresPresenter
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.subjects.PublishSubject
import timber.log.Timber
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Fragment that shows the manga from the catalogue.
 * Uses R.layout.fragment_catalogue.
 */
@RequiresPresenter(CataloguePresenter::class)
class CatalogueFragment : BaseRxFragment<CataloguePresenter>(), FlexibleViewHolder.OnListItemClickListener {

    /**
     * Spinner shown in the toolbar to change the selected source.
     */
    private lateinit var spinner: Spinner

    /**
     * Adapter containing the list of manga from the catalogue.
     */
    private lateinit var adapter: CatalogueAdapter

    /**
     * Scroll listener for grid mode. It loads next pages when the end of the list is reached.
     */
    private lateinit var gridScrollListener: EndlessScrollListener

    /**
     * Scroll listener for list mode. It loads next pages when the end of the list is reached.
     */
    private lateinit var listScrollListener: EndlessScrollListener

    /**
     * Query of the search box.
     */
    private val query: String?
        get() = presenter.query

    /**
     * Selected index of the spinner (selected source).
     */
    private var selectedIndex: Int = 0

    /**
     * Time in milliseconds to wait for input events in the search query before doing network calls.
     */
    private val SEARCH_TIMEOUT = 1000L

    /**
     * Subject to debounce the query.
     */
    private val queryDebouncerSubject = PublishSubject.create<String>()

    /**
     * Subscription of the debouncer subject.
     */
    private var queryDebouncerSubscription: Subscription? = null

    /**
     * Subscription of the number of manga per row.
     */
    private var numColumnsSubscription: Subscription? = null

    /**
     * Display mode of the catalogue (list or grid mode).
     */
    private var displayMode: MenuItem? = null

    /**
     * Search item.
     */
    private var searchItem: MenuItem? = null

    /**
     * Property to get the toolbar from the containing activity.
     */
    private val toolbar: Toolbar
        get() = (activity as MainActivity).toolbar

    companion object {
        /**
         * Creates a new instance of this fragment.
         *
         * @return a new instance of [CatalogueFragment].
         */
        fun newInstance(): CatalogueFragment {
            return CatalogueFragment()
        }
    }

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_catalogue, container, false)
    }

    override fun onViewCreated(view: View, savedState: Bundle?) {
        // Initialize adapter, scroll listener and recycler views
        adapter = CatalogueAdapter(this)

        val glm = catalogue_grid.layoutManager as GridLayoutManager
        gridScrollListener = EndlessScrollListener(glm, { requestNextPage() })
        catalogue_grid.setHasFixedSize(true)
        catalogue_grid.adapter = adapter
        catalogue_grid.addOnScrollListener(gridScrollListener)

        val llm = LinearLayoutManager(activity)
        listScrollListener = EndlessScrollListener(llm, { requestNextPage() })
        catalogue_list.setHasFixedSize(true)
        catalogue_list.adapter = adapter
        catalogue_list.layoutManager = llm
        catalogue_list.addOnScrollListener(listScrollListener)
        catalogue_list.addItemDecoration(DividerItemDecoration(context.theme.getResourceDrawable(R.attr.divider_drawable)))

        if (presenter.isListMode) {
            switcher.showNext()
        }

        numColumnsSubscription = getColumnsPreferenceForCurrentOrientation().asObservable()
                .doOnNext { catalogue_grid.spanCount = it }
                .skip(1)
                // Set again the adapter to recalculate the covers height
                .subscribe { catalogue_grid.adapter = adapter }

        switcher.inAnimation = AnimationUtils.loadAnimation(activity, android.R.anim.fade_in)
        switcher.outAnimation = AnimationUtils.loadAnimation(activity, android.R.anim.fade_out)

        // Create toolbar spinner
        val themedContext = activity.supportActionBar?.themedContext ?: activity

        val spinnerAdapter = ArrayAdapter(themedContext,
                android.R.layout.simple_spinner_item, presenter.sources)
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_item)

        val onItemSelected = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val source = spinnerAdapter.getItem(position)
                if (!presenter.isValidSource(source)) {
                    spinner.setSelection(selectedIndex)
                    context.toast(R.string.source_requires_login)
                } else if (source != presenter.source) {
                    selectedIndex = position
                    showProgressBar()
                    glm.scrollToPositionWithOffset(0, 0)
                    llm.scrollToPositionWithOffset(0, 0)
                    presenter.setActiveSource(source)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        }

        spinner = Spinner(themedContext).apply {
            adapter = spinnerAdapter
            selectedIndex = presenter.sources.indexOf(presenter.source)
            setSelection(selectedIndex)
            onItemSelectedListener = onItemSelected
        }

        setToolbarTitle("")
        toolbar.addView(spinner)

        showProgressBar()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.catalogue_list, menu)

        // Initialize search menu
        searchItem = menu.findItem(R.id.action_search).apply {
            val searchView = actionView as SearchView

            if (!query.isNullOrEmpty()) {
                expandActionView()
                searchView.setQuery(query, true)
                searchView.clearFocus()
            }
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    onSearchEvent(query, true)
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    onSearchEvent(newText, false)
                    return true
                }
            })
        }

        // Show next display mode
        displayMode = menu.findItem(R.id.action_display_mode).apply {
            val icon = if (presenter.isListMode)
                R.drawable.ic_view_module_white_24dp
            else
                R.drawable.ic_view_list_white_24dp
            setIcon(icon)
        }

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_display_mode -> swapDisplayMode()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        queryDebouncerSubscription = queryDebouncerSubject.debounce(SEARCH_TIMEOUT, MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { searchWithQuery(it) }
    }

    override fun onPause() {
        queryDebouncerSubscription?.unsubscribe()
        super.onPause()
    }

    override fun onDestroyView() {
        numColumnsSubscription?.unsubscribe()
        searchItem?.let {
            if (it.isActionViewExpanded) it.collapseActionView()
        }
        toolbar.removeView(spinner)
        super.onDestroyView()
    }

    /**
     * Called when the input text changes or is submitted.
     *
     * @param query the new query.
     * @param now whether to send the network call now or debounce it by [SEARCH_TIMEOUT].
     */
    private fun onSearchEvent(query: String, now: Boolean) {
        if (now) {
            searchWithQuery(query)
        } else {
            queryDebouncerSubject.onNext(query)
        }
    }

    /**
     * Restarts the request with a new query.
     *
     * @param newQuery the new query.
     */
    private fun searchWithQuery(newQuery: String) {
        // If text didn't change, do nothing
        if (query == newQuery)
            return

        showProgressBar()
        catalogue_grid.layoutManager.scrollToPosition(0)
        catalogue_list.layoutManager.scrollToPosition(0)

        presenter.restartPager(newQuery)
    }

    /**
     * Requests the next page (if available). Called from scroll listeners when they reach the end.
     */
    private fun requestNextPage() {
        if (presenter.hasNextPage()) {
            showGridProgressBar()
            presenter.requestNext()
        }
    }

    /**
     * Called from the presenter when the network request is received.
     *
     * @param page the current page.
     * @param mangas the list of manga of the page.
     */
    fun onAddPage(page: Int, mangas: List<Manga>) {
        hideProgressBar()
        if (page == 0) {
            adapter.clear()
            gridScrollListener.resetScroll()
            listScrollListener.resetScroll()
        }
        adapter.addItems(mangas)
    }

    /**
     * Called from the presenter when the network request fails.
     *
     * @param error the error received.
     */
    fun onAddPageError(error: Throwable) {
        hideProgressBar()
        Timber.e(error, error.message)

        catalogue_view.snack(error.message ?: "") {
            setAction(R.string.action_retry) {
                showProgressBar()
                presenter.retryPage()
            }
        }
    }

    /**
     * Called from the presenter when a manga is initialized.
     *
     * @param manga the manga initialized
     */
    fun onMangaInitialized(manga: Manga) {
        getHolder(manga)?.setImage(manga)
    }

    /**
     * Swaps the current display mode.
     */
    fun swapDisplayMode() {
        presenter.swapDisplayMode()
        val isListMode = presenter.isListMode
        val icon = if (isListMode)
            R.drawable.ic_view_module_white_24dp
        else
            R.drawable.ic_view_list_white_24dp
        displayMode?.setIcon(icon)
        switcher.showNext()
        if (!isListMode) {
            // Initialize mangas if going to grid view
            presenter.initializeMangas(adapter.items)
        }
    }

    /**
     * Returns a preference for the number of manga per row based on the current orientation.
     *
     * @return the preference.
     */
    fun getColumnsPreferenceForCurrentOrientation(): Preference<Int> {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
            presenter.prefs.portraitColumns()
        else
            presenter.prefs.landscapeColumns()
    }

    /**
     * Returns the view holder for the given manga.
     *
     * @param manga the manga to find.
     * @return the holder of the manga or null if it's not bound.
     */
    private fun getHolder(manga: Manga): CatalogueGridHolder? {
        return catalogue_grid.findViewHolderForItemId(manga.id!!) as? CatalogueGridHolder
    }

    /**
     * Shows the progress bar.
     */
    private fun showProgressBar() {
        progress.visibility = ProgressBar.VISIBLE
    }

    /**
     * Shows the progress bar at the end of the screen.
     */
    private fun showGridProgressBar() {
        progress_grid.visibility = ProgressBar.VISIBLE
    }

    /**
     * Hides active progress bars.
     */
    private fun hideProgressBar() {
        progress.visibility = ProgressBar.GONE
        progress_grid.visibility = ProgressBar.GONE
    }

    /**
     * Called when a manga is clicked.
     *
     * @param position the position of the element clicked.
     * @return true if the item should be selected, false otherwise.
     */
    override fun onListItemClick(position: Int): Boolean {
        val item = adapter.getItem(position) ?: return false

        val intent = MangaActivity.newIntent(activity, item, true)
        startActivity(intent)
        return false
    }

    /**
     * Called when a manga is long clicked.
     *
     * @param position the position of the element clicked.
     */
    override fun onListItemLongClick(position: Int) {
        val manga = adapter.getItem(position) ?: return

        val textRes = if (manga.favorite) R.string.remove_from_library else R.string.add_to_library

        MaterialDialog.Builder(activity)
                .items(getString(textRes))
                .itemsCallback { dialog, itemView, which, text ->
                    when (which) {
                        0 -> {
                            presenter.changeMangaFavorite(manga)
                            adapter.notifyItemChanged(position)
                        }
                    }
                }.show()
    }

}
