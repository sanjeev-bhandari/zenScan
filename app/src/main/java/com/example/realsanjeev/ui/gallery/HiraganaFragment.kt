package com.example.realsanjeev.ui.gallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.realsanjeev.databinding.FragmentHiraganaBinding

class HiraganaFragment : Fragment() {

    private var _binding: FragmentHiraganaBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val hiraganaViewModel =
            ViewModelProvider(this).get(HiraganaViewModel::class.java)

        _binding = FragmentHiraganaBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textHiragana
        hiraganaViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}