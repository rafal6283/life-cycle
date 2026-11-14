package pl.rafal.lifecycle.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Blokada odciskiem palca / twarzą albo kodem ekranu blokady telefonu. */
object AppLock {
    private val authenticators = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

    fun available(a: android.content.Context): Boolean {
        val bm = BiometricManager.from(a)
        if (Build.VERSION.SDK_INT >= 30) return bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        val km = a.getSystemService(android.app.KeyguardManager::class.java)
        return bm.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS || km?.isDeviceSecure == true
    }

    fun prompt(activity: FragmentActivity, onOk: () -> Unit) {
        val p = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Doba")
            .setSubtitle("Odblokuj, aby zobaczyć swoją historię")
            .apply {
                if (Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(authenticators)
                else @Suppress("DEPRECATION") setDeviceCredentialAllowed(true)
            }
            .build()
        try {
            p.authenticate(info)
        } catch (_: Exception) {
            onOk() // brak zabezpieczeń w telefonie — nie blokujemy na stałe
        }
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🔒", fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("Doba jest zablokowana", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onUnlock) { Text("Odblokuj") }
    }
}
