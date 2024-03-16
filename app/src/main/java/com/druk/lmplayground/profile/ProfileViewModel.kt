package com.druk.lmplayground.profile

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.druk.lmplayground.data.colleagueProfile
import com.druk.lmplayground.data.meProfile

class ProfileViewModel : ViewModel() {

    private var userId: String = ""

    fun setUserId(newUserId: String?) {
        if (newUserId != userId) {
            userId = newUserId ?: meProfile.userId
        }
        // Workaround for simplicity
        _userData.value = if (userId == meProfile.userId || userId == meProfile.displayName) {
            meProfile
        } else {
            colleagueProfile
        }
    }

    private val _userData = MutableLiveData<ProfileScreenState>()
    val userData: LiveData<ProfileScreenState> = _userData
}

@Immutable
data class ProfileScreenState(
    val userId: String,
    @DrawableRes val photo: Int?,
    val name: String,
    val status: String,
    val displayName: String,
    val position: String,
    val twitter: String = "",
    val timeZone: String?, // Null if me
    val commonChannels: String? // Null if me
) {
    fun isMe() = userId == meProfile.userId
}
