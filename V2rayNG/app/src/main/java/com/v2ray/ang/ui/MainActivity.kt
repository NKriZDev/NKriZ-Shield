package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MigrateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestSubSettingActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        initGroupTab()
    }
    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {
        }

        override fun onTabReselected(tab: TabLayout.Tab?) {
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    // register activity result for requesting permission
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                when (pendingAction) {
                    Action.IMPORT_QR_CODE_CONFIG ->
                        scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))

                    Action.READ_CONTENT_FROM_URI ->
                        chooseFileForCustomConfig.launch(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }, getString(R.string.title_file_chooser)))

                    Action.POST_NOTIFICATIONS -> {}
                    else -> {}
                }
            } else {
                toast(R.string.toast_permission_denied)
            }
            pendingAction = Action.NONE
        }

    private var pendingAction: Action = Action.NONE
    private var flowInProgress = false
    private var flowConnectTriggered = false
    private var flowAwaitingPing = false
    private var userDisconnectRequested = false
    private var suppressDisconnectReset = false

    enum class Action {
        NONE,
        IMPORT_QR_CODE_CONFIG,
        READ_CONTENT_FROM_URI,
        POST_NOTIFICATIONS
    }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) {
            readContentFromUri(uri)
        }
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.app_name)
        // Hide/disable toolbar/nav UI
        binding.toolbar.visibility = View.GONE
        binding.navView.visibility = View.GONE

        // Ensure server list UI stays hidden on the main tab
        // Server list UI removed from main; ensure hidden
        binding.serverListContainer.isVisible = false
        binding.tabGroup.isVisible = false

        binding.btnOpenServerList.setOnClickListener { showServerList() }
        binding.btnCloseServerList.setOnClickListener { hideServerList() }
        binding.btnAdvancedSettingsMain.setOnClickListener { openDrawer() }
        binding.btnReloadSubscription.setOnClickListener { importConfigViaSub() }
        binding.btnTestAndSelectBest.setOnClickListener { testAndSelectBestServer() }
        binding.btnSelectNextServer.setOnClickListener { selectNextServer() }
        binding.btnFlowStart.setOnClickListener { startFlowSequence() }

        applyMainShortcutVisibility()
        binding.btnConnect.setOnLongClickListener {
            val show = !MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_MAIN_SHORTCUTS, false)
            MmkvManager.encodeSettings(AppConfig.PREF_SHOW_MAIN_SHORTCUTS, show)
            applyMainShortcutVisibility()
            toast(if (show) R.string.toast_hidden_controls_on else R.string.toast_hidden_controls_off)
            true
        }
        resetFlowUi()

        binding.btnConnect.setOnClickListener { handleConnectClick() }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            } else {
//                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
        }
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        binding.navView.setNavigationItemSelectedListener(this)

        initGroupTab()
        setupViewModel()
        migrateLegacy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingAction = Action.POST_NOTIFICATIONS
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.searchViewServer.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                mainViewModel.filterConfig(query.orEmpty())
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                mainViewModel.filterConfig(newText.orEmpty())
                return false
            }
        })
    }

    private fun showServerList() {
        binding.serverListContainer.isVisible = true
        binding.btnOpenServerList.isVisible = false
        binding.tabGroup.isVisible = true
        binding.connectBlock.isVisible = false
        binding.layoutTest.isVisible = false
        binding.btnCloseServerList.isVisible = true
        binding.btnAdvancedSettingsMain.isVisible = false
    }

    private fun hideServerList() {
        binding.serverListContainer.isVisible = false
        applyMainShortcutVisibility()
        binding.tabGroup.isVisible = false
        binding.connectBlock.isVisible = true
        binding.layoutTest.isVisible = true
        binding.btnCloseServerList.isVisible = false
    }

    private fun applyMainShortcutVisibility() {
        val show = MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_MAIN_SHORTCUTS, false)
        binding.btnOpenServerList.isVisible = show
        binding.btnAdvancedSettingsMain.isVisible = show
    }

    private fun openDrawer() {
        binding.navView.visibility = View.VISIBLE
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun showAddConfigDialog() {
        val options = listOf(
            Pair(getString(R.string.menu_item_import_config_qrcode)) { importQRcode() },
            Pair(getString(R.string.menu_item_import_config_clipboard)) { importClipboard() },
            Pair(getString(R.string.menu_item_import_config_local)) { importConfigLocal() },
            Pair(getString(R.string.menu_item_import_config_manually_vmess)) { importManually(EConfigType.VMESS.value) },
            Pair(getString(R.string.menu_item_import_config_manually_vless)) { importManually(EConfigType.VLESS.value) },
            Pair(getString(R.string.menu_item_import_config_manually_ss)) { importManually(EConfigType.SHADOWSOCKS.value) },
            Pair(getString(R.string.menu_item_import_config_manually_socks)) { importManually(EConfigType.SOCKS.value) },
            Pair(getString(R.string.menu_item_import_config_manually_http)) { importManually(EConfigType.HTTP.value) },
            Pair(getString(R.string.menu_item_import_config_manually_trojan)) { importManually(EConfigType.TROJAN.value) },
            Pair(getString(R.string.menu_item_import_config_manually_wireguard)) { importManually(EConfigType.WIREGUARD.value) },
            Pair(getString(R.string.menu_item_import_config_manually_hysteria2)) { importManually(EConfigType.HYSTERIA2.value) }
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_item_add_config)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMoreActionsDialog() {
        val actions = listOf(
            Pair(getString(R.string.title_service_restart)) { restartV2Ray() },
            Pair(getString(R.string.title_ping_all_server)) { mainViewModel.testAllTcping() },
            Pair(getString(R.string.title_real_ping_all_server)) { mainViewModel.testAllRealPing() },
            Pair(getString(R.string.title_export_all)) { exportAll() },
            Pair(getString(R.string.title_del_all_config)) { delAllConfig() },
            Pair(getString(R.string.title_del_invalid_config)) { delInvalidConfig() },
            Pair(getString(R.string.title_create_intelligent_selection_all_server)) {
                if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "0") {
                    toast(getString(R.string.pre_resolving_domain))
                }
                mainViewModel.createIntelligentSelectionAll()
            },
            Pair(getString(R.string.title_sort_by_test_results)) { sortByTestResults() },
            Pair(getString(R.string.title_sub_update)) { importConfigViaSub() }
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.more_actions)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddManuallyDialog() {
        // no-op, manual items now live inside add config dialog
    }

    private fun handleConnectClick() {
        if (mainViewModel.isRunning.value == true) {
            userDisconnectRequested = true
            V2RayServiceManager.stopVService(this)
        } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun updateConnectButton(isRunning: Boolean) {
        adapter.isRunning = isRunning
        if (isRunning) {
            suppressDisconnectReset = false
            binding.btnConnect.text = getString(R.string.action_disconnect)
            binding.btnConnect.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_button_bg))
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
            binding.layoutTest.isFocusable = true
        } else {
            binding.btnConnect.text = getString(R.string.action_connect)
            binding.btnConnect.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_button_bg))
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
            binding.tabGroup.isVisible = false
            if (!suppressDisconnectReset || userDisconnectRequested) {
                clearFlowEffects()
            }
            userDisconnectRequested = false
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) {
            setTestState(it)
            if (flowAwaitingPing && isPingSuccess(it)) {
                onFlowPingSuccess()
            }
        }
        mainViewModel.connectionFailureAction.observe(this) {
            suppressDisconnectReset = false
            userDisconnectRequested = false
            clearFlowEffects()
        }
        mainViewModel.isRunning.observe(this) { isRunning -> updateConnectButton(isRunning) }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MigrateManager.migrateServerConfig2Profile()
            launch(Dispatchers.Main) {
                if (result) {
                    toast(getString(R.string.migration_success))
                    mainViewModel.reloadServerList()
                } else {
                    //toast(getString(R.string.migration_fail))
                }
            }

        }
    }

    private fun initGroupTab() {
        binding.tabGroup.removeOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.removeAllTabs()

        val (listId, listRemarks) = mainViewModel.getSubscriptions(this)
        if (listId == null || listRemarks == null) {
            return
        }

        for (it in listRemarks.indices) {
            val tab = binding.tabGroup.newTab()
            tab.text = listRemarks[it]
            tab.tag = listId[it]
            binding.tabGroup.addTab(tab)
        }
        val selectIndex =
            listId.indexOf(mainViewModel.subscriptionId).takeIf { it >= 0 } ?: (listId.count() - 1)
        binding.tabGroup.selectTab(binding.tabGroup.getTabAt(selectIndex))
        binding.tabGroup.addOnTabSelectedListener(tabGroupListener)
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.intelligent_selection_all -> {
            if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "0") {
                toast(getString(R.string.pre_resolving_domain))
            }
            mainViewModel.createIntelligentSelectionAll()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }


        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        startActivity(
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerActivity::class.java)
        )
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
        } else {
            pendingAction = Action.IMPORT_QR_CODE_CONFIG
            requestPermissionLauncher.launch(permission)
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> initGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    binding.pbWaiting.hide()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    private fun importConfigViaSub(): Boolean {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            if (mainViewModel.subscriptionId.isNullOrBlank()) {
                mainViewModel.subscriptionIdChanged(AngConfigManager.SERVER_SUB_ID)
            }
            val count = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) {
                    toast(getString(R.string.title_update_config_count, count))
                    mainViewModel.reloadServerList()
                } else {
                    toastError(R.string.toast_failure)
                }
                binding.pbWaiting.hide()
            }
        }
        return true
    }

    private fun exportAll() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                binding.pbWaiting.hide()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                binding.pbWaiting.hide()
            }
        }
    }

    private fun testAndSelectBestServer(): Boolean {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val bestGuid = mainViewModel.testAndSelectBestServer()
            if (!bestGuid.isNullOrEmpty()) {
                mainViewModel.sortCurrentGroupByTestResults()
            }
            withContext(Dispatchers.Main) {
                if (!bestGuid.isNullOrEmpty()) {
                    val profile = MmkvManager.decodeServerConfig(bestGuid)
                    val delay = MmkvManager.decodeServerAffiliationInfo(bestGuid)?.testDelayMillis ?: 0L
                    if (profile != null && delay > 0L) {
                        toast(getString(R.string.toast_best_server_selected, profile.remarks, delay))
                    } else {
                        toast(R.string.toast_success)
                    }
                    mainViewModel.reloadServerList()
                } else {
                    toastError(R.string.toast_failure)
                }
                binding.pbWaiting.hide()
            }
        }
        return true
    }

    private fun startFlowSequence(): Boolean {
        if (flowInProgress) {
            return true
        }
        flowInProgress = true
        flowConnectTriggered = false
        flowAwaitingPing = false
        resetFlowUi()

        binding.lineFlowUpdateActive.visibility = View.VISIBLE
        binding.progressFlow.visibility = View.VISIBLE
        binding.progressUpdate.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            if (mainViewModel.subscriptionId.isNullOrBlank()) {
                mainViewModel.subscriptionIdChanged(AngConfigManager.SERVER_SUB_ID)
            }
            val count = mainViewModel.updateConfigViaSubAll()
            withContext(Dispatchers.Main) {
                binding.progressFlow.visibility = View.INVISIBLE
                binding.progressUpdate.visibility = View.INVISIBLE
                if (count > 0) {
                    binding.ringFlowDone.visibility = View.VISIBLE
                    binding.ringUpdateDone.visibility = View.VISIBLE
                    binding.lineUpdateSpeedtestActive.visibility = View.VISIBLE
                    mainViewModel.reloadServerList()
                    startSpeedtestFlow()
                } else {
                    flowInProgress = false
                    toastError(R.string.toast_failure)
                }
            }
        }
        return true
    }

    private fun startSpeedtestFlow() {
        binding.progressSpeedtest.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val servers = mainViewModel.serversCache.toList()
            if (servers.isEmpty()) {
                withContext(Dispatchers.Main) {
                    binding.progressSpeedtest.visibility = View.INVISIBLE
                    flowInProgress = false
                    toastError(R.string.toast_failure)
                }
                return@launch
            }

            SpeedtestManager.closeAllTcpSockets()
            MmkvManager.clearAllTestDelayResults(servers.map { it.guid })

            val thresholdMs = 500L
            val minCount = 6
            var underThresholdCount = 0
            var bestGuid: String? = null
            var bestDelay = Long.MAX_VALUE
            var connectTriggered = false

            for (item in servers) {
                val serverAddress = item.profile.server
                val serverPort = item.profile.serverPort
                val testResult = if (!serverAddress.isNullOrBlank() && !serverPort.isNullOrBlank()) {
                    SpeedtestManager.tcping(serverAddress, serverPort.toInt())
                } else {
                    -1L
                }

                MmkvManager.encodeServerTestDelayMillis(item.guid, testResult)
                mainViewModel.updateListAction.postValue(mainViewModel.getPosition(item.guid))

                if (testResult > 0) {
                    if (testResult < thresholdMs) {
                        underThresholdCount += 1
                    }
                    if (testResult < bestDelay) {
                        bestDelay = testResult
                        bestGuid = item.guid
                    }
                }

                if (!connectTriggered && underThresholdCount > 5) {
                    connectTriggered = true
                    withContext(Dispatchers.Main) {
                        startConnectFlow(bestGuid)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                binding.progressSpeedtest.visibility = View.INVISIBLE
                binding.ringSpeedtestDone.visibility = View.VISIBLE
                mainViewModel.sortCurrentGroupByTestResults()
                mainViewModel.reloadServerList()
                if (!connectTriggered) {
                    startConnectFlow(bestGuid)
                }
            }
        }
    }

    private fun startConnectFlow(bestGuid: String?) {
        if (flowConnectTriggered) {
            return
        }
        flowConnectTriggered = true
        userDisconnectRequested = false
        binding.lineSpeedtestConnectActive.visibility = View.VISIBLE
        binding.progressConnect.visibility = View.VISIBLE

        if (!bestGuid.isNullOrBlank()) {
            MmkvManager.setSelectServer(bestGuid)
            mainViewModel.reloadServerList()
        }

        if (mainViewModel.isRunning.value == true) {
            suppressDisconnectReset = true
            V2RayServiceManager.stopVService(this)
            lifecycleScope.launch {
                delay(500)
                startV2Ray()
            }
        } else {
            suppressDisconnectReset = false
            startV2Ray()
        }
        flowAwaitingPing = true
    }

    private fun onFlowPingSuccess() {
        flowAwaitingPing = false
        binding.progressConnect.visibility = View.INVISIBLE
        binding.ringConnectDone.visibility = View.VISIBLE
        showCloudGlow()
        flowInProgress = false
    }

    private fun isPingSuccess(content: String?): Boolean {
        if (content.isNullOrBlank()) {
            return false
        }
        if (!content.contains("ms")) {
            return false
        }
        return content.any { it.isDigit() }
    }

    private fun resetFlowUi() {
        binding.progressFlow.visibility = View.INVISIBLE
        binding.progressUpdate.visibility = View.INVISIBLE
        binding.progressSpeedtest.visibility = View.INVISIBLE
        binding.progressConnect.visibility = View.INVISIBLE

        binding.ringFlowDone.visibility = View.INVISIBLE
        binding.ringUpdateDone.visibility = View.INVISIBLE
        binding.ringSpeedtestDone.visibility = View.INVISIBLE
        binding.ringConnectDone.visibility = View.INVISIBLE

        binding.lineFlowUpdateActive.visibility = View.INVISIBLE
        binding.lineUpdateSpeedtestActive.visibility = View.INVISIBLE
        binding.lineSpeedtestConnectActive.visibility = View.INVISIBLE
        hideCloudGlow()
    }

    private fun clearFlowEffects() {
        resetFlowUi()
        flowInProgress = false
        flowConnectTriggered = false
        flowAwaitingPing = false
    }

    private fun selectNextServer(): Boolean {
        val servers = mainViewModel.serversCache
        if (servers.isEmpty()) {
            toastError(R.string.toast_failure)
            return true
        }

        val selectedGuid = MmkvManager.getSelectServer()
        val currentIndex = servers.indexOfFirst { it.guid == selectedGuid }
        val nextIndex = if (currentIndex >= 0) currentIndex + 1 else 0
        if (nextIndex >= servers.size) {
            toast(R.string.toast_no_next_server)
            return true
        }

        val nextServer = servers[nextIndex]
        val delay = MmkvManager.decodeServerAffiliationInfo(nextServer.guid)?.testDelayMillis ?: 0L
        if (delay <= 0L) {
            toast(R.string.toast_next_server_unreachable)
            return true
        }

        MmkvManager.setSelectServer(nextServer.guid)
        mainViewModel.reloadServerList()
        toast(getString(R.string.toast_next_server_selected, nextServer.profile.remarks, delay))

        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
            lifecycleScope.launch {
                try {
                    delay(500)
                    V2RayServiceManager.startVService(this@MainActivity)
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to restart V2Ray service", e)
                }
            }
        }
        return true
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pendingAction = Action.READ_CONTENT_FROM_URI
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    importBatchConfig(input?.bufferedReader()?.readText())
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun setTestState(content: String?) {
        val lines = content?.split("\n") ?: emptyList()
        val ping = lines.firstOrNull()?.takeIf { it.contains("ms") }
        val country = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

        if (ping != null) {
            binding.btnConnect.text = if (country != null) {
                "$ping\n$country"
            } else {
                ping
            }
            binding.tvTestState.text = getString(R.string.connection_connected)
            showCloudGlow()
        } else {
            binding.tvTestState.text = content
            if (mainViewModel.isRunning.value != true) {
                binding.btnConnect.text = getString(R.string.action_connect)
                hideCloudGlow()
            }
        }
    }

    private fun showCloudGlow() {
        binding.cloudGlow.animate().cancel()
        if (binding.cloudGlow.visibility != View.VISIBLE) {
            binding.cloudGlow.alpha = 0f
            binding.cloudGlow.visibility = View.VISIBLE
        }
        binding.cloudGlow.animate()
            .alpha(1f)
            .setDuration(1800)
            .start()
    }

    private fun hideCloudGlow() {
        binding.cloudGlow.animate().cancel()
        binding.cloudGlow.alpha = 0f
        binding.cloudGlow.visibility = View.INVISIBLE
    }

//    val mConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName?) {
//        }
//
//        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
//            sendMsg(AppConfig.MSG_REGISTER_CLIENT, "")
//        }
//    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.sub_setting -> requestSubSettingActivity.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> startActivity(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestSubSettingActivity.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> startActivity(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra("isRunning", mainViewModel.isRunning.value == true)
            )
            R.id.add_config -> showAddConfigDialog()
            R.id.more_actions -> showMoreActionsDialog()

            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}
