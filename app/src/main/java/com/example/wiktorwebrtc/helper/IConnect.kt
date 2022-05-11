package com.example.wiktorwebrtc.helper

interface IConnect {
    fun authorized()
    fun notAuthorized()
    fun sendLocalSDPSuccess()
}