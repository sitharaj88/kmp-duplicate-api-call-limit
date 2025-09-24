package com.sitharaj.myapplication

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform