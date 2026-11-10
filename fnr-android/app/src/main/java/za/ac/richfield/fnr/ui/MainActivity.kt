package za.ac.richfield.fnr.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.view.MenuCompat
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import za.ac.richfield.fnr.FnrApp
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.audio.RecPhase
import za.ac.richfield.fnr.audio.Recorder
import za.ac.richfield.fnr.databinding.ActivityMainBinding

/**
 * Single activity that hosts every screen through one navigation graph (Form Links).
 * Student screens get a bottom navigation bar; the overflow menu holds Recordings,
 * Backup, Profile and Log out.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var nav: NavController
    private val container get() = (application as FnrApp).container

    private val topLevel = setOf(
        R.id.splashFragment, R.id.loginFragment, R.id.dashboardFragment, R.id.recordFragment,
        R.id.subjectsFragment, R.id.reportsMenuFragment, R.id.adminDashboardFragment, R.id.purposeFragment,
    )
    private val withBottomBar = setOf(
        R.id.dashboardFragment, R.id.recordFragment, R.id.subjectsFragment, R.id.reportsMenuFragment,
    )
    private val signedOut = setOf(R.id.splashFragment, R.id.loginFragment, R.id.registerFragment, R.id.purposeFragment)
    private lateinit var appBarConfig: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        nav = (supportFragmentManager.findFragmentById(R.id.navHost) as NavHostFragment).navController
        appBarConfig = AppBarConfiguration(topLevel)
        setupActionBarWithNavController(nav, appBarConfig)
        binding.bottomNav.setupWithNavController(nav)

        nav.addOnDestinationChangedListener { _, dest, _ ->
            binding.bottomNav.isVisible = dest.id in withBottomBar
            binding.toolbar.isVisible = dest.id != R.id.splashFragment
            invalidateOptionsMenu()
        }

        addMenuProvider(object : MenuProvider {
            @SuppressLint("RestrictedApi")
            override fun onCreateMenu(menu: Menu, inflater: android.view.MenuInflater) {
                inflater.inflate(R.menu.main_menu, menu)
                // Show each item's icon in the overflow menu and a divider above Log out
                (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
                MenuCompat.setGroupDividerEnabled(menu, true)
            }

            override fun onPrepareMenu(menu: Menu) {
                val current = nav.currentDestination?.id
                val loggedIn = container.session.isLoggedIn && current !in signedOut
                val student = loggedIn && !container.session.isAdmin
                menu.findItem(R.id.menuRecordings).isVisible = loggedIn
                menu.findItem(R.id.menuBackup).isVisible = loggedIn
                menu.findItem(R.id.menuProfile).isVisible = loggedIn
                menu.findItem(R.id.menuAdmin).isVisible = loggedIn && !student
                menu.findItem(R.id.menuLogout).isVisible = loggedIn
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean {
                when (item.itemId) {
                    R.id.menuRecordings -> nav.navigate(R.id.recordingsFragment)
                    R.id.menuBackup -> nav.navigate(R.id.backupFragment)
                    R.id.menuProfile -> nav.navigate(R.id.profileFragment)
                    R.id.menuAdmin -> nav.navigate(R.id.adminDashboardFragment)
                    R.id.menuLogout -> logout(null)
                    else -> return false
                }
                return true
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean = nav.navigateUp(appBarConfig) || super.onSupportNavigateUp()

    override fun onUserInteraction() {
        super.onUserInteraction()
        container.session.touch()
    }

    override fun onResume() {
        super.onResume()
        // 15 minutes without interaction ends the session (FR-USR-08),
        // except while a recording is running (a lecture can be longer than that).
        val recording = Recorder.state.value.phase.let { it == RecPhase.RECORDING || it == RecPhase.PAUSED }
        if (container.session.isExpired && !recording) {
            logout("You were logged out after 15 minutes of inactivity.")
        } else {
            container.session.touch()
        }
    }

    fun logout(message: String?) {
        container.users.logout()
        nav.navigate(R.id.loginFragment, null, NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build())
        message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }
}
