package android.kimyona.jammer.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import android.kimyona.jammer.R
import android.kimyona.jammer.ui.fragments.LibraryFragment
import android.kimyona.jammer.ui.fragments.PlayerFragment
import android.kimyona.jammer.ui.fragments.QueueFragment
import android.kimyona.jammer.ui.popup.HtmlPopupActivity
import android.kimyona.jammer.ui.viewmodel.PlayerViewModel

/**
 * MainActivity com ViewPager2 + TabLayout.
 * Tabs: Library | Player | Queue
 * Menu: Tutorial | FAQ | Sincronizar | Configurações
 * MVP FIX: menu_sync agora dispara scanLibrary() de verdade.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 3
            override fun createFragment(position: Int): Fragment =
                when (position) {
                    0 -> LibraryFragment()
                    1 -> PlayerFragment()
                    2 -> QueueFragment()
                    else -> LibraryFragment()
                }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Library"
                1 -> "Player"
                2 -> "Queue"
                else -> ""
            }
        }.attach()

        // Começa na aba Player (índice 1)
        viewPager.currentItem = 1
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_tutorial -> {
                openHtmlPopup("Tutorial", HtmlPopupActivity.ASSET_TUTORIAL)
                true
            }
            R.id.menu_faq -> {
                openHtmlPopup("FAQ", HtmlPopupActivity.ASSET_FAQ)
                true
            }
            // MVP FIX: sincronizar agora funciona
            R.id.menu_sync -> {
                val vm = ViewModelProvider(this).get(PlayerViewModel::class.java)
                vm.scanLibrary()
                Toast.makeText(this, "Sincronizando biblioteca...", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.menu_settings -> {
                Toast.makeText(this, "Configurações em breve!", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openHtmlPopup(title: String, asset: String) {
        val intent = Intent(this, HtmlPopupActivity::class.java).apply {
            putExtra(HtmlPopupActivity.EXTRA_TITLE, title)
            putExtra(HtmlPopupActivity.EXTRA_HTML_ASSET, asset)
        }
        startActivity(intent)
    }
}
