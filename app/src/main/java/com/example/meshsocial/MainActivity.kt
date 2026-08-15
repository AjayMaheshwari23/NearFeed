package com.example.meshsocial

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meshsocial.ui.MainViewModel
import com.example.meshsocial.ui.NearFeedScreen
import com.example.meshsocial.ui.theme.NearFeedTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NearFeedTheme {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(application))
                    NearFeedScreen(vm)
                }
            }
        }
    }
}
