package com.asl.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Setup / pairing screen. Also the navigation entry point. */
@Serializable
data object Main : NavKey

/** Landscape caption display, reached once pairing succeeds. */
@Serializable
data object Display : NavKey
