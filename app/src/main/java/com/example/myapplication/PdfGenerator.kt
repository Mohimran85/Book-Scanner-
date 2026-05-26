package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.kernel.colors.ColorConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class PdfGenerator(private val context: Context) {

    fun createPdf(pages: List<PageData>, onComplete: (String) -> Unit, onError: (Exception) -> Unit) {
        if (pages.isEmpty()) {
            onError(Exception("No pages scanned yet! Scan some pages first."))
            return
        }

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "BookScan_$timeStamp.pdf"

            val outputStream: OutputStream
            val savedPath: String

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ : Use MediaStore to save to Downloads
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
                ) ?: throw Exception("Failed to create file in Downloads")

                outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw Exception("Failed to open output stream")
                savedPath = "Downloads/$fileName"
            } else {
                // Older Android: Use direct file access
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val pdfFile = File(downloadsDir, fileName)
                outputStream = pdfFile.outputStream()
                savedPath = pdfFile.absolutePath
            }

            val writer = PdfWriter(outputStream)
            val pdf = PdfDocument(writer)
            val document = Document(pdf)

            for (page in pages) {
                // Convert Bitmap to byte array
                val stream = ByteArrayOutputStream()
                page.bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val byteArray = stream.toByteArray()

                val imageData = ImageDataFactory.create(byteArray)
                val image = Image(imageData)

                // Scale image to fit page
                image.setAutoScale(true)

                document.add(image)

                // Add extracted text below image for searchability
                if (page.extractedText.isNotBlank()) {
                    val paragraph = Paragraph(page.extractedText)
                    paragraph.setFontColor(ColorConstants.DARK_GRAY)
                    paragraph.setFontSize(9f)
                    document.add(paragraph)
                }
            }

            document.close()
            outputStream.close()
            onComplete(savedPath)
        } catch (e: Exception) {
            onError(e)
        }
    }
}

data class PageData(
    val bitmap: Bitmap,
    val extractedText: String,
    val pageNumber: Int = 0
)
