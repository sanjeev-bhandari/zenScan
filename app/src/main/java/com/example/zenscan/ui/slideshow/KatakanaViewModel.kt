package com.example.zenscan.ui.slideshow

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class KatakanaViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "This is Katakana Fragment"
    }
    val text: LiveData<String> = _text
}