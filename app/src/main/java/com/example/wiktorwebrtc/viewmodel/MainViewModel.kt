package com.example.wiktorwebrtc.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.wiktorwebrtc.helper.State

class MainViewModel : ViewModel() {

    private val _isConnected = MutableLiveData<State<Any>>()
    val isConnected: LiveData<State<Any>> = _isConnected

    private val _localSDPstatus = MutableLiveData<State<Any>>()
    val localSDPstatus: LiveData<State<Any>> = _localSDPstatus

    fun authorized() {
        _isConnected.postValue(State.success(true))
    }

    fun notAuthorized() {
        _isConnected.postValue(State.failed(""))
    }

    fun sendLocalSDPSuccess(){
        _localSDPstatus.postValue(State.success(true))
    }
}