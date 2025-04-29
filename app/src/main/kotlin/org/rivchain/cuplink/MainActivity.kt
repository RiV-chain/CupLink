package org.rivchain.cuplink

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textfield.TextInputEditText
import org.rivchain.cuplink.call.CallActivity
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.NetworkUtils
import org.rivchain.cuplink.util.PowerManager
import androidx.core.view.get

class MainActivity : AddContactActivity() {

    private var currentFragmentTag: String? = null
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var viewPager: ViewPager2

    private fun initToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.apply {
            setNavigationOnClickListener {
                finish()
            }
        }
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowTitleEnabled(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(this, "onCreate()")
        // need to be called before super.onCreate()
        applyNightMode()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initToolbar()

        viewPager = findViewById(R.id.container)
        viewPager.adapter = ViewPagerFragmentAdapter(this)

        this.bottomNavigationView = findViewById(R.id.bottom_navigation)
        bottomNavigationView.setOnNavigationItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.contacts -> {
                    viewPager.currentItem = 0
                    currentFragmentTag = "0"
                }
                R.id.history -> {
                    viewPager.currentItem = 1
                    currentFragmentTag = "1"
                }
                R.id.connect -> {
                    viewPager.currentItem = 2
                    currentFragmentTag = "2"
                }
                else -> {
                    viewPager.currentItem = 0
                    currentFragmentTag = "0"
                }
            }
            true
        }
        bottomNavigationView.itemIconTintList = null
        if (savedInstanceState != null && savedInstanceState.containsKey(BUNDLE_CURRENT_FRAGMENT_TAG)) {
            // restored session
            currentFragmentTag =
                savedInstanceState.getString(BUNDLE_CURRENT_FRAGMENT_TAG, "0")

            when (currentFragmentTag) {
                FRAGMENT_TAG_CONTACTS -> {
                    viewPager.currentItem = 0
                    bottomNavigationView.post {
                        bottomNavigationView.selectedItemId = R.id.contacts
                    }
                }

                FRAGMENT_TAG_HISTORY -> {
                    viewPager.currentItem = 1
                    bottomNavigationView.post {
                        bottomNavigationView.selectedItemId = R.id.history
                    }
                }

                FRAGMENT_TAG_SHARE_CONTACT -> {
                    viewPager.currentItem = 2
                    bottomNavigationView.post {
                        bottomNavigationView.selectedItemId = R.id.connect
                    }
                }

                else -> {
                    viewPager.currentItem = 0
                    bottomNavigationView.post {
                        bottomNavigationView.selectedItemId = R.id.contacts
                    }
                }
            }
        } else {
            // new session
            currentFragmentTag = "0"
            bottomNavigationView.post {
                bottomNavigationView.selectedItemId = R.id.contacts
            }
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if(position == 2) {
                    bottomNavigationView.visibility = View.GONE
                    (findViewById<Toolbar>(R.id.toolbar).parent as View).visibility = View.GONE
                } else {
                    bottomNavigationView.visibility = View.VISIBLE
                    (findViewById<Toolbar>(R.id.toolbar).parent as View).visibility = View.VISIBLE
                }
                bottomNavigationView.menu[position].isChecked = true
                currentFragmentTag = position.toString()
            }
        })

        val settings = DatabaseCache.database.settings

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            if (settings.ignoreBatteryOptimizations && PowerManager.needsFixing(this)) {
                PowerManager.callPowerManager(this)
                PowerManager.callAutostartManager(this)
                Log.d(this, "Power management fix enabled")
            } else {
                Log.d(this, "Power management fix disabled")
            }
        } else {
            Log.d(this, "Power management fix skipped")
        }

        preferences.edit(commit = true) { putBoolean(PREF_KEY_ENABLED, true) }

        Log.d(this, "onServiceConnected()")

        // data source for the views was not ready before
        (viewPager.adapter as ViewPagerFragmentAdapter).let {
            it.ready = true
            it.notifyDataSetChanged()
        }

        val toolbarLabel = findViewById<TextView>(R.id.toolbar_label)
        if (settings.showUsernameAsLogo) {
            toolbarLabel.visibility = View.VISIBLE
            toolbarLabel.text = settings.username
        } else {
            // default
            toolbarLabel.visibility = View.GONE
        }

        if (!addressWarningShown) {
            // only show once since app start
            showInvalidAddressSettingsWarning()
            addressWarningShown = true
        }

        NotificationUtils.refreshEvents(this)
        NotificationUtils.refreshContacts(this)
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {

        if (CallActivity.isCallInProgress) {
            // Redirect to CallActivity if a call is active
            val intent = Intent(this, CallActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent)
        } else {
            if (viewPager.currentItem == 0) {
                // If already on the first page, exit the activity
                super.onBackPressedDispatcher.onBackPressed()
                finish()
            } else {
                // If not on the first page, navigate to the first page
                backToContractsPage()
            }
        }
    }

    internal fun backToContractsPage(){
        viewPager.setCurrentItem(0, true) // true to animate the transition
        bottomNavigationView.menu[0].isChecked = true // Sync BottomNavigationView
    }

    private fun isWifiConnected(): Boolean {
        val context = this as Context
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false

            return when {
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                //activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                else -> false
            }
        } else {
            val connManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI) ?: return false
            @Suppress("DEPRECATION")
            return mWifi.isConnected
        }
    }

    private fun showInvalidAddressSettingsWarning() {
        Handler(Looper.getMainLooper()).postDelayed({

            val storedAddresses = DatabaseCache.database.settings.addresses
            val storedIPAddresses = storedAddresses.filter { NetworkUtils.isIPAddress(it) || NetworkUtils.isMACAddress(it) }
            if (storedAddresses.isNotEmpty() && storedIPAddresses.isEmpty()) {
                // ignore, we only have domains configured
            } else if (storedAddresses.isEmpty()) {
                // no addresses configured at all
                Toast.makeText(this, R.string.warning_no_addresses_configured, Toast.LENGTH_LONG).show()
            } else {
                if (isWifiConnected()) {
                    val systemAddresses = NetworkUtils.collectAddresses().map { it.address }
                    if (storedIPAddresses.intersect(systemAddresses.toSet()).isEmpty()) {
                        // none of the configured addresses are used in the system
                        // addresses might have changed!
                        Toast.makeText(this, R.string.warning_no_addresses_found, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, 700)
    }

    // request password for setting activity
    private fun showMenuPasswordDialog(itemId: Int, menuPassword: String) {
        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_enter_database_password, null)
        val dialog = createBlurredPPTCDialog(view)
        dialog.setCanceledOnTouchOutside(false)
        val passwordEditText = view.findViewById<TextInputEditText>(R.id.passwordEditTextView)
        val okButton = view.findViewById<Button>(R.id.okButton)
        okButton.setOnClickListener {
            val password = passwordEditText.text.toString()
            if (menuPassword == password) {
                // start menu action
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                passwordEditText.error = getString(R.string.wrong_password)
                Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show()
            }
            // close dialog
            dialog.dismiss()
        }
        dialog.show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Log.d(this, "onOptionsItemSelected()")

        val settings = DatabaseCache.database.settings
        if (settings.menuPassword.isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            showMenuPasswordDialog(item.itemId, settings.menuPassword)
        }

        return super.onOptionsItemSelected(item)
    }

    fun updateEventTabTitle() {
        Log.d(this, "updateEventTabTitle()")
        // update event tab title
        (viewPager.adapter as ViewPagerFragmentAdapter?)?.notifyDataSetChanged()
    }

    override fun onResume() {
        Log.d(this, "onResume()")
        super.onResume()

        updateEventTabTitle()
    }

    //@SuppressLint("RestrictedApi")
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Log.d(this, "onCreateOptionsMenu()")
        menuInflater.inflate(R.menu.menu_settings, menu)
        val item = menu.findItem(R.id.action_settings) as MenuItem
        item.setActionView(R.layout.menu_settings_layout)
        val settingsButton = item
            .actionView?.findViewById<ImageButton>(R.id.settingsButton)
        settingsButton?.setOnClickListener {
            val settings = DatabaseCache.database.settings
            if (settings.menuPassword.isEmpty()) {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                showMenuPasswordDialog(item.itemId, settings.menuPassword)
            }
        }
        /*
        Not needed anymore since RCL-183 was implemented
         */
        /*
        if (menu is MenuBuilder) {
            menu.setOptionalIconsVisible(true)
        }
         */
        return true
    }

    class ViewPagerFragmentAdapter(private val fm: MainActivity) : FragmentStateAdapter(fm) {
        var ready = false

        override fun getItemCount(): Int {
            return if (ready) 3 else 0
        }

        override fun createFragment(position: Int): Fragment {

            val fragment = when (position) {
                0 -> ContactListFragment()
                1 -> EventListFragment()
                2 -> {
                    val fragment = ConnectFragment()
                    val bundle = Bundle()
                    bundle.putByteArray("EXTRA_CONTACT_PUBLICKEY", DatabaseCache.database.settings.publicKey)
                    fragment.arguments = bundle
                    fragment
                }
                else -> ContactListFragment()
            }
            return fragment
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeeplinkIntent(intent)
    }

    private fun handleDeeplinkIntent(intent: Intent) {
        val data = intent.data
        if (data != null) {
            AddContactActivity.handlePotentialCupLinkContactUrl(this, data.toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        try {
            super.onSaveInstanceState(outState)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (currentFragmentTag != null) {
            outState.putString(BUNDLE_CURRENT_FRAGMENT_TAG, currentFragmentTag)
        }
    }

    companion object {

        const val FRAGMENT_TAG_CONTACTS: String = "0"
        const val FRAGMENT_TAG_HISTORY: String = "1"
        const val FRAGMENT_TAG_SHARE_CONTACT: String = "2"

        const val BUNDLE_CURRENT_FRAGMENT_TAG: String = "currentFragmentTag"

        private var addressWarningShown = false

        fun clearTop(context: Context): Intent {
            val intent = Intent(context, MainActivity::class.java)

            intent.setFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

            return intent
        }
    }
}
