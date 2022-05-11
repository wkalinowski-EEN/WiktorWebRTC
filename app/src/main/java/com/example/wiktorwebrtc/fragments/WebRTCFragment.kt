package com.example.wiktorwebrtc.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.wiktorwebrtc.R
import com.example.wiktorwebrtc.talkdown.TalkdownController

class WebRTCFragment : Fragment() {

    private lateinit var talkdownController: TalkdownController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)



    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_first, container, false)
    }

}