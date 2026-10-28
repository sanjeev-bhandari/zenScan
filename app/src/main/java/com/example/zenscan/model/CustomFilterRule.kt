package com.example.zenscan.model

data class CustomFilterRule(
    val id: String,
    val pattern: String,
    val isRegex: Boolean,
    val label: String
)
