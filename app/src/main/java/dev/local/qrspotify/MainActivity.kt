package dev.local.qrspotify

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.local.qrspotify.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private enum class Screen { START, SCANNER, NOW_PLAYING, LOGIN }
    private enum class Mode { QR, QUICK_GAME }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    private var currentScreen = Screen.START
    private var screenBeforeLogin = Screen.START
    private var currentMode = Mode.QR
    private var currentTrackId: String? = null
    private var isScanningPaused = false
    private val eqAnimators = mutableListOf<ObjectAnimator>()

    private var quickGamePlaylist: List<QuickGameSong> = emptyList()
    private val playedSongPositions = mutableSetOf<Int>()
    private var currentQuickGameSong: QuickGameSong? = null

    // Matches spotify:track:ID URIs and open.spotify.com/.../track/ID links,
    // including locale-prefixed paths like /intl-pt/track/...
    private val spotifyTrackPattern: Pattern = Pattern.compile(
        "(?:spotify:track:|spotify\\.com/(?:[\\w-]+/)?track/)([a-zA-Z0-9]{22})"
    )

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupWebViews()
        setupClickListeners()
        setupBackHandling()

        showScreen(Screen.START)
    }

    private fun setupClickListeners() {
        binding.startButton.setOnClickListener { requestCameraAndShowScanner() }
        binding.quickGameButton.setOnClickListener { startQuickGame() }
        binding.startLoginButton.setOnClickListener { openSpotifyLogin() }
        binding.closeScannerButton.setOnClickListener { showScreen(Screen.START) }
        binding.loginButton.setOnClickListener { openSpotifyLogin() }
        binding.closeLoginButton.setOnClickListener { showScreen(screenBeforeLogin) }
        binding.closeNowPlayingButton.setOnClickListener { stopPlaybackAndGoHome() }
        binding.nextCardButton.setOnClickListener {
            when (currentMode) {
                Mode.QR -> returnToScanner()
                Mode.QUICK_GAME -> playNextQuickGameSong()
            }
        }
        binding.openSpotifyButton.setOnClickListener { openInSpotifyApp() }
        binding.revealButton.setOnClickListener { revealCurrentSong() }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    Screen.LOGIN ->
                        if (binding.loginWebView.canGoBack()) binding.loginWebView.goBack()
                        else showScreen(screenBeforeLogin)
                    Screen.NOW_PLAYING ->
                        if (currentMode == Mode.QUICK_GAME) stopPlaybackAndGoHome() else returnToScanner()
                    Screen.SCANNER -> showScreen(Screen.START)
                    Screen.START -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun requestCameraAndShowScanner() {
        currentMode = Mode.QR
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            isScanningPaused = false
            showScreen(Screen.SCANNER)
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // --- Quick game mode -----------------------------------------------------

    private fun startQuickGame() {
        currentMode = Mode.QUICK_GAME
        if (quickGamePlaylist.isNotEmpty()) {
            playNextQuickGameSong()
            return
        }
        Thread {
            val songs = try {
                QuickGamePlaylist.loadFromAssets(this, "hitster_playlist_anos.json")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load quick game playlist", e)
                emptyList()
            }
            runOnUiThread {
                if (songs.isEmpty()) {
                    Toast.makeText(this, R.string.playlist_load_error, Toast.LENGTH_LONG).show()
                } else {
                    quickGamePlaylist = songs
                    playNextQuickGameSong()
                }
            }
        }.start()
    }

    private fun playNextQuickGameSong() {
        val playlist = quickGamePlaylist
        if (playlist.isEmpty()) return

        if (playedSongPositions.size >= playlist.size) {
            playedSongPositions.clear()
            Toast.makeText(this, R.string.playlist_cycle_restart, Toast.LENGTH_SHORT).show()
        }

        var index: Int
        do {
            index = Random.nextInt(playlist.size)
        } while (index in playedSongPositions)
        playedSongPositions.add(index)

        val song = playlist[index]
        currentQuickGameSong = song
        currentTrackId = song.trackId

        binding.revealButton.visibility = View.VISIBLE
        binding.revealedInfo.visibility = View.GONE

        val url = "file:///android_asset/spotify_player.html?trackId=${song.trackId}&autoPlay=1"
        binding.playerWebView.loadUrl(url)
        showScreen(Screen.NOW_PLAYING)
    }

    private fun revealCurrentSong() {
        val song = currentQuickGameSong ?: return
        binding.revealedInfo.text = getString(R.string.reveal_format, song.title, song.artist, song.year)
        binding.revealedInfo.visibility = View.VISIBLE
        binding.revealButton.visibility = View.GONE
    }

    private fun stopPlaybackAndGoHome() {
        currentQuickGameSong = null
        currentTrackId = null
        binding.playerWebView.stopLoading()
        binding.playerWebView.loadUrl("about:blank")
        showScreen(Screen.START)
    }

    private fun setupWebViews() {
        for (webView in listOf(binding.playerWebView, binding.loginWebView)) {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = false
        }
        binding.playerWebView.addJavascriptInterface(SpotifyPlayerBridge(), "AndroidBridge")

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.playerWebView, true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.loginWebView, true)
    }

    // --- Screen management -------------------------------------------------

    private fun showScreen(screen: Screen) {
        if (screen == Screen.LOGIN) {
            screenBeforeLogin = currentScreen
        }
        currentScreen = screen

        binding.startContainer.visibility = visibleIf(screen == Screen.START)
        binding.scannerContainer.visibility = visibleIf(screen == Screen.SCANNER)
        binding.nowPlayingContainer.visibility = visibleIf(screen == Screen.NOW_PLAYING)
        binding.loginContainer.visibility = visibleIf(screen == Screen.LOGIN)

        if (screen == Screen.NOW_PLAYING) startEqualizerAnimation() else stopEqualizerAnimation()
    }

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun returnToScanner() {
        isScanningPaused = false
        currentTrackId = null
        binding.playerWebView.stopLoading()
        binding.playerWebView.loadUrl("about:blank")
        showScreen(Screen.SCANNER)
    }

    private fun openSpotifyLogin() {
        binding.loginWebView.loadUrl("https://accounts.spotify.com/en/login")
        showScreen(Screen.LOGIN)
    }

    private fun openInSpotifyApp() {
        val trackId = currentTrackId ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:track:$trackId")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$trackId")))
        }
    }

    // --- Camera / QR scanning ----------------------------------------------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }

        val scannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(scannerOptions)

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(scanner, imageProxy)
                }
            }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }

    private fun processImageProxy(
        scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
        imageProxy: ImageProxy
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || isScanningPaused) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue ?: continue
                    handleScannedText(rawValue)
                }
            }
            .addOnFailureListener { e -> Log.e(TAG, "Barcode scan failed", e) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun handleScannedText(text: String) {
        if (isScanningPaused) return

        val matcher = spotifyTrackPattern.matcher(text)
        if (matcher.find()) {
            val trackId = matcher.group(1)!!
            isScanningPaused = true
            runOnUiThread { playTrack(trackId) }
        } else {
            runOnUiThread {
                Toast.makeText(this, R.string.invalid_qr, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playTrack(trackId: String) {
        currentTrackId = trackId
        currentQuickGameSong = null
        binding.revealButton.visibility = View.GONE
        binding.revealedInfo.visibility = View.GONE

        val url = "file:///android_asset/spotify_player.html?trackId=$trackId&autoPlay=1"
        binding.playerWebView.loadUrl(url)
        showScreen(Screen.NOW_PLAYING)
    }

    // --- "Now playing" equalizer animation ----------------------------------

    private fun startEqualizerAnimation() {
        if (eqAnimators.isNotEmpty()) return
        val bars = listOf(binding.eqBar1, binding.eqBar2, binding.eqBar3, binding.eqBar4)
        val durations = listOf(420L, 560L, 480L, 640L)
        bars.forEachIndexed { index, bar ->
            bar.post {
                bar.pivotY = bar.height.toFloat()
                val animator = ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.35f, 1f).apply {
                    duration = durations[index]
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    startDelay = index * 90L
                }
                animator.start()
                eqAnimators.add(animator)
            }
        }
    }

    private fun stopEqualizerAnimation() {
        eqAnimators.forEach { it.cancel() }
        eqAnimators.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private inner class SpotifyPlayerBridge {
        @JavascriptInterface
        fun onPlayerReady() {
            Log.d(TAG, "Spotify embed player ready")
        }

        @JavascriptInterface
        fun onPlaybackUpdate(isPaused: Boolean, position: Int, duration: Int) {
            Log.d(TAG, "Playback update: paused=$isPaused pos=$position dur=$duration")
        }

        @JavascriptInterface
        fun onPlayerError(code: String) {
            Log.e(TAG, "Spotify embed player error: $code")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Erro ao tocar: $code", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun onPlayerTimeout() {
            Log.w(TAG, "Spotify embed player timed out loading")
        }
    }

    companion object {
        private const val TAG = "QRSpotifyPlayer"
    }
}
