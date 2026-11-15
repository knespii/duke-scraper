package cz.duke390.hlidac

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import cz.duke390.hlidac.ui.DukeTheme
import cz.duke390.hlidac.ui.MainScreen
import cz.duke390.hlidac.ui.MainViewModel
import cz.duke390.hlidac.work.RefreshWorker

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RefreshWorker.schedulePeriodic(this)
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { DukeTheme { MainScreen(vm) } }
    }
}
