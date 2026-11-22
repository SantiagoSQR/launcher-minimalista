package com.valen.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val model by viewModels<LauncherModel>()
    private var homeRequest by mutableIntStateOf(0)
    private val roles by lazy { getSystemService(RoleManager::class.java) }
    private var isDefaultHome by mutableStateOf(false)
    private val requestHome = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshHomeRole()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = transparent, navigationBarStyle = transparent)
        setContent { LauncherScreen(model, isDefaultHome, homeRequest, ::chooseHome) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeRequest++
    }

    override fun onResume() {
        super.onResume()
        refreshHomeRole()
        model.refresh()
    }

    override fun onStop() {
        model.cancelInterruption()
        homeRequest++
        super.onStop()
    }

    private fun refreshHomeRole() {
        isDefaultHome = roles.isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun chooseHome() {
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
            requestHome.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }
}
