package com.choice.autotap

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.choice.autotap.data.AppDatabase
import com.choice.autotap.data.MacroRepository
import com.choice.autotap.data.RunLogRepository
import com.choice.autotap.data.SnippetRepository
import com.choice.autotap.license.AndroidLicenseStore
import com.choice.autotap.license.HttpLicenseApi
import com.choice.autotap.license.LicenseController
import com.choice.autotap.license.LicenseTokenVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container shared by the UI and the services. */
class AppGraph(private val context: Context) {
    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "choice_auto_tap.db")
        .fallbackToDestructiveMigration()
        .build()

    val macros = MacroRepository(db.macroDao())
    val logs = RunLogRepository(db.runLogDao())
    val snippets = SnippetRepository(db.snippetDao())

    /**
     * Licensing gate. Independent of the macro engine; the UI and the accessibility service only
     * ask it whether the app may be used. Server URL and public key come from the build config.
     */
    val license: LicenseController by lazy {
        val api = HttpLicenseApi(BuildConfig.LICENSE_API_URL, allowInsecureHttp = BuildConfig.LICENSE_ALLOW_INSECURE)
        LicenseController(
            store = AndroidLicenseStore(context),
            api = api,
            verifier = LicenseTokenVerifier(BuildConfig.LICENSE_PUBLIC_KEY),
            configured = api.isConfigured,
        )
    }

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
