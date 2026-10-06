package org.hellokittyvpn.android.ui
import org.hellokittyvpn.android.R
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException

enum class ProblemReason(val resource: Int) {
    PROFILE(R.string.problem_profile_body), NETWORK(R.string.problem_network_body),
    TIMEOUT(R.string.problem_timeout_body), UNKNOWN(R.string.problem_unknown_body),
}
data class AppProblem(val reason: ProblemReason)
internal fun Throwable.toAppProblem(): AppProblem {
    if (this is CancellationException) throw this
    return AppProblem(when (this) {
        is IllegalArgumentException -> ProblemReason.PROFILE
        is SocketTimeoutException -> ProblemReason.TIMEOUT
        is IOException -> ProblemReason.NETWORK
        else -> ProblemReason.UNKNOWN
    })
}
