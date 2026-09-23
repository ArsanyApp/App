package com.choice.autotap

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.choice.autotap.data.AppDatabase
import com.choice.autotap.data.MacroRepository
import com.choice.autotap.data.RunLogRepository
import com.choice.autotap.data.SnippetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container shared by the UI and the services. */
class AppGraph(context: Context) {
    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "choice_auto_tap.db")
        .fallbackToDestructiveMigration()
        .build()

    val macros = MacroRepository(db.macroDao())
    val logs = RunLogRepository(db.runLogDao())
    val snippets = SnippetRepository(db.snippetDao())

    /** For work that must outlive a screen (saving logs, final saves on exit). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class ChoiceApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as ChoiceApp).graph
