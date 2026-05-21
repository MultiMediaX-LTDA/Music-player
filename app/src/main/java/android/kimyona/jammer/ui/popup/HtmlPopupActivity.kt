package android.kimyona.jammer.ui.popup

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.kimyona.jammer.R

/**
 * Activity que mostra conteúdo HTML offline.
 * Usada pra tutoriais, FAQs, changelog, etc.
 * Recebe o título e o nome do asset HTML via intent extras.
 */
class HtmlPopupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_HTML_ASSET = "html_asset"

        // Assets pré-definidos
        const val ASSET_TUTORIAL = "html/tutorial.html"
        const val ASSET_FAQ = "html/faq.html"
        const val ASSET_CHANGELOG = "html/changelog.html"
    }

    private lateinit var webView: WebView
    private lateinit var btnClose: ImageButton
    private lateinit var tvTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_html_popup)

        webView = findViewById(R.id.webView)
        btnClose = findViewById(R.id.btnClose)
        tvTitle = findViewById(R.id.tvPopupTitle)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Ajuda"
        val htmlAsset = intent.getStringExtra(EXTRA_HTML_ASSET) ?: ASSET_TUTORIAL

        tvTitle.text = title

        // Configura WebView
        webView.settings.apply {
            javaScriptEnabled = false // Segurança: não precisa de JS pra conteúdo estático
            allowFileAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        // Carrega HTML do assets/
        webView.loadUrl("file:///android_asset/$htmlAsset")

        btnClose.setOnClickListener {
            finish()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
