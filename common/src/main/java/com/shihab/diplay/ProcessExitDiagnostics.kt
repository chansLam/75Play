package com.shihab.diplay

import android.content.Context
import com.shihab.diplay.compat.realSdkInt

/** Own-app numeric exit metadata only; never read traces, descriptions or process state blobs. */
internal object ProcessExitDiagnostics {
    private const val MAX_RECORDS = 3
    private const val UNSUPPORTED = "Process exits available=false requiresApi=30"

    internal data class Record(
        val timestampMillis: Long,
        val reason: Int,
        val status: Int,
        val importance: Int,
        val pssKiB: Long,
        val rssKiB: Long,
    )

    fun report(context: Context): String {
        if (realSdkInt() < 30) return UNSUPPORTED
        return report(realSdkInt(), System.currentTimeMillis()) {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE)
                ?: throw IllegalStateException()
            val method = manager.javaClass.getMethod(
                "getHistoricalProcessExitReasons", String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            @Suppress("UNCHECKED_CAST")
            (method.invoke(manager, context.packageName, 0, MAX_RECORDS) as? List<Any>).orEmpty()
                .take(MAX_RECORDS).map { exit ->
                    val type = exit.javaClass
                    fun number(name: String) = type.getMethod(name).invoke(exit) as Int
                    fun long(name: String) = type.getMethod(name).invoke(exit) as Long
                    Record(long("getTimestamp"), number("getReason"), number("getStatus"),
                        number("getImportance"), long("getPss"), long("getRss"))
                }
        }
    }

    internal fun report(sdkInt: Int, nowMillis: Long, read: () -> List<Record>): String {
        if (sdkInt < 30) return UNSUPPORTED
        val records = try {
            read().take(MAX_RECORDS)
        } catch (error: Exception) {
            return unavailable(error)
        } catch (error: LinkageError) {
            return unavailable(error)
        }
        if (records.isEmpty()) return "Process exits available=true count=0; no retained exit record."
        return buildString {
            append("Process exits available=true count=${records.size}; historical records may predate this session.")
            records.forEachIndexed { index, record ->
                val age = if (record.timestampMillis > 0 && record.timestampMillis <= nowMillis) {
                    (nowMillis - record.timestampMillis).toString()
                } else "unknown"
                append("\nProcess exit index=$index ageMs=$age reason=${reason(record.reason)} " +
                    "reasonCode=${record.reason} status=${record.status} importance=${record.importance} " +
                    "pssKiB=${record.pssKiB.coerceAtLeast(0)} rssKiB=${record.rssKiB.coerceAtLeast(0)}")
            }
        }
    }

    private fun unavailable(error: Throwable): String {
        val type = error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")
        return "Process exits available=false error=$type"
    }

    // Stable public Android 11 reason codes. Keep newer/unknown values visible as numbers.
    private fun reason(code: Int) = when (code) {
        0 -> "unknown"
        1 -> "self_exit"
        2 -> "signal"
        3 -> "low_memory"
        4 -> "java_crash"
        5 -> "native_crash"
        6 -> "anr"
        7 -> "initialization_failure"
        8 -> "permission_change"
        9 -> "excessive_resources"
        10 -> "user_requested"
        11 -> "user_stopped"
        12 -> "dependency_died"
        13 -> "other"
        else -> "unknown"
    }
}
