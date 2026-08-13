package com.example.meshsocial

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meshsocial.ui.MainViewModel
import com.example.meshsocial.ui.NearFeedScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(application))
                    NearFeedScreen(vm)
                }
            }
        }
    }
}
