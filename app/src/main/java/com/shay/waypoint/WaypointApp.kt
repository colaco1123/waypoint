package com.shay.waypoint

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.shay.waypoint.data.Repo
import com.shay.waypoint.data.WDb
import com.shay.waypoint.notify.Notifier

class WaypointApp : Application() {
    lateinit var repo: Repo

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, WDb::class.java, "waypoint.db").build()
        repo = Repo(this, db)
        Notifier.createChannels(this)
        repo.start()
    }
}

fun Context.repo(): Repo = (applicationContext as WaypointApp).repo
