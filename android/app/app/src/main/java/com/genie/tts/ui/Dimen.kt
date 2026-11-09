package com.genie.tts.ui

import androidx.annotation.DimenRes
import android.content.Context
import android.view.View

internal fun Context.px(@DimenRes id: Int): Int = resources.getDimensionPixelSize(id)

internal fun Context.dimen(@DimenRes id: Int): Float = resources.getDimension(id)

internal fun View.px(@DimenRes id: Int): Int = resources.getDimensionPixelSize(id)

internal fun View.dimen(@DimenRes id: Int): Float = resources.getDimension(id)
