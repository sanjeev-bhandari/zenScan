package com.example.realsanjeev.ui.gallery

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HiraganaViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "This is Hiragana Fragment"
    }
    val text: LiveData<String> = _text
}