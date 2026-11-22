package com.valen.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
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
        setContent {
            Box(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                contentAlignment = Alignment.Center
            ) {
                if (!isDefaultHome) {
                    BasicText(
                        text = stringResource(R.string.choose_home),
                        modifier = Modifier.clickable(role = Role.Button) { chooseHome() }
                            .padding(16.dp),
                        style = TextStyle(color = Color.White, fontSize = 16.sp)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHomeRole()
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
