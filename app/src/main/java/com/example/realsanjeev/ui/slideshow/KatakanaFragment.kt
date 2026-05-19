package com.example.realsanjeev.ui.slideshow

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.realsanjeev.databinding.FragmentKatakanaBinding

class KatakanaFragment : Fragment() {

    private var _binding: FragmentKatakanaBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val katakanaViewModel =
            ViewModelProvider(this).get(KatakanaViewModel::class.java)

        _binding = FragmentKatakanaBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textKatakana
        katakanaViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}