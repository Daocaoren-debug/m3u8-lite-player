package com.m3u8.liteplayer

import android.net.Uri

data class MediaFile(
    val name: String,
    val path: String,
    val size: Long,
    val uri: Uri,
    val lastModified: Long = 0L
)
