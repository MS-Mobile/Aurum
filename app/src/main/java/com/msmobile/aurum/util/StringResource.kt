package com.msmobile.aurum.util

import androidx.annotation.StringRes

data class StringResource(@param:StringRes val textResId: Int, val arguments: List<Any> = listOf())
