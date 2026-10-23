package dev.local.qrspotify

import android.content.Context
import org.json.JSONArray
import java.util.regex.Pattern

data class QuickGameSong(
    val position: Int,
    val title: String,
    val artist: String,
    val trackId: String,
    val year: Int
)

object QuickGamePlaylist {

    private val trackIdPattern: Pattern = Pattern.compile("track/([a-zA-Z0-9]{22})")

    fun loadFromAssets(context: Context, fileName: String): List<QuickGameSong> {
        val json = context.assets.open(fileName).bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        val songs = mutableListOf<QuickGameSong>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val url = obj.optString("spotify_url")
            val matcher = trackIdPattern.matcher(url)
            if (!matcher.find()) continue

            songs.add(
                QuickGameSong(
                    position = obj.optInt("posicao"),
                    title = obj.optString("musica"),
                    artist = obj.optString("artista"),
                    trackId = matcher.group(1)!!,
                    year = obj.optInt("ano")
                )
            )
        }
        return songs
    }
}
