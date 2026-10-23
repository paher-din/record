package consumer

import android.content.Context
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RecordStartup
import com.paherdin.record.core.data.StartupFailure
import com.paherdin.record.core.data.StartupObserver
import com.paherdin.record.core.data.StartupProgress
import com.paherdin.record.core.data.StartupResult

/**
 * Positive consumer fixture (plan M1-T03 Step 4): the documented startup gate
 * surface compiles — create, progress observer, progress/failure enums,
 * sealed results, and the Ready repository.
 */
class StartupSurfaceUsage {
    suspend fun use(context: Context): RecordRepository? {
        val startup = RecordStartup.create(context)
        var progress: StartupProgress? = null
        val observer = StartupObserver { progress = it }
        val started: StartupResult = startup.start(observer)
        val retried: StartupResult = startup.retry(observer)
        val failure = StartupFailure.Unexpected
        val repository = when (started) {
            is StartupResult.Ready -> started.repository
            is StartupResult.Blocked -> null
        }
        check(failure.name.isNotEmpty())
        check(progress == null || progress.name.isNotEmpty())
        check(repository != null || retried !is StartupResult.Ready)
        return repository
    }
}
