package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
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
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.api.BackendApiClient
import com.v2ray.ang.api.SubscribeRequest
import com.v2ray.ang.billing.BillingManager
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.SubscriptionCache
class MainActivity : HelperBaseActivity() {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var billingManager: BillingManager
    private var speedJob: kotlinx.coroutines.Job? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)

        billingManager = BillingManager(this)

        binding.fab.setOnClickListener { handleFabAction() }
        
        binding.cardServerSelector.setOnClickListener {
            showServerSelectorDialog()
        }

        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    private fun showServerSelectorDialog() {
        if (mainViewModel.serversCache.isEmpty()) {
            toast(getString(R.string.title_server_list_empty))
            return
        }

        val serverNames = mainViewModel.serversCache.map { it.profile.remarks ?: "Unknown" }.toTypedArray()
        val currentSelectedGuid = MmkvManager.getSelectServer()
        var selectedIndex = mainViewModel.serversCache.indexOfFirst { it.guid == currentSelectedGuid }
        if (selectedIndex == -1) selectedIndex = 0

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Location")
            .setSingleChoiceItems(serverNames, selectedIndex) { dialog, which ->
                val selectedServer = mainViewModel.serversCache[which]
                MmkvManager.setSelectServer(selectedServer.guid)
                
                // Update the text directly or wait for applyRunningState
                binding.tvSelectedServer.text = selectedServer.profile.remarks
                
                if (mainViewModel.isRunning.value == true) {
                    restartV2Ray()
                }
                
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupViewModel() {
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun handleFabAction() {
        if (mainViewModel.isRunning.value == true) {
            applyRunningState(isLoading = true, isRunning = false)
            CoreServiceManager.stopVService(this)
            return
        }
        
        applyRunningState(isLoading = true, isRunning = false)
        
        // Use a BottomSheetDialog for Terms of Service (if not already accepted)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_subscription, null)
        dialog.setContentView(view)
        
        val cbTos = view.findViewById<android.widget.CheckBox>(R.id.cb_tos)
        val btnSubscribe = view.findViewById<android.widget.Button>(R.id.btn_subscribe)
        
        btnSubscribe.setOnClickListener {
            if (!cbTos.isChecked) {
                toast("Please agree to the ToS to continue")
                return@setOnClickListener
            }
            dialog.dismiss()
            applyRunningState(isLoading = true, isRunning = false)
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (BuildConfig.SKIP_SUBSCRIPTION) {
                        LogUtil.d(AppConfig.TAG, "SKIP_SUBSCRIPTION is true, using mock flow.")
                        val randomToken = "test_sandbox_token_" + java.util.UUID.randomUUID().toString()
                        val response = BackendApiClient.apiService.subscribe(SubscribeRequest(randomToken))
                        processSubscriptionResponse(response.subscription_url)
                    } else {
                        LogUtil.d(AppConfig.TAG, "SKIP_SUBSCRIPTION is false, launching Google Play flow.")
                        withContext(Dispatchers.Main) {
                            billingManager.initiatePurchaseFlow("mysc_monthly_sub_placeholder")
                            applyRunningState(isLoading = false, isRunning = false)
                        }
                    }
                } catch(e: Exception) {
                    LogUtil.e(AppConfig.TAG, "API Error: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        toast("Connection Error: ${e.message ?: "Unknown Error"}")
                        applyRunningState(isLoading = false, isRunning = false)
                    }
                }
            }
        }
        dialog.show()
    }

    private suspend fun processSubscriptionResponse(subUrl: String?) {
        if (subUrl != null) {
            val subItem = SubscriptionItem()
            subItem.remarks = "MySC Premium"
            subItem.url = subUrl
            subItem.allowInsecureUrl = true
            
            val newSubId = java.util.UUID.randomUUID().toString()
            MmkvManager.encodeSubscription(newSubId, subItem)
            val subCache = SubscriptionCache(newSubId, subItem)
            
            val result = AngConfigManager.updateConfigViaSub(subCache)
            if (result.successCount > 0) {
                val newServers = MmkvManager.decodeServerList(newSubId)
                if (newServers.isNotEmpty()) {
                    MmkvManager.setSelectServer(newServers[0])
                    withContext(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                    }
                }
            } else {
                throw java.lang.Exception("Failed to fetch nodes")
            }
        }
        withContext(Dispatchers.Main) {
            if (SettingsManager.isVpnMode()) {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            binding.tvConnectionStatus.text = "CONNECTING..."
            binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#8892B0"))
            return
        }

        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.tvConnectionStatus.text = "CONNECTED"
            binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#00FFCC"))
            
            // Show stats layout and change selected server text
            binding.layoutStats.isVisible = true
            val currentServer = MmkvManager.getSelectServer()
            if (!currentServer.isNullOrEmpty()) {
                val serverConfig = MmkvManager.decodeServerConfig(currentServer)
                if (serverConfig != null) {
                    binding.tvSelectedServer.text = serverConfig.remarks
                }
            }
            
            startSpeedMonitoring()
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.tvConnectionStatus.text = "TAP TO CONNECT"
            binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#8892B0"))
            binding.layoutStats.isVisible = false
            
            stopSpeedMonitoring()
        }
    }

    private fun startSpeedMonitoring() {
        if (speedJob?.isActive == true) return
        
        speedJob = lifecycleScope.launch(Dispatchers.IO) {
            var lastTotal = 0L
            while (kotlinx.coroutines.isActive) {
                var proxyUplink = 0L
                var proxyDownlink = 0L
                var directUplink = 0L
                var directDownlink = 0L

                com.v2ray.ang.core.CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
                    when {
                        stat.tag == AppConfig.TAG_DIRECT -> {
                            when (stat.direction) {
                                AppConfig.UPLINK -> directUplink += stat.value
                                AppConfig.DOWNLINK -> directDownlink += stat.value
                            }
                        }
                        stat.tag.startsWith(AppConfig.TAG_PROXY) -> {
                            when (stat.direction) {
                                AppConfig.UPLINK -> proxyUplink += stat.value
                                AppConfig.DOWNLINK -> proxyDownlink += stat.value
                            }
                        }
                    }
                }

                val currentTotal = proxyUplink + proxyDownlink + directUplink + directDownlink
                val speed = if (lastTotal > 0 && currentTotal >= lastTotal) (currentTotal - lastTotal) else 0L
                lastTotal = currentTotal

                val speedStr = com.v2ray.ang.extension.toSpeedString(speed)
                val dataStr = com.v2ray.ang.extension.toTrafficString(currentTotal)

                withContext(Dispatchers.Main) {
                    binding.tvSpeed.text = speedStr
                    binding.tvTraffic.text = "Data: $dataStr"
                }

                delay(1000)
            }
        }
    }

    private fun stopSpeedMonitoring() {
        speedJob?.cancel()
        speedJob = null
        binding.tvSpeed.text = "0 KB/s"
        binding.tvTraffic.text = "Data: 0 MB"
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_maritime, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.menu_subscription -> {
            requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            true
        }
        R.id.menu_split_tunneling -> {
            requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            true
        }
        R.id.menu_settings -> {
            requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.menu_logcat -> {
            startActivity(Intent(this, LogcatActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else if (createConfigType == EConfigType.PROXYCHAIN.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerProxyChainActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
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
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            // refreshGroupTabTitles()
                        }

                        countSub > 0 -> {} // setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
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
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    // refreshGroupTabTitles()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        // refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
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
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        // refreshGroupTabTitles()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
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
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        // refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        // Obsolete UI, removed.
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        // Obsolete UI, removed.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onDestroy() {
        super.onDestroy()
    }
}