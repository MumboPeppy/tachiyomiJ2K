package eu.kanade.tachiyomi.ui.manga

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.checkbox.checkBoxPrompt
import com.afollestad.materialdialogs.checkbox.isCheckPromptChecked
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadService
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.databinding.MangaDetailsControllerBinding
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.ui.base.controller.BaseController
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterHolder
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterItem
import eu.kanade.tachiyomi.ui.manga.chapter.ChaptersSortBottomSheet
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.ui.manga.track.TrackingBottomSheet
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.recents.RecentsController
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.source.BrowseController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.source.global_search.GlobalSearchController
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.addOrRemoveToFavorites
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.moveCategories
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.ThemeUtil
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getPrefTheme
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.isTablet
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsets
import eu.kanade.tachiyomi.util.view.getText
import eu.kanade.tachiyomi.util.view.requestFilePermissionsSafe
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.setStyle
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.toolbarHeight
import eu.kanade.tachiyomi.util.view.updatePaddingRelative
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.max

class MangaDetailsController :
    BaseController<MangaDetailsControllerBinding>,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    ActionMode.Callback,
    MangaDetailsAdapter.MangaDetailsInterface,
    FlexibleAdapter.OnItemMoveListener {

    constructor(
        manga: Manga?,
        fromCatalogue: Boolean = false,
        smartSearchConfig: BrowseController.SmartSearchConfig? = null,
        update: Boolean = false
    ) : super(
        Bundle().apply {
            putLong(MANGA_EXTRA, manga?.id ?: 0)
            putBoolean(FROM_CATALOGUE_EXTRA, fromCatalogue)
            putParcelable(SMART_SEARCH_CONFIG_EXTRA, smartSearchConfig)
            putBoolean(UPDATE_EXTRA, update)
        }
    ) {
        this.manga = manga
        if (manga != null) {
            source = Injekt.get<SourceManager>().getOrStub(manga.source)
        }
        presenter = MangaDetailsPresenter(this, manga!!, source!!)
    }

    constructor(mangaId: Long) : this(
        Injekt.get<DatabaseHelper>().getManga(mangaId).executeAsBlocking()
    )

    constructor(bundle: Bundle) : this(bundle.getLong(MANGA_EXTRA)) {
        val notificationId = bundle.getInt("notificationId", -1)
        val context = applicationContext ?: return
        if (notificationId > -1) NotificationReceiver.dismissNotification(
            context,
            notificationId,
            bundle.getInt("groupId", 0)
        )
    }

    private var manga: Manga? = null
    private var source: Source? = null
    var colorAnimator: ValueAnimator? = null
    val presenter: MangaDetailsPresenter
    var coverColor: Int? = null
    var toolbarIsColored = false
    private var snack: Snackbar? = null
    val fromCatalogue = args.getBoolean(FROM_CATALOGUE_EXTRA, false)
    private var trackingBottomSheet: TrackingBottomSheet? = null
    private var startingRangeChapterPos: Int? = null
    private var rangeMode: RangeMode? = null
    private var editMangaDialog: EditMangaDialog? = null
    var refreshTracker: Int? = null
    var chapterPopupMenu: Pair<Int, PopupMenu>? = null

    // Tablet Layout
    var isTablet = false
    private var tabletAdapter: MangaDetailsAdapter? = null

    private var query = ""
    private var adapter: MangaDetailsAdapter? = null

    private var actionMode: ActionMode? = null

    var headerHeight = 0
    var fullCoverActive = false

    override fun getTitle(): String? {
        return null
    }

    override fun createBinding(inflater: LayoutInflater) = MangaDetailsControllerBinding.inflate(inflater)

    //region UI Methods
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        coverColor = null
        fullCoverActive = false

        setTabletMode(view)
        setRecycler(view)
        setPaletteColor()
        adapter?.fastScroller = binding.fastScroller
        binding.fastScroller.addOnScrollStateChangeListener {
            activityBinding?.appBar?.y = 0f
        }

        presenter.onCreate()
        binding.swipeRefresh.isRefreshing = presenter.isLoading
        binding.swipeRefresh.setOnRefreshListener { presenter.refreshAll() }
        requestFilePermissionsSafe(301, presenter.preferences, presenter.manga.isLocal())
    }

    /** Check if device is tablet, and use a second recycler to hold the details header if so */
    private fun setTabletMode(view: View) {
        isTablet = view.context.isTablet() &&
            view.context.resources.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.tabletOverlay.isVisible = isTablet
        binding.tabletRecycler.isVisible = isTablet
        binding.tabletDivider.isVisible = isTablet
        if (isTablet) {
            binding.recycler.updateLayoutParams<ViewGroup.LayoutParams> { width = 0 }
            tabletAdapter = MangaDetailsAdapter(this)
            binding.tabletRecycler.adapter = tabletAdapter
            binding.tabletRecycler.layoutManager = LinearLayoutManager(view.context)
        }
    }

    override fun onDestroyView(view: View) {
        snack?.dismiss()
        presenter.onDestroy()
        adapter = null
        trackingBottomSheet = null
        super.onDestroyView(view)
    }

    /** Set adapter, insets, and scroll listener for recycler view */
    private fun setRecycler(view: View) {
        adapter = MangaDetailsAdapter(this)

        binding.recycler.adapter = adapter
        adapter?.isSwipeEnabled = true
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        binding.recycler.addItemDecoration(
            MangaDetailsDivider(view.context)
        )
        binding.recycler.setHasFixedSize(true)
        val attrsArray = intArrayOf(android.R.attr.actionBarSize)
        val array = view.context.obtainStyledAttributes(attrsArray)
        val appbarHeight = array.getDimensionPixelSize(0, 0)
        array.recycle()
        val offset = 10.dpToPx
        binding.swipeRefresh.setStyle()
        binding.swipeRefresh.setDistanceToTriggerSync(70.dpToPx)
        activityBinding!!.appBar.elevation = 0f

        if (isTablet) {
            val tHeight = toolbarHeight.takeIf { it ?: 0 > 0 } ?: appbarHeight
            headerHeight = tHeight + (activityBinding?.root?.rootWindowInsets?.systemWindowInsetTop ?: 0)
            binding.recycler.updatePaddingRelative(top = headerHeight + 4.dpToPx)
            binding.recycler.doOnApplyWindowInsets { _, insets, _ ->
                setInsets(insets, appbarHeight, offset)
            }
        } else {
            scrollViewWith(
                binding.recycler,
                padBottom = true,
                customPadding = true,
                afterInsets = { insets ->
                    setInsets(insets, appbarHeight, offset)
                },
                liftOnScroll = {
                    colorToolbar(it)
                }
            )
        }

        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (!isTablet) {
                        val atTop = !recyclerView.canScrollVertically(-1)
                        val tY = getHeader()?.binding?.backdrop?.translationY ?: 0f
                        getHeader()?.binding?.backdrop?.translationY = max(0f, tY + dy * 0.25f)
                        if (atTop) getHeader()?.binding?.backdrop?.translationY = 0f
                    }
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    val atTop = !recyclerView.canScrollVertically(-1)
                    if (atTop) getHeader()?.binding?.backdrop?.translationY = 0f
                }
            }
        )
    }

    private fun setInsets(insets: WindowInsets, appbarHeight: Int, offset: Int) {
        binding.recycler.updatePaddingRelative(bottom = insets.systemWindowInsetBottom)
        binding.tabletRecycler.updatePaddingRelative(bottom = insets.systemWindowInsetBottom)
        val tHeight = toolbarHeight.takeIf { it ?: 0 > 0 } ?: appbarHeight
        headerHeight = tHeight + insets.systemWindowInsetTop
        binding.swipeRefresh.setProgressViewOffset(false, (-40).dpToPx, headerHeight + offset)
        if (isTablet) {
            binding.tabletOverlay.updateLayoutParams<ViewGroup.LayoutParams> { height = headerHeight }
            // 4dp extra to line up chapter header and manga header
            binding.recycler.updatePaddingRelative(top = headerHeight + 4.dpToPx)
        }
        getHeader()?.setTopHeight(headerHeight)
        binding.fastScroller.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = headerHeight
            bottomMargin = insets.systemWindowInsetBottom
        }
        binding.fastScroller.scrollOffset = headerHeight
    }

    /** Set the toolbar to fully transparent or colored and translucent */
    private fun colorToolbar(isColor: Boolean, animate: Boolean = true) {
        if (isColor == toolbarIsColored || (isTablet && isColor)) return
        toolbarIsColored = isColor
        val isCurrentController =
            router?.backstack?.lastOrNull()?.controller == this@MangaDetailsController
        if (isCurrentController) setTitle()
        if (actionMode != null) {
            activityBinding?.toolbar?.setBackgroundColor(Color.TRANSPARENT)
            return
        }
        val color =
            coverColor ?: activity!!.getResourceColor(R.attr.colorPrimaryVariant)
        val colorFrom =
            if (colorAnimator?.isRunning == true) activity?.window?.statusBarColor
                ?: color
            else ColorUtils.setAlphaComponent(
                color,
                if (toolbarIsColored) 0 else 175
            )
        val colorTo = ColorUtils.setAlphaComponent(
            color,
            if (toolbarIsColored) 175 else 0
        )
        colorAnimator?.cancel()
        if (animate) {
            colorAnimator = ValueAnimator.ofObject(
                android.animation.ArgbEvaluator(),
                colorFrom,
                colorTo
            )
            colorAnimator?.duration = 250 // milliseconds
            colorAnimator?.addUpdateListener { animator ->
                activityBinding?.toolbar?.setBackgroundColor(animator.animatedValue as Int)
                activity?.window?.statusBarColor = (animator.animatedValue as Int)
            }
            colorAnimator?.start()
        } else {
            activityBinding?.toolbar?.setBackgroundColor(colorTo)
            activity?.window?.statusBarColor = colorTo
        }
    }

    /** Get the color of the manga cover*/
    fun setPaletteColor() {
        val view = view ?: return

        val request = ImageRequest.Builder(view.context).data(presenter.manga).allowHardware(false).memoryCacheKey(presenter.manga.key())
            .target(
                onSuccess = { drawable ->
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    // Generate the Palette on a background thread.
                    if (bitmap != null) {
                        Palette.from(bitmap).generate {
                            if (it == null) return@generate
                            val colorBack = view.context.getResourceColor(
                                android.R.attr.colorBackground
                            )
                            // this makes the color more consistent regardless of theme
                            val backDropColor =
                                ColorUtils.blendARGB(it.getVibrantColor(colorBack), colorBack, .35f)

                            coverColor = backDropColor
                            getHeader()?.setBackDrop(backDropColor)
                            if (toolbarIsColored) {
                                val translucentColor =
                                    ColorUtils.setAlphaComponent(backDropColor, 175)
                                activityBinding?.toolbar?.setBackgroundColor(translucentColor)
                                activity?.window?.statusBarColor = translucentColor
                            }
                        }
                    }
                    binding.mangaCoverFull.setImageDrawable(drawable)
                    getHeader()?.updateCover(manga!!)
                },
                onError = {
                    val file = presenter.coverCache.getCoverFile(manga!!)
                    if (file.exists()) {
                        file.delete()
                        setPaletteColor()
                    }
                }
            ).build()
        view.context.imageLoader.enqueue(request)
    }

    /** Set toolbar theme for themes that are inverted (ie. light blue theme) */
    private fun setActionBar(forThis: Boolean) {
        val activity = activity as? MainActivity ?: return
        val activityBinding = activityBinding ?: return
        // if the theme is using inverted toolbar color
        if (ThemeUtil.hasDarkActionBarInLight(activity, activity.getPrefTheme(presenter.preferences))) {
            if (forThis) activityBinding.appBar.context.setTheme(
                R.style.ThemeOverlay_AppCompat_DayNight_ActionBar
            )
            else activityBinding.appBar.context.setTheme(
                R.style.Theme_ActionBar_Dark_DayNight
            )

            val iconPrimary = view?.context?.getResourceColor(
                if (forThis) android.R.attr.textColorPrimary
                else R.attr.actionBarTintColor
            ) ?: Color.BLACK
            activityBinding.toolbar.setTitleTextColor(iconPrimary)
            activity.drawerArrow?.color = iconPrimary
            activityBinding.toolbar.overflowIcon?.setTint(iconPrimary)
            activityBinding.mainContent.systemUiVisibility = if (forThis) {
                activityBinding.mainContent.systemUiVisibility.or(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                )
            } else activityBinding.mainContent.systemUiVisibility.rem(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            )
        }
    }

    private fun setStatusBarAndToolbar() {
        activity?.window?.statusBarColor = if (toolbarIsColored) {
            val translucentColor = ColorUtils.setAlphaComponent(coverColor ?: Color.TRANSPARENT, 175)
            activityBinding?.toolbar?.setBackgroundColor(translucentColor)
            translucentColor
        } else Color.TRANSPARENT
        activityBinding?.appBar?.setBackgroundColor(Color.TRANSPARENT)
        activityBinding?.toolbar?.setBackgroundColor(
            activity?.window?.statusBarColor
                ?: Color.TRANSPARENT
        )
    }

    //endregion

    //region Lifecycle methods
    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        presenter.isLockedFromSearch = SecureActivityDelegate.shouldBeLocked()
        presenter.headerItem.isLocked = presenter.isLockedFromSearch
        manga!!.thumbnail_url = presenter.refreshMangaFromDb().thumbnail_url
        presenter.fetchChapters(refreshTracker == null)
        if (refreshTracker != null) {
            trackingBottomSheet?.refreshItem(refreshTracker ?: 0)
            presenter.refreshTracking()
            refreshTracker = null
        }
        // fetch cover again in case the user set a new cover while reading
        setPaletteColor()
        val isCurrentController = router?.backstack?.lastOrNull()?.controller ==
            this
        if (isCurrentController) {
            setStatusBarAndToolbar()
        }
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter) {
            setActionBar(true)
            setStatusBarAndToolbar()
        } else {
            if (router.backstack.lastOrNull()?.controller is DialogController) {
                return
            }
            if (type == ControllerChangeType.POP_EXIT) {
                setActionBar(false)
                presenter.cancelScope()
            }
            colorAnimator?.cancel()

            getHeader()?.clearDescFocus()
            val colorSecondary = activity?.getResourceColor(
                R.attr.colorSecondary
            ) ?: Color.BLACK
            if (router.backstackSize > 0 &&
                router.backstack.last().controller !is MangaDetailsController
            ) {
                if (router.backstack.last().controller !is FloatingSearchInterface) {
                    activityBinding?.appBar?.setBackgroundColor(colorSecondary)
                }
                activityBinding?.toolbar?.setBackgroundColor(colorSecondary)
                activity?.window?.statusBarColor = activity?.getResourceColor(
                    android.R.attr.statusBarColor
                ) ?: colorSecondary
            }
        }
    }

    override fun onChangeEnded(
        changeHandler: ControllerChangeHandler,
        type: ControllerChangeType
    ) {
        super.onChangeEnded(changeHandler, type)
        if (type == ControllerChangeType.PUSH_ENTER) {
            binding.swipeRefresh.isRefreshing = presenter.isLoading
        }
        if (!type.isEnter) {
            activityBinding?.root?.clearFocus()
        }
    }
    //endregion

    fun isNotOnline(showSnackbar: Boolean = true): Boolean {
        if (activity == null || !activity!!.isOnline()) {
            if (showSnackbar) view?.snack(R.string.no_network_connection)
            return true
        }
        return false
    }

    fun showError(message: String) {
        binding.swipeRefresh.isRefreshing = presenter.isLoading
        view?.snack(message)
    }

    fun showChaptersRemovedPopup(deletedChapters: List<ChapterItem>) {
        val context = activity ?: return
        val deleteRemovedPref = presenter.preferences.deleteRemovedChapters()
        when (deleteRemovedPref.get()) {
            2 -> {
                presenter.deleteChapters(deletedChapters, false)
                return
            }
            1 -> return
            else -> {
                MaterialDialog(context).title(R.string.chapters_removed).message(
                    text = context.resources.getQuantityString(
                        R.plurals.deleted_chapters,
                        deletedChapters.size,
                        deletedChapters.size,
                        deletedChapters.joinToString("\n") { it.name }
                    )
                ).positiveButton(R.string.delete) {
                    presenter.deleteChapters(deletedChapters, false)
                    if (it.isCheckPromptChecked()) deleteRemovedPref.set(2)
                }.negativeButton(R.string.keep) {
                    if (it.isCheckPromptChecked()) deleteRemovedPref.set(1)
                }.cancelOnTouchOutside(false).checkBoxPrompt(R.string.remember_this_choice) {}.show()
            }
        }
    }

    fun setRefresh(enabled: Boolean) {
        binding.swipeRefresh.isRefreshing = enabled
    }

    //region Recycler methods
    fun updateChapterDownload(download: Download) {
        getHolder(download.chapter)?.notifyStatus(
            download.status,
            presenter.isLockedFromSearch,
            download.progress,
            true
        )
    }

    private fun getHolder(chapter: Chapter): ChapterHolder? {
        return binding.recycler.findViewHolderForItemId(chapter.id!!) as? ChapterHolder
    }

    private fun getHeader(): MangaHeaderHolder? {
        return if (isTablet) binding.tabletRecycler.findViewHolderForAdapterPosition(0) as? MangaHeaderHolder
        else binding.recycler.findViewHolderForAdapterPosition(0) as? MangaHeaderHolder
    }

    fun updateHeader() {
        binding.swipeRefresh.isRefreshing = presenter.isLoading
        adapter?.setChapters(presenter.chapters)
        tabletAdapter?.notifyDataSetChanged()
        addMangaHeader()
        activity?.invalidateOptionsMenu()
    }

    fun updateChapters(chapters: List<ChapterItem>) {
        view ?: return
        binding.swipeRefresh.isRefreshing = presenter.isLoading
        if (presenter.chapters.isEmpty() && fromCatalogue && !presenter.hasRequested) {
            launchUI { binding.swipeRefresh.isRefreshing = true }
            presenter.fetchChaptersFromSource()
        }
        tabletAdapter?.notifyDataSetChanged()
        adapter?.setChapters(chapters)
        addMangaHeader()
        colorToolbar(binding.recycler.canScrollVertically(-1))
        activity?.invalidateOptionsMenu()
    }

    private fun addMangaHeader() {
        if (tabletAdapter?.scrollableHeaders?.isEmpty() == true) {
            tabletAdapter?.removeAllScrollableHeaders()
            tabletAdapter?.addScrollableHeader(presenter.headerItem)
            adapter?.removeAllScrollableHeaders()
            adapter?.addScrollableHeader(presenter.tabletChapterHeaderItem!!)
        } else if (!isTablet && adapter?.scrollableHeaders?.isEmpty() == true) {
            adapter?.removeAllScrollableHeaders()
            adapter?.addScrollableHeader(presenter.headerItem)
        }
    }

    fun refreshAdapter() = adapter?.notifyDataSetChanged()

    override fun onItemClick(view: View?, position: Int): Boolean {
        val chapterItem = (adapter?.getItem(position) as? ChapterItem) ?: return false
        val chapter = chapterItem.chapter
        if (actionMode != null) {
            if (startingRangeChapterPos == null) {
                adapter?.addSelection(position)
                (binding.recycler.findViewHolderForAdapterPosition(position) as? BaseFlexibleViewHolder)
                    ?.toggleActivation()
                (binding.recycler.findViewHolderForAdapterPosition(position) as? ChapterHolder)
                    ?.notifyStatus(Download.State.CHECKED, false, 0)
                startingRangeChapterPos = position
                actionMode?.invalidate()
            } else {
                val startingPosition = startingRangeChapterPos ?: return false
                var chapterList = listOf<ChapterItem>()
                when {
                    startingPosition > position ->
                        chapterList = presenter.chapters.subList(position - 1, startingPosition)
                    startingPosition <= position ->
                        chapterList = presenter.chapters.subList(startingPosition - 1, position)
                }
                when (rangeMode) {
                    RangeMode.Download -> downloadChapters(chapterList)
                    RangeMode.Read -> markAsRead(chapterList)
                    RangeMode.Unread -> markAsUnread(chapterList)
                }
                presenter.fetchChapters(false)
                adapter?.removeSelection(startingPosition)
                (binding.recycler.findViewHolderForAdapterPosition(startingPosition) as? BaseFlexibleViewHolder)
                    ?.toggleActivation()
                startingRangeChapterPos = null
                rangeMode = null
                destroyActionModeIfNeeded()
            }
            return false
        }
        openChapter(chapter)

        return false
    }

    override fun onItemLongClick(position: Int) {
        val adapter = adapter ?: return
        val item = (adapter.getItem(position) as? ChapterItem) ?: return
        val descending = presenter.sortDescending()
        val items = listOf(
            MaterialMenuSheet.MenuSheetItem(
                0,
                if (descending) R.drawable.ic_eye_down_24dp else R.drawable.ic_eye_up_24dp,
                R.string.mark_previous_as_read
            ),
            MaterialMenuSheet.MenuSheetItem(
                1,
                if (descending) R.drawable.ic_eye_off_down_24dp else R.drawable.ic_eye_off_up_24dp,
                R.string.mark_previous_as_unread
            ),
            MaterialMenuSheet.MenuSheetItem(
                2,
                R.drawable.ic_eye_range_24dp,
                R.string.mark_range_as_read
            ),
            MaterialMenuSheet.MenuSheetItem(
                3,
                R.drawable.ic_eye_off_range_24dp,
                R.string.mark_range_as_unread
            )
        )
        val menuSheet = MaterialMenuSheet(activity!!, items, item.name) { _, itemPos ->
            when (itemPos) {
                0 -> markPreviousAs(item, true)
                1 -> markPreviousAs(item, false)
                2 -> startReadRange(position, RangeMode.Read)
                3 -> startReadRange(position, RangeMode.Unread)
            }
            true
        }
        menuSheet.show()
//        val popup = PopupMenu(itemView.context, itemView)
//        chapterPopupMenu = position to popup
//
//        // Inflate our menu resource into the PopupMenu's Menu
//        popup.menuInflater.inflate(R.menu.chapter_single, popup.menu)
//
//        popup.setOnMenuItemClickListener { menuItem ->
//            when (menuItem.itemId) {
//                R.id.action_mark_previous_as_read -> markPreviousAs(item, true)
//                R.id.action_mark_previous_as_unread -> markPreviousAs(item, false)
//            }
//            chapterPopupMenu = null
//            true
//        }
//
//        // Finally show the PopupMenu
//        popup.show()
    }

    override fun onActionStateChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        binding.swipeRefresh.isEnabled = actionState != ItemTouchHelper.ACTION_STATE_SWIPE
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) {
    }

    override fun shouldMoveItem(fromPosition: Int, toPosition: Int): Boolean {
        return true
    }
    //endregion

    fun dismissPopup(position: Int) {
        if (chapterPopupMenu != null && chapterPopupMenu?.first == position) {
            chapterPopupMenu?.second?.dismiss()
            chapterPopupMenu = null
        }
    }

    private fun markPreviousAs(chapter: ChapterItem, read: Boolean) {
        val adapter = adapter ?: return
        val chapters = if (presenter.sortDescending()) adapter.items.reversed() else adapter.items
        val chapterPos = chapters.indexOf(chapter)
        if (chapterPos != -1) {
            if (read) {
                markAsRead(chapters.take(chapterPos))
            } else {
                markAsUnread(chapters.take(chapterPos))
            }
        }
    }

    fun bookmarkChapter(position: Int) {
        val item = adapter?.getItem(position) as? ChapterItem ?: return
        val bookmarked = item.bookmark
        bookmarkChapters(listOf(item), !bookmarked)
        snack?.dismiss()
        snack = view?.snack(
            if (bookmarked) R.string.removed_bookmark
            else R.string.bookmarked,
            Snackbar.LENGTH_INDEFINITE
        ) {
            setAction(R.string.undo) {
                bookmarkChapters(listOf(item), bookmarked)
            }
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    fun toggleReadChapter(position: Int) {
        val item = adapter?.getItem(position) as? ChapterItem ?: return
        val chapter = item.chapter
        val lastRead = chapter.last_page_read
        val pagesLeft = chapter.pages_left
        val read = item.chapter.read
        presenter.markChaptersRead(listOf(item), !read, false)
        snack?.dismiss()
        snack = view?.snack(
            if (read) R.string.marked_as_unread
            else R.string.marked_as_read,
            Snackbar.LENGTH_INDEFINITE
        ) {
            var undoing = false
            setAction(R.string.undo) {
                presenter.markChaptersRead(listOf(item), read, true, lastRead, pagesLeft)
                undoing = true
            }
            addCallback(
                object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        super.onDismissed(transientBottomBar, event)
                        if (!undoing && !read && presenter.preferences.removeAfterMarkedAsRead()) {
                            presenter.deleteChapters(listOf(item))
                        }
                    }
                }
            )
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    private fun bookmarkChapters(chapters: List<ChapterItem>, bookmarked: Boolean) {
        presenter.bookmarkChapters(chapters, bookmarked)
    }

    private fun markAsRead(chapters: List<ChapterItem>) {
        presenter.markChaptersRead(chapters, true)
    }

    private fun markAsUnread(chapters: List<ChapterItem>) {
        presenter.markChaptersRead(chapters, false)
    }

    private fun openChapter(chapter: Chapter) {
        val activity = activity ?: return
        val intent = ReaderActivity.newIntent(activity, manga!!, chapter)
        startActivity(intent)
    }

    //region action bar menu methods
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        colorToolbar(binding.recycler.canScrollVertically(-1))
        activityBinding?.toolbar?.navigationIcon =
            activityBinding?.toolbar?.navigationIcon?.mutate()?.apply {
                setTint(view?.context?.getResourceColor(R.attr.actionBarTintColor) ?: Color.WHITE)
            }
        inflater.inflate(R.menu.manga_details, menu)
        val editItem = menu.findItem(R.id.action_edit)
        editItem.isVisible = presenter.manga.favorite && !presenter.isLockedFromSearch
        menu.findItem(R.id.action_download).isVisible = !presenter.isLockedFromSearch &&
            !presenter.manga.isLocal()
        menu.findItem(R.id.action_mark_all_as_read).isVisible =
            presenter.getNextUnreadChapter() != null && !presenter.isLockedFromSearch
        menu.findItem(R.id.action_mark_all_as_unread).isVisible =
            presenter.anyRead() && !presenter.isLockedFromSearch
        menu.findItem(R.id.action_remove_downloads).isVisible =
            presenter.hasDownloads() && !presenter.isLockedFromSearch &&
            !presenter.manga.isLocal()
        menu.findItem(R.id.remove_non_bookmarked).isVisible =
            presenter.hasBookmark() && !presenter.isLockedFromSearch
        menu.findItem(R.id.action_migrate).isVisible = !presenter.isLockedFromSearch &&
            manga?.source != LocalSource.ID && presenter.manga.favorite
        menu.findItem(R.id.action_migrate).title = view?.context?.getString(
            R.string.migrate_,
            presenter.manga.seriesType(view!!.context)
        )
        val iconPrimary = view?.context?.getResourceColor(android.R.attr.textColorPrimary)
            ?: Color.BLACK
        menu.findItem(R.id.action_download).icon?.mutate()?.setTint(iconPrimary)
        editItem.icon?.mutate()?.setTint(iconPrimary)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = resources?.getString(R.string.search_chapters)
        searchItem.icon?.mutate()?.setTint(iconPrimary)
        searchItem.collapseActionView()
        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, true)
            searchView.clearFocus()
        }

        setOnQueryTextChangeListener(searchView) {
            query = it ?: ""
            if (!isTablet) {
                if (query.isNotEmpty()) getHeader()?.collapse()
                else getHeader()?.expand()
            }

            adapter?.setFilter(query)
            adapter?.performFilter()
            true
        }
        searchItem.fixExpand(onExpand = { invalidateMenuOnExpand() })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_edit -> {
                editMangaDialog = EditMangaDialog(
                    this,
                    presenter.manga
                )
                editMangaDialog?.showDialog(router)
            }
            R.id.action_open_in_web_view -> openInWebView()
            R.id.action_refresh_tracking -> presenter.refreshTracking(true)
            R.id.action_migrate ->
                if (!isNotOnline()) {
                    PreMigrationController.navigateToMigration(
                        presenter.preferences.skipPreMigration().getOrDefault(),
                        router,
                        listOf(manga!!.id!!)
                    )
                }
            R.id.action_mark_all_as_read -> {
                MaterialDialog(view!!.context).message(R.string.mark_all_chapters_as_read)
                    .positiveButton(R.string.mark_as_read) {
                        markAsRead(presenter.chapters)
                    }.negativeButton(android.R.string.cancel).show()
            }
            R.id.remove_all, R.id.remove_read, R.id.remove_non_bookmarked -> massDeleteChapters(item.itemId)
            R.id.action_mark_all_as_unread -> {
                MaterialDialog(view!!.context).message(R.string.mark_all_chapters_as_unread)
                    .positiveButton(R.string.mark_as_unread) {
                        markAsUnread(presenter.chapters)
                    }.negativeButton(android.R.string.cancel).show()
            }
            R.id.download_next, R.id.download_next_5, R.id.download_custom, R.id.download_unread, R.id.download_all -> downloadChapters(
                item.itemId
            )
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
    //endregion

    fun saveCover() {
        if (presenter.saveCover()) {
            activity?.toast(R.string.cover_saved)
        } else {
            activity?.toast(R.string.error_saving_cover)
        }
    }

    fun shareCover() {
        val cover = presenter.shareCover()
        if (cover != null) {
            val stream = cover.getUriCompat(activity!!)
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, stream)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                clipData = ClipData.newRawUri(null, stream)
                type = "image/*"
            }
            startActivity(Intent.createChooser(intent, activity?.getString(R.string.share)))
        } else {
            activity?.toast(R.string.error_sharing_cover)
        }
    }

    override fun prepareToShareManga() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            presenter.shareManga()
        } else {
            shareManga()
        }
    }

    fun shareManga(cover: File? = null) {
        val context = view?.context ?: return

        val source = presenter.source as? HttpSource ?: return
        val stream = cover?.getUriCompat(context)
        try {
            val url = source.mangaDetailsRequest(presenter.manga).url.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/*"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_TITLE, presenter.manga.title)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (stream != null) {
                    clipData = ClipData.newRawUri(null, stream)
                }
            }
            startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    override fun openInWebView() {
        if (isNotOnline()) return
        val source = presenter.source as? HttpSource ?: return
        val url = try {
            source.mangaDetailsRequest(presenter.manga).url.toString()
        } catch (e: Exception) {
            return
        }

        val activity = activity ?: return
        val intent = WebViewActivity.newIntent(
            activity.applicationContext,
            source.id,
            url,
            presenter.manga
                .title
        )
        startActivity(intent)
    }

    private fun massDeleteChapters(choice: Int) {
        val chaptersToDelete = when (choice) {
            R.id.remove_all -> presenter.allChapters
            R.id.remove_non_bookmarked -> presenter.allChapters.filter { !it.bookmark }
            R.id.remove_read -> presenter.allChapters.filter { it.read }
            else -> emptyList()
        }.filter { it.isDownloaded }
        if (chaptersToDelete.isNotEmpty() || choice == R.id.remove_all) {
            massDeleteChapters(chaptersToDelete, choice == R.id.remove_all)
        } else {
            snack?.dismiss()
            snack = view?.snack(R.string.no_chapters_to_delete)
        }
    }

    private fun massDeleteChapters(chapters: List<ChapterItem>, isEverything: Boolean) {
        val context = view?.context ?: return
        MaterialDialog(context).message(
            text =
            if (isEverything) context.getString(R.string.remove_all_downloads)
            else context.resources.getQuantityString(
                R.plurals.remove_n_chapters,
                chapters.size,
                chapters.size
            )
        ).positiveButton(R.string.remove) {
            presenter.deleteChapters(chapters, isEverything = isEverything)
        }.negativeButton(android.R.string.cancel).show()
    }

    private fun downloadChapters(choice: Int) {
        val chaptersToDownload = when (choice) {
            R.id.download_next -> presenter.getUnreadChaptersSorted().take(1)
            R.id.download_next_5 -> presenter.getUnreadChaptersSorted().take(5)
            R.id.download_custom -> {
                createActionModeIfNeeded()
                rangeMode = RangeMode.Download
                return
            }
            R.id.download_unread -> presenter.allChapters.filter { !it.read }
            R.id.download_all -> presenter.allChapters
            else -> emptyList()
        }
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
    }

    private fun isLocked(): Boolean {
        if (presenter.isLockedFromSearch) {
            SecureActivityDelegate.promptLockIfNeeded(activity)
            return true
        }
        return false
    }

    //region Interface methods
    override fun coverColor(): Int? = coverColor
    override fun topCoverHeight(): Int = headerHeight

    override fun startDownloadNow(position: Int) {
        val chapter = (adapter?.getItem(position) as? ChapterItem) ?: return
        presenter.startDownloadingNow(chapter)
    }

    // In case the recycler is at the bottom and collapsing the header makes it unscrollable
    override fun updateScroll() {
        if (!binding.recycler.canScrollVertically(-1)) {
            getHeader()?.binding?.backdrop?.translationY = 0f
            activityBinding?.appBar?.y = 0f
            colorToolbar(isColor = false, animate = false)
        }
    }

    private fun downloadChapters(chapters: List<ChapterItem>) {
        val view = view ?: return
        presenter.downloadChapters(chapters)
        val text = view.context.getString(
            R.string.add_x_to_library,
            presenter.manga.seriesType
            (view.context).toLowerCase(Locale.ROOT)
        )
        if (!presenter.manga.favorite && (
            snack == null ||
                snack?.getText() != text
            )
        ) {
            snack = view.snack(text, Snackbar.LENGTH_INDEFINITE) {
                setAction(R.string.add) {
                    presenter.setFavorite(true)
                }
                addCallback(
                    object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            super.onDismissed(transientBottomBar, event)
                            if (snack == transientBottomBar) snack = null
                        }
                    }
                )
            }
            (activity as? MainActivity)?.setUndoSnackBar(snack)
        }
    }

    override fun startDownloadRange(position: Int) {
        createActionModeIfNeeded()
        rangeMode = RangeMode.Download
        onItemClick(null, position)
    }

    private fun startReadRange(position: Int, mode: RangeMode) {
        createActionModeIfNeeded()
        rangeMode = mode
        onItemClick(null, position)
    }

    override fun readNextChapter() {
        if (activity is SearchActivity && presenter.isLockedFromSearch) {
            SecureActivityDelegate.promptLockIfNeeded(activity)
            return
        }
        val item = presenter.getNextUnreadChapter()
        if (item != null) {
            openChapter(item.chapter)
        } else if (snack == null ||
            snack?.getText() != view?.context?.getString(R.string.next_chapter_not_found)
        ) {
            snack = view?.snack(R.string.next_chapter_not_found, Snackbar.LENGTH_LONG) {
                addCallback(
                    object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            super.onDismissed(transientBottomBar, event)
                            if (snack == transientBottomBar) snack = null
                        }
                    }
                )
            }
        }
    }

    override fun downloadChapter(position: Int) {
        val view = view ?: return
        val chapter = (adapter?.getItem(position) as? ChapterItem) ?: return
        if (actionMode != null) {
            onItemClick(null, position)
            return
        }
        if (chapter.status != Download.State.NOT_DOWNLOADED && chapter.status != Download.State.ERROR) {
            presenter.deleteChapter(chapter)
        } else {
            if (chapter.status == Download.State.ERROR) {
                DownloadService.start(view.context)
            } else {
                downloadChapters(listOf(chapter))
            }
        }
    }

    override fun tagClicked(text: String) {
        if (router.backstackSize < 2) {
            return
        }

        when (val previousController = router.backstack[router.backstackSize - 2].controller) {
            is LibraryController -> {
                router.handleBack()
                previousController.search(text)
            }
            is RecentsController -> {
                // Manually navigate to LibraryController
                router.handleBack()
                (activity as? MainActivity)?.goToTab(R.id.nav_library)
                val controller =
                    router.getControllerWithTag(R.id.nav_library.toString()) as LibraryController
                controller.search(text)
            }
            is BrowseSourceController -> {
                if (presenter.source is HttpSource) {
                    router.handleBack()
                    previousController.searchWithGenre(text)
                }
            }
        }
    }

    override fun globalSearch(text: String) {
        if (isNotOnline()) return
        router.pushController(GlobalSearchController(text).withFadeTransaction())
    }

    override fun showChapterFilter() {
        ChaptersSortBottomSheet(this).show()
    }

    override fun favoriteManga(longPress: Boolean) {
        if (isLocked()) return
        val manga = presenter.manga
        val categories = presenter.getCategories()
        if (!manga.favorite) {
            toggleMangaFavorite()
        } else {
            val favButton = getHeader()?.binding?.favoriteButton ?: return
            val popup = makeFavPopup(favButton, manga, categories)
            popup?.show()
        }
    }

    override fun setFavButtonPopup(popupView: View) {
        if (isLocked()) return
        val manga = presenter.manga
        if (!manga.favorite) {
            popupView.setOnTouchListener(null)
            return
        }
        val popup = makeFavPopup(popupView, manga, presenter.getCategories())
        popupView.setOnTouchListener(popup?.dragToOpenListener)
    }

    private fun makeFavPopup(popupView: View, manga: Manga, categories: List<Category>): PopupMenu? {
        val view = view ?: return null
        val popup = PopupMenu(view.context, popupView)
        popup.menu.add(0, 1, 0, R.string.remove_from_library)
        if (categories.isNotEmpty()) {
            popup.menu.add(0, 0, 1, R.string.edit_categories)
        }

        // Set a listener so we are notified if a menu item is clicked
        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == 0) {
                presenter.manga.moveCategories(presenter.db, activity!!) {
                    updateHeader()
                }
            } else {
                toggleMangaFavorite()
            }
            true
        }
        return popup
    }

    private fun toggleMangaFavorite() {
        val view = view ?: return
        val activity = activity ?: return
        snack?.dismiss()
        snack = presenter.manga.addOrRemoveToFavorites(
            presenter.db,
            presenter.preferences,
            view,
            activity,
            onMangaAdded = {
                updateHeader()
                showAddedSnack()
            },
            onMangaMoved = {
                updateHeader()
                if (presenter.preferences.autoAddTrack()) {
                    presenter.fetchChapters(andTracking = true)
                }
            },
            onMangaDeleted = { presenter.confirmDeletion() }
        )
        if (snack?.duration == Snackbar.LENGTH_INDEFINITE) {
            val favButton = getHeader()?.binding?.favoriteButton
            (activity as? MainActivity)?.setUndoSnackBar(snack, favButton)
        }
    }

    private fun showAddedSnack() {
        val view = view ?: return
        snack?.dismiss()
        snack = view.snack(view.context.getString(R.string.added_to_library))
    }

    override fun mangaPresenter(): MangaDetailsPresenter = presenter

    /**
     * Copies a string to clipboard
     *
     * @param content the actual text to copy to the board
     * @param label Label to show to the user describing the content
     */
    override fun copyToClipboard(content: String, label: Int) {
        if (content.isBlank()) return

        val activity = activity ?: return
        val view = view ?: return

        val contentType = view.context.getString(label)
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(contentType, content))

        snack = view.snack(view.context.getString(R.string._copied_to_clipboard, contentType))
    }

    override fun showTrackingSheet() {
        if (isLocked()) return
        trackingBottomSheet =
            TrackingBottomSheet(this)
        trackingBottomSheet?.show()
    }
    //endregion

    //region Tracking methods
    fun refreshTracking(trackings: List<TrackItem>) {
        trackingBottomSheet?.onNextTrackings(trackings)
    }

    fun onTrackSearchResults(results: List<TrackSearch>) {
        trackingBottomSheet?.onSearchResults(results)
    }

    fun refreshTracker() {
        getHeader()?.updateTracking()
    }

    fun trackRefreshDone() {
        trackingBottomSheet?.onRefreshDone()
    }

    fun trackRefreshError(error: Exception) {
        Timber.e(error)
        trackingBottomSheet?.onRefreshError(error)
    }

    fun trackSearchError(error: Exception) {
        Timber.e(error)
        trackingBottomSheet?.onSearchResultsError(error)
    }
    //endregion

    //region Action mode methods
    private fun createActionModeIfNeeded() {
        if (actionMode == null) {
            actionMode = (activity as AppCompatActivity).startSupportActionMode(this)
            activityBinding?.toolbar?.setBackgroundColor(Color.TRANSPARENT)
            val view = activity?.window?.currentFocus ?: return
            val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            if (adapter?.mode != SelectableAdapter.Mode.MULTI) {
                adapter?.mode = SelectableAdapter.Mode.MULTI
            }
        }
    }

    /**
     * Destroys the action mode.
     */
    private fun destroyActionModeIfNeeded() {
        actionMode?.finish()
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return true
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.title = view?.context?.getString(
            if (startingRangeChapterPos == null) {
                R.string.select_starting_chapter
            } else R.string.select_ending_chapter
        )
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        actionMode = null
        setStatusBarAndToolbar()
        if (startingRangeChapterPos != null && rangeMode == RangeMode.Download) {
            val item = adapter?.getItem(startingRangeChapterPos!!) as? ChapterItem
            (binding.recycler.findViewHolderForAdapterPosition(startingRangeChapterPos!!) as? ChapterHolder)?.notifyStatus(
                item?.status ?: Download.State.NOT_DOWNLOADED,
                false,
                0
            )
        }
        rangeMode = null
        startingRangeChapterPos = null
        adapter?.mode = SelectableAdapter.Mode.IDLE
        adapter?.clearSelection()
        return
    }
    //endregion

    fun changeCover() {
        if (manga?.favorite == true) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            startActivityForResult(
                Intent.createChooser(
                    intent,
                    resources?.getString(R.string.select_cover_image)
                ),
                101
            )
        } else {
            activity?.toast(R.string.must_be_in_library_to_edit)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 101) {
            if (data == null || resultCode != Activity.RESULT_OK) return
            val activity = activity ?: return
            try {
                val uri = data.data ?: return
                if (editMangaDialog != null) editMangaDialog?.updateCover(uri)
                else {
                    presenter.editCoverWithStream(uri)
                }
            } catch (error: IOException) {
                activity.toast(R.string.failed_to_update_cover)
                Timber.e(error)
            }
        }
    }

    override fun zoomImageFromThumb(thumbView: View) {
        if (fullCoverActive) return
        val drawable = binding.mangaCoverFull.drawable ?: return
        fullCoverActive = true
        drawable.alpha = 255
        val fullCoverDialog = FullCoverDialog(
            this,
            drawable,
            thumbView
        )
        fullCoverDialog.setOnDismissListener {
            fullCoverActive = false
        }
        fullCoverDialog.setOnCancelListener {
            fullCoverActive = false
        }
        fullCoverDialog.show()
    }

    companion object {
        const val UPDATE_EXTRA = "update"
        const val SMART_SEARCH_CONFIG_EXTRA = "smartSearchConfig"

        const val FROM_CATALOGUE_EXTRA = "from_catalogue"
        const val MANGA_EXTRA = "manga"

        private enum class RangeMode {
            Download,
            Read,
            Unread
        }
    }
}
