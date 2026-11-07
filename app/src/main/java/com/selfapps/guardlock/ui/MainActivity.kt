package com.selfapps.guardlock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfapps.guardlock.data.Role

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) { Root() }
            }
        }
    }
}

@Composable
private fun Root(vm: MainViewModel = viewModel()) {
    when {
        vm.role == null -> RoleSelectScreen(vm)
        vm.role == Role.GUARDIAN -> GuardianScreen(vm)
        vm.pairId == null -> JoinScreen(vm)
        else -> GuardedScreen(vm)
    }
}
