package com.shihab.diplay

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityOptionsCompat
import com.shihab.diplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", shadows = [FileProviderPathTestShadow::class])
class DiagnosticExportUiTest {
    @Test fun missingPickerSavesAReportAndProvidesSelectableTextInsideDiPlay() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            authority,
            DiagnosticReportProvider().apply { attachInfo(context, info) },
        )
        val missingPicker = object : ActivityResultLauncher<String>() {
            override fun launch(input: String, options: ActivityOptionsCompat?) {
                throw ActivityNotFoundException("No DocumentsUI")
            }
            override fun unregister() = Unit
            override fun getContract() = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
        }
        ReflectionHelpers.setField(activity, "export", missingPicker)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "chooseReportDestination")
            shadowOf(Looper.getMainLooper()).idle()
            val deadline = System.nanoTime() + 5_000_000_000L
            var reports = reportDirectory(context)
            while ((reports == null || reports.listFiles().isNullOrEmpty()) && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
                reports = reportDirectory(context)
            }
            val saved = requireNotNull(ShadowDialog.getLatestDialog())
            val file = requireNotNull(reports?.listFiles()?.singleOrNull()) { "diagnostic report was not saved" }
            assertTrue(file.name.endsWith(".txt"))
            assertTrue(file.readText().contains("Android 9 / API 28"))
            assertTrue(descendants(saved.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.contains(file.absolutePath) })
            descendants(saved.window!!.decorView).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.view_diagnostic_report) }
                .performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val viewer = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue(descendants(viewer.window!!.decorView).filterIsInstance<TextView>()
                .any { it.isTextSelectable && it.text.contains("Android 9 / API 28") })
            viewer.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun reportDirectory(context: android.content.Context): File? {
        val external = context.getExternalFilesDir(null)?.let { File(it, "diagnostic-reports") }
        val privateDir = File(context.filesDir, "diagnostic-reports")
        return listOfNotNull(external, privateDir).firstOrNull { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
