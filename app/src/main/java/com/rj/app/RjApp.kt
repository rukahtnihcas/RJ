package com.rj.app

import android.app.Application
import com.rj.app.show.ShowEngine

class RjApp : Application() {
    val engine: ShowEngine by lazy { ShowEngine(this) }
}
