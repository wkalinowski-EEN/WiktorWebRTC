package com.example.wiktorwebrtc

import android.Manifest
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.wiktorwebrtc.databinding.LauncherActivityBinding
import com.example.wiktorwebrtc.helper.State
import com.example.wiktorwebrtc.talkdown.TalkdownController
import com.example.wiktorwebrtc.viewmodel.MainViewModel
import android.widget.Toast
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.core.content.ContextCompat

import androidx.core.app.ActivityCompat
import com.example.wiktorwebrtc.helper.IConnect


class LauncherActivity : AppCompatActivity() {

    private val MY_PERMISSIONS_RECORD_AUDIO = 1
    lateinit var binding: LauncherActivityBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var talkdownController: TalkdownController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LauncherActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        initObservers()

        requestAudioPermissions()
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun initObservers() {
        viewModel.isConnected.observe(this, { state ->
            when (state) {
                is State.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                }
                is State.Success -> {
                    binding.progressBar.visibility = View.INVISIBLE
                    binding.tvConnect.text = "Connected"
                    binding.tvConnect.setTextColor(Color.GREEN)
                }
                is State.Failed -> {
                    binding.progressBar.visibility = View.INVISIBLE
                }
            }
        })

        viewModel.localSDPstatus.observe(this, { state ->
            when (state) {
                is State.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                }
                is State.Success -> {
                    binding.progressBar.visibility = View.INVISIBLE
                }
                is State.Failed -> {
                    binding.progressBar.visibility = View.INVISIBLE
                }
            }
        })

    }

    private fun initViews() {
        binding.buttonSend.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            talkdownController.setStunAndTurn(null)

            binding.ivSDP.setImageResource(R.drawable.accept)
            viewModel.authorized()
        }
        binding.buttonGet.setOnClickListener {
            //    talkdownController.setStunAndTurn(null)
        }

        binding.buttonConnect.setOnClickListener {
            viewModel.sendLocalSDPSuccess()
            talkdownController = TalkdownController(this, true, object : IConnect {
                override fun authorized() {
                    viewModel.authorized()
                }

                override fun notAuthorized() {
                    viewModel.notAuthorized()
                }

                override fun sendLocalSDPSuccess() {
                    viewModel.sendLocalSDPSuccess()
                }
            })
        }
    }

    private fun requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            //When permission is not granted by user, show them message why this permission is needed.
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.RECORD_AUDIO
                )
            ) {
                Toast.makeText(this, "Please grant permissions to record audio", Toast.LENGTH_LONG)
                    .show()

                //Give user option to still opt-in the permissions
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO),
                    MY_PERMISSIONS_RECORD_AUDIO
                )
            } else {
                // Show user dialog to grant permission to record audio
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO),
                    MY_PERMISSIONS_RECORD_AUDIO
                )
            }
        } else if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            )
            == PackageManager.PERMISSION_GRANTED
        ) {
            //Go ahead with recording audio now
            //   recordAudio()
        }
    }

    //Handling callback
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            MY_PERMISSIONS_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty()
                    && grantResults[0] === PackageManager.PERMISSION_GRANTED
                ) {
                    // permission was granted, yay!
                    //   recordAudio()
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Toast.makeText(this, "Permissions Denied to record audio", Toast.LENGTH_LONG)
                        .show()
                }
                return
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        talkdownController.dispose()
    }
}