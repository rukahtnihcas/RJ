package com.rj.app

import android.app.Application
import com.rj.app.show.ShowEngine

class RjApp : Application() {
    val engine: ShowEngine by lazy { ShowEngine(this) }

    companion object {
        /** True while a show is on air (read by the car-connected receiver). */
        @Volatile var isLive = false
    }
}
