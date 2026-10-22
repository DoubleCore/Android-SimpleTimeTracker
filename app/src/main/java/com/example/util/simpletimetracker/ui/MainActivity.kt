package com.example.util.simpletimetracker.ui

import android.view.LayoutInflater
import androidx.activity.viewModels
import com.example.util.simpletimetracker.core.base.BaseActivity
import com.example.util.simpletimetracker.core.manager.ThemeManager
import com.example.util.simpletimetracker.core.provider.ContextProvider
import com.example.util.simpletimetracker.feature_views.extension.visible
import com.example.util.simpletimetracker.navigation.Router
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.example.util.simpletimetracker.databinding.MainActivityBinding as Binding

@AndroidEntryPoint
class MainActivity : BaseActivity<Binding>() {

    override val inflater: (LayoutInflater) -> Binding = Binding::inflate

    @Inject
    override lateinit var themeManager: ThemeManager

    @Inject
    override lateinit var contextProvider: ContextProvider

    @Inject
    lateinit var router: Router

    @Inject lateinit var cloudBridge: com.example.util.simpletimetracker.cloud.NativeCloudBridge
    private val cloudHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cloudStatus = object : Runnable {
        override fun run() { binding.cloudSyncStatus.text = cloudBridge.status + " · 设置"; cloudHandler.postDelayed(this, 2000) }
    }
    override fun onPause() { cloudHandler.removeCallbacks(cloudStatus); super.onPause() }
    private val viewModel: MainActivityViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        router.bind(this)
        viewModel.onVisible()
        cloudHandler.removeCallbacks(cloudStatus); cloudHandler.post(cloudStatus)
    }

    override fun initUi() {
        router.bind(this)
        router.onCreate(this)
        binding.cloudSyncStatus.setOnClickListener {
            startActivity(android.content.Intent(this, org.doublecore.stt.cloud.CloudSettingsActivity::class.java))
        }
        binding.lanDirect.setOnClickListener {
            startActivity(android.content.Intent(this, com.example.util.simpletimetracker.lan.LanHostActivity::class.java))
        }
    }

    override fun initViewModel() = with(viewModel) {
        progressVisibility.observe { binding.mainProgress.visible = it }
    }
}
