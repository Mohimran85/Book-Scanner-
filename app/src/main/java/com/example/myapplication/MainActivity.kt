package com.example.myapplication

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.filled.*
import androidx.core.content.FileProvider
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : ComponentActivity() {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val pdfGenerator by lazy { PdfGenerator(this) }

    // Each entry is a single page (left or right half of a book spread)
    private val scannedPages = mutableStateListOf<PageData>()
    private var lastPdfPath = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scannerOptions = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(RESULT_FORMAT_JPEG)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(scannerOptions)

        val scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                if (scanResult != null) {
                    handleScanResult(scanResult)
                }
            }
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BookScannerScreen(
                        scannedPages = scannedPages,
                        lastPdfPath = lastPdfPath.value,
                        onStartScan = {
                            lastPdfPath.value = null
                            scanner.getStartScanIntent(this@MainActivity)
                                .addOnSuccessListener { intentSender ->
                                    scannerLauncher.launch(
                                        IntentSenderRequest.Builder(intentSender).build()
                                    )
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(this, "Scanner error: ${e.message}", Toast.LENGTH_LONG).show()
                                    Log.e("Scanner", "Failed to start", e)
                                }
                        },
                        onGeneratePdf = {
                            pdfGenerator.createPdf(scannedPages.toList(), { path ->
                                runOnUiThread {
                                    lastPdfPath.value = path
                                    Toast.makeText(this, "PDF saved to: $path", Toast.LENGTH_LONG).show()
                                }
                            }, { e ->
                                runOnUiThread {
                                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            })
                        },
                        onClearPages = {
                            scannedPages.clear()
                            lastPdfPath.value = null
                        }
                    )
                }
            }
        }
    }

    /**
     * Each scanned image from ML Kit is a full book spread (2 pages visible).
     * This function splits each spread into LEFT (odd page) and RIGHT (even page),
     * runs OCR on each half, and adds them as separate pages.
     */
    private fun handleScanResult(result: GmsDocumentScanningResult) {
        val pages = result.pages ?: return

        for (page in pages) {
            val imageUri = page.imageUri
            try {
                val inputStream = contentResolver.openInputStream(imageUri)
                val fullBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (fullBitmap != null) {
                    val width = fullBitmap.width
                    val height = fullBitmap.height
                    val halfWidth = width / 2

                    // Split into LEFT page and RIGHT page
                    val leftBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, halfWidth, height)
                    val rightBitmap = Bitmap.createBitmap(fullBitmap, halfWidth, 0, width - halfWidth, height)

                    // OCR on LEFT page
                    val leftImage = InputImage.fromBitmap(leftBitmap, 0)
                    textRecognizer.process(leftImage)
                        .addOnSuccessListener { visionTextL ->
                            val pageNum = scannedPages.size + 1
                            scannedPages.add(PageData(leftBitmap, visionTextL.text, pageNum))
                            Log.d("Scanner", "Left page $pageNum added. Text: ${visionTextL.text.take(80)}")

                            // OCR on RIGHT page (after left is done to maintain order)
                            val rightImage = InputImage.fromBitmap(rightBitmap, 0)
                            textRecognizer.process(rightImage)
                                .addOnSuccessListener { visionTextR ->
                                    val pageNum2 = scannedPages.size + 1
                                    scannedPages.add(PageData(rightBitmap, visionTextR.text, pageNum2))
                                    Log.d("Scanner", "Right page $pageNum2 added. Text: ${visionTextR.text.take(80)}")
                                }
                                .addOnFailureListener { e ->
                                    val pageNum2 = scannedPages.size + 1
                                    scannedPages.add(PageData(rightBitmap, "", pageNum2))
                                    Log.e("Scanner", "Right OCR failed", e)
                                }
                        }
                        .addOnFailureListener { e ->
                            val pageNum = scannedPages.size + 1
                            scannedPages.add(PageData(leftBitmap, "", pageNum))
                            Log.e("Scanner", "Left OCR failed", e)

                            val rightImage = InputImage.fromBitmap(rightBitmap, 0)
                            textRecognizer.process(rightImage)
                                .addOnSuccessListener { visionTextR ->
                                    val pageNum2 = scannedPages.size + 1
                                    scannedPages.add(PageData(rightBitmap, visionTextR.text, pageNum2))
                                }
                                .addOnFailureListener {
                                    val pageNum2 = scannedPages.size + 1
                                    scannedPages.add(PageData(rightBitmap, "", pageNum2))
                                }
                        }
                }
            } catch (e: Exception) {
                Log.e("Scanner", "Error processing page", e)
            }
        }
    }
}

@Composable
fun BookScannerScreen(
    scannedPages: List<PageData>,
    lastPdfPath: String?,
    onStartScan: () -> Unit,
    onGeneratePdf: () -> Unit,
    onClearPages: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF0F1F3))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp, bottom = 100.dp) // padding for bottom bar
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Help Icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .border(1.dp, Color.LightGray, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = "Help",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Notification Icon
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        imageVector = Icons.Rounded.Notifications,
                        contentDescription = "Notifications",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE68A00)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("5", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Greeting
            Text(
                text = "Hi User,",
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Gray
            )
            Text(
                text = "How can I help\nyou today?",
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                lineHeight = 44.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Action Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.CropFree,
                    title = "Scan",
                    subtitle = "Documents, ID cards...",
                    onClick = onStartScan
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Edit,
                    title = "Edit",
                    subtitle = "Sign, add text, mark...",
                    onClick = onGeneratePdf // Mapped to generate PDF
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Transform,
                    title = "Convert",
                    subtitle = "PDF, DOCX, JPG, TX...",
                    onClick = onClearPages // Mapped to clear for now
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.AutoAwesome,
                    title = "Ask AI",
                    subtitle = "Summarize, finish wri...",
                    onClick = {}
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Search Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(28.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = Color.Gray
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Search",
                        color = Color.Gray,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Mic",
                        tint = Color.Gray
                    )
                }
            }

            // Scanned Pages list (Conditionally shown if pages exist to retain functionality)
            if (scannedPages.isNotEmpty() || lastPdfPath != null) {
                Spacer(modifier = Modifier.height(24.dp))
                if (lastPdfPath != null) {
                    Text(
                        text = "✅ PDF Generated!",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (scannedPages.isNotEmpty()) {
                    Text(
                        text = "Scanned Pages (${scannedPages.size}):",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().height(100.dp)
                    ) {
                        itemsIndexed(scannedPages) { _, page ->
                            Image(
                                bitmap = page.bitmap.asImageBitmap(),
                                contentDescription = "Page ${page.pageNumber}",
                                modifier = Modifier
                                    .width(70.dp)
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
        }

        // Bottom Action Bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left pill (Layers + User)
                Box(
                    modifier = Modifier
                        .height(64.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.Black)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Layers (white circle)
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Layers,
                                contentDescription = "Layers",
                                tint = Color.Black
                            )
                        }
                        // User (black circle with gray outline)
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.Black)
                                .border(1.dp, Color.DarkGray, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = "User",
                                tint = Color.LightGray
                            )
                        }
                    }
                }
                
                // Right circle (Plus)
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Add",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun ActionCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .height(130.dp)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(24.dp), spotColor = Color(0x1A000000)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.Gray,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
            }
        }
    }
}