package com.example.parser

data class SampleMediaSource(
    val label: String,
    val description: String,
    val url: String,
    val defaultFormat: String
)

object SamplePlaylists {
    val samples = listOf(
        SampleMediaSource(
            label = "Open Classical Audio Suite",
            description = "Public domain Beethoven & Mozart M3U playlist",
            url = "https://raw.githubusercontent.com/alextunyk/test-multimedia-files/master/audio/playlist.m3u",
            defaultFormat = "MP3"
        ),
        SampleMediaSource(
            label = "LibriVox Short Stories",
            description = "Public domain open audio podcast RSS feed",
            url = "https://librivox.org/rss/14068",
            defaultFormat = "MP3"
        ),
        SampleMediaSource(
            label = "Open Video Archive (MP4)",
            description = "Creative Commons sample video clips",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            defaultFormat = "MP4"
        ),
        SampleMediaSource(
            label = "CC Nature Soundscapes",
            description = "Direct high quality nature soundscapes (MP3)",
            url = "https://actions.google.com/sounds/v1/weather/thunder_crack.ogg",
            defaultFormat = "MP3"
        )
    )
}
