package com.example.zenscan.model

data class ExifMetadata(
    val make: String?,
    val model: String?,
    val dateTime: String?,
    val latitude: Double?,
    val longitude: Double?,
    val width: Int?,
    val height: Int?,
    val allAttributes: List<Pair<String, String>>
)
