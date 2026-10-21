package com.msmobile.aurum.util

import android.util.Log

/** Injected instead of calling `android.util.Log` directly, so DI classes stay testable on the JVM. */
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

class AndroidLogger : Logger {
    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
