(function () {
  'use strict';

  const Screen = { START: 'start', SCANNER: 'scanner', NOW_PLAYING: 'nowPlaying' };
  const Mode = { QR: 'qr', QUICK_GAME: 'quickGame' };

  // Matches spotify:track:ID URIs and open.spotify.com/.../track/ID links,
  // including locale-prefixed paths like /intl-pt/track/...
  const TRACK_ID_REGEX = /(?:spotify:track:|spotify\.com\/(?:[\w-]+\/)?track\/)([a-zA-Z0-9]{22})/;
  const PLAYLIST_TRACK_ID_REGEX = /track\/([a-zA-Z0-9]{22})/;

  const el = {
    startScreen: document.getElementById('startScreen'),
    scannerScreen: document.getElementById('scannerScreen'),
    nowPlayingScreen: document.getElementById('nowPlayingScreen'),
    video: document.getElementById('video'),
    overlayCanvas: document.getElementById('overlayCanvas'),
    revealedInfo: document.getElementById('revealedInfo'),
    revealButton: document.getElementById('revealButton'),
    nextCardButton: document.getElementById('nextCardButton'),
    openSpotifyButton: document.getElementById('openSpotifyButton'),
    closeScannerButton: document.getElementById('closeScannerButton'),
    closeNowPlayingButton: document.getElementById('closeNowPlayingButton'),
    loginButton: document.getElementById('loginButton'),
    startLoginButton: document.getElementById('startLoginButton'),
    startButton: document.getElementById('startButton'),
    quickGameButton: document.getElementById('quickGameButton'),
    playerContainer: document.getElementById('playerContainer'),
  };

  let currentScreen = Screen.START;
  let currentMode = Mode.QR;
  let currentTrackId = null;
  let currentQuickGameSong = null;
  let isScanningPaused = false;

  let mediaStream = null;
  let scanLoopHandle = null;

  let spotifyIframeApi = null;
  let embedController = null;
  let pendingTrackId = null;

  let quickGamePlaylist = [];
  const playedPositions = new Set();

  function showScreen(screen) {
    currentScreen = screen;
    el.startScreen.classList.toggle('hidden', screen !== Screen.START);
    el.scannerScreen.classList.toggle('hidden', screen !== Screen.SCANNER);
    el.nowPlayingScreen.classList.toggle('hidden', screen !== Screen.NOW_PLAYING);
  }

  // --- Camera / QR scanning --------------------------------------------

  async function startScanner() {
    currentMode = Mode.QR;
    isScanningPaused = false;
    showScreen(Screen.SCANNER);

    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' } },
        audio: false,
      });
      el.video.srcObject = mediaStream;
      await el.video.play();
      resizeOverlay();
      scanLoopHandle = requestAnimationFrame(scanFrame);
    } catch (err) {
      alert('Não foi possível acessar a câmera: ' + err.message);
      showScreen(Screen.START);
    }
  }

  function stopScanner() {
    if (scanLoopHandle) {
      cancelAnimationFrame(scanLoopHandle);
      scanLoopHandle = null;
    }
    if (mediaStream) {
      mediaStream.getTracks().forEach((track) => track.stop());
      mediaStream = null;
    }
  }

  const sampleCanvas = document.createElement('canvas');
  const sampleCtx = sampleCanvas.getContext('2d', { willReadFrequently: true });

  function scanFrame() {
    try {
      if (
        !isScanningPaused &&
        typeof window.jsQR === 'function' &&
        el.video.readyState === el.video.HAVE_ENOUGH_DATA &&
        el.video.videoWidth > 0
      ) {
        sampleCanvas.width = el.video.videoWidth;
        sampleCanvas.height = el.video.videoHeight;
        sampleCtx.drawImage(el.video, 0, 0, sampleCanvas.width, sampleCanvas.height);

        const imageData = sampleCtx.getImageData(0, 0, sampleCanvas.width, sampleCanvas.height);
        const code = window.jsQR(imageData.data, imageData.width, imageData.height, {
          inversionAttempts: 'dontInvert',
        });

        if (code && code.data) {
          handleScannedText(code.data);
        }
      }
    } catch (err) {
      console.error('QR scan frame failed', err);
    }
    scanLoopHandle = requestAnimationFrame(scanFrame);
  }

  function handleScannedText(text) {
    if (isScanningPaused) return;
    const match = TRACK_ID_REGEX.exec(text);
    if (match) {
      isScanningPaused = true;
      playTrack(match[1]);
    }
  }

  // --- Scanner mask overlay ---------------------------------------------

  function resizeOverlay() {
    const canvas = el.overlayCanvas;
    canvas.width = el.scannerScreen.clientWidth;
    canvas.height = el.scannerScreen.clientHeight;
    drawMask();
  }

  function drawMask() {
    const canvas = el.overlayCanvas;
    const ctx = canvas.getContext('2d');
    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const boxSize = Math.min(w, h) * 0.72;
    const left = (w - boxSize) / 2;
    const top = (h - boxSize) / 2 - h * 0.06;
    const radius = 24;

    ctx.save();
    ctx.fillStyle = 'rgba(0, 0, 0, 0.7)';
    ctx.fillRect(0, 0, w, h);
    ctx.globalCompositeOperation = 'destination-out';
    roundRectPath(ctx, left, top, boxSize, boxSize, radius);
    ctx.fill();
    ctx.restore();

    ctx.strokeStyle = '#1DB954';
    ctx.lineWidth = 6;
    ctx.lineCap = 'round';
    const cornerLen = Math.min(boxSize * 0.18, 42);
    drawCorner(ctx, left, top, 1, 1, cornerLen);
    drawCorner(ctx, left + boxSize, top, -1, 1, cornerLen);
    drawCorner(ctx, left, top + boxSize, 1, -1, cornerLen);
    drawCorner(ctx, left + boxSize, top + boxSize, -1, -1, cornerLen);
  }

  function drawCorner(ctx, x, y, dirX, dirY, len) {
    ctx.beginPath();
    ctx.moveTo(x, y + len * dirY);
    ctx.lineTo(x, y);
    ctx.lineTo(x + len * dirX, y);
    ctx.stroke();
  }

  function roundRectPath(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  window.addEventListener('resize', () => {
    if (currentScreen === Screen.SCANNER) resizeOverlay();
  });

  // --- Spotify embed playback (hidden, title/artist/art never shown) ----

  window.onSpotifyIframeApiReady = (IFrameAPI) => {
    spotifyIframeApi = IFrameAPI;
    if (pendingTrackId) {
      const trackId = pendingTrackId;
      pendingTrackId = null;
      loadTrack(trackId);
    }
  };

  function loadTrack(trackId) {
    if (!spotifyIframeApi) {
      pendingTrackId = trackId;
      return;
    }
    if (!embedController) {
      const options = { width: '300', height: '80', uri: 'spotify:track:' + trackId };
      spotifyIframeApi.createController(el.playerContainer, options, (controller) => {
        embedController = controller;
        controller.addListener('ready', () => controller.play());
      });
    } else {
      embedController.loadUri('spotify:track:' + trackId);
      embedController.play();
    }
  }

  function stopPlayback() {
    if (embedController) {
      try { embedController.pause(); } catch (e) { /* no-op */ }
    }
  }

  // --- QR mode: play a scanned track -------------------------------------

  function playTrack(trackId) {
    stopScanner();
    currentTrackId = trackId;
    currentQuickGameSong = null;
    el.revealButton.classList.add('hidden');
    el.revealedInfo.classList.add('hidden');
    el.revealedInfo.textContent = '';
    loadTrack(trackId);
    showScreen(Screen.NOW_PLAYING);
  }

  // --- Quick game mode ----------------------------------------------------

  async function startQuickGame() {
    currentMode = Mode.QUICK_GAME;
    if (quickGamePlaylist.length === 0) {
      try {
        const response = await fetch('hitster_playlist_anos.json');
        const data = await response.json();
        quickGamePlaylist = data
          .map((item) => {
            const match = PLAYLIST_TRACK_ID_REGEX.exec(item.spotify_url || '');
            if (!match) return null;
            return {
              title: item.musica,
              artist: item.artista,
              year: item.ano,
              trackId: match[1],
            };
          })
          .filter(Boolean);
      } catch (err) {
        alert('Não foi possível carregar a playlist.');
        return;
      }
      if (quickGamePlaylist.length === 0) {
        alert('Não foi possível carregar a playlist.');
        return;
      }
    }
    playNextQuickGameSong();
  }

  function playNextQuickGameSong() {
    if (quickGamePlaylist.length === 0) return;

    if (playedPositions.size >= quickGamePlaylist.length) {
      playedPositions.clear();
      alert('Todas as músicas já tocaram! Reiniciando o ciclo.');
    }

    let index;
    do {
      index = Math.floor(Math.random() * quickGamePlaylist.length);
    } while (playedPositions.has(index));
    playedPositions.add(index);

    const song = quickGamePlaylist[index];
    currentQuickGameSong = song;
    currentTrackId = song.trackId;

    el.revealButton.classList.remove('hidden');
    el.revealedInfo.classList.add('hidden');
    el.revealedInfo.textContent = '';

    loadTrack(song.trackId);
    showScreen(Screen.NOW_PLAYING);
  }

  function revealCurrentSong() {
    const song = currentQuickGameSong;
    if (!song) return;
    el.revealedInfo.textContent = `${song.title} • ${song.artist} (${song.year})`;
    el.revealedInfo.classList.remove('hidden');
    el.revealButton.classList.add('hidden');
  }

  // --- Navigation ----------------------------------------------------------

  function stopPlaybackAndGoHome() {
    stopPlayback();
    currentQuickGameSong = null;
    currentTrackId = null;
    showScreen(Screen.START);
  }

  function returnToScanner() {
    stopPlayback();
    startScanner();
  }

  function openInSpotify() {
    if (!currentTrackId) return;
    window.open('https://open.spotify.com/track/' + currentTrackId, '_blank', 'noopener');
  }

  function openSpotifyLogin() {
    window.open('https://accounts.spotify.com/login', '_blank', 'noopener');
  }

  // --- Wiring ----------------------------------------------------------

  el.startButton.addEventListener('click', startScanner);
  el.quickGameButton.addEventListener('click', startQuickGame);
  el.startLoginButton.addEventListener('click', openSpotifyLogin);
  el.loginButton.addEventListener('click', openSpotifyLogin);
  el.closeScannerButton.addEventListener('click', () => {
    stopScanner();
    showScreen(Screen.START);
  });
  el.closeNowPlayingButton.addEventListener('click', stopPlaybackAndGoHome);
  el.nextCardButton.addEventListener('click', () => {
    if (currentMode === Mode.QUICK_GAME) {
      playNextQuickGameSong();
    } else {
      returnToScanner();
    }
  });
  el.openSpotifyButton.addEventListener('click', openInSpotify);
  el.revealButton.addEventListener('click', revealCurrentSong);

  showScreen(Screen.START);
})();
