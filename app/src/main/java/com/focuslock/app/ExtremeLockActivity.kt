package com.focuslock.app

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Tela do modo EXTREME. Mantém o foreground dispatch de NFC (o overlay não consegue ler tags)
 * e oferece o mesmo painel (Câmera, Telefone, senha forte) caso o overlay não esteja disponível.
 */
class ExtremeLockActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var panel: ExtremePanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        val prefs = (application as FocusLockApp).prefs
        if (!prefs.isExtremeActive) {
            finish()
            return
        }

        setShowWhenLockedCompat()
        panel = ExtremePanel(
            context = this,
            prefs = prefs,
            onUnlocked = { finish() }
        )
        setContentView(panel!!.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* bloqueado no modo EXTREME */ }
        })

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        handleNfcIntent(intent)
    }

    private fun setShowWhenLockedCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = (application as FocusLockApp).prefs
        if (!prefs.isExtremeActive) {
            finish()
            return
        }
        isResumed = true
        panel?.refreshLockoutState()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        panel?.release()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return

        val uid = tag.id.joinToString(":") { "%02X".format(it) }
        if (!ExtremeMode.handleTag(this, uid)) {
            android.widget.Toast.makeText(
                this,
                "Tag NFC não é a Tag EXTREME. Só ela libera este modo.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        @Volatile
        var isResumed: Boolean = false
            private set

        @Volatile
        private var instance: ExtremeLockActivity? = null

        fun finishIfShowing() {
            instance?.runOnUiThread { instance?.finish() }
        }
    }
}
