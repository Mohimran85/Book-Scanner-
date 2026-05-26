package com.example.myapplication

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

class ScannerProcessor {

    private var prevGray: Mat? = null
    private var stableFramesCount = 0
    private var lastCaptureTime = 0L
    private var hasMoved = true

    // Tunable parameters
    private val stabilityThreshold = 8.0
    private val stableFramesNeeded = 12
    private val cooldownMs = 3000L
    private val minBookAreaRatio = 0.10   // Book must cover at least 10% of frame
    private val maxBookAreaRatio = 0.90   // Must not be the whole frame

    fun processFrame(bitmap: Bitmap): ProcessingResult {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(21.0, 21.0), 0.0)

        // --- 1. Stability Check ---
        var isCurrentlyStable = false
        if (prevGray != null && prevGray!!.size() == blurred.size()) {
            val diff = Mat()
            Core.absdiff(prevGray, blurred, diff)
            val meanDiff = Core.mean(diff).`val`[0]
            if (meanDiff < stabilityThreshold) {
                stableFramesCount++
                if (stableFramesCount >= stableFramesNeeded) {
                    isCurrentlyStable = true
                }
            } else {
                stableFramesCount = 0
                hasMoved = true
            }
            diff.release()
        }
        if (prevGray != null) prevGray!!.release()
        prevGray = blurred.clone()

        // --- 2. Book Detection ---
        val bookCorners = detectBookCorners(gray, mat.cols(), mat.rows())
        val bookDetected = bookCorners != null

        // --- 3. Decide capture ---
        val currentTime = System.currentTimeMillis()
        var shouldCapture = false
        if (isCurrentlyStable && hasMoved && bookDetected && (currentTime - lastCaptureTime > cooldownMs)) {
            lastCaptureTime = currentTime
            stableFramesCount = 0
            hasMoved = false
            shouldCapture = true
        }

        // --- 4. Crop book, split pages ---
        var leftBitmap: Bitmap? = null
        var rightBitmap: Bitmap? = null

        if (shouldCapture && bookCorners != null) {
            try {
                // Perspective-transform to get a flat, cropped book image
                val croppedBook = cropBookPerspective(mat, bookCorners)

                if (croppedBook != null && croppedBook.cols() > 10 && croppedBook.rows() > 10) {
                    val width = croppedBook.cols()
                    val height = croppedBook.rows()
                    val centerX = width / 2

                    val leftMat = Mat(croppedBook, Rect(0, 0, centerX, height))
                    val rightMat = Mat(croppedBook, Rect(centerX, 0, width - centerX, height))

                    val leftEnhanced = enhancePage(leftMat)
                    val rightEnhanced = enhancePage(rightMat)

                    leftBitmap = Bitmap.createBitmap(leftEnhanced.cols(), leftEnhanced.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(leftEnhanced, leftBitmap)

                    rightBitmap = Bitmap.createBitmap(rightEnhanced.cols(), rightEnhanced.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(rightEnhanced, rightBitmap)

                    leftMat.release()
                    rightMat.release()
                    leftEnhanced.release()
                    rightEnhanced.release()
                }

                croppedBook?.release()
            } catch (e: Exception) {
                Log.e("ScannerProcessor", "Error cropping book", e)
            }
        }

        // --- 5. Draw overlay ---
        val resultMat = mat.clone()

        if (bookCorners != null) {
            // Draw green border around detected book
            val pts = bookCorners.toArray()
            for (i in pts.indices) {
                Imgproc.line(resultMat, pts[i], pts[(i + 1) % pts.size],
                    Scalar(0.0, 255.0, 0.0, 255.0), 4)
            }
            // Draw center fold line ONLY within the book
            val topCenter = Point((pts[0].x + pts[1].x) / 2, (pts[0].y + pts[1].y) / 2)
            val botCenter = Point((pts[3].x + pts[2].x) / 2, (pts[3].y + pts[2].y) / 2)
            Imgproc.line(resultMat, topCenter, botCenter,
                Scalar(0.0, 200.0, 255.0, 255.0), 3)
        }

        // Status text on frame
        val statusMsg = when {
            shouldCapture -> "CAPTURED!"
            !bookDetected -> "No book detected - point at a book"
            isCurrentlyStable && !hasMoved -> "Turn page to scan next..."
            isCurrentlyStable -> "Stable - capturing soon..."
            else -> "Hold still..."
        }
        val textColor = if (bookDetected) Scalar(0.0, 255.0, 0.0, 255.0) else Scalar(255.0, 80.0, 80.0, 255.0)
        // Background for text readability
        Imgproc.rectangle(resultMat, Point(0.0, 0.0), Point(resultMat.cols().toDouble(), 80.0),
            Scalar(0.0, 0.0, 0.0, 180.0), -1)
        Imgproc.putText(resultMat, statusMsg, Point(15.0, 55.0),
            Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, textColor, 3)

        val previewBitmap = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resultMat, previewBitmap)

        mat.release()
        gray.release()
        blurred.release()
        resultMat.release()

        return ProcessingResult(previewBitmap, shouldCapture, leftBitmap, rightBitmap, isCurrentlyStable, bookDetected)
    }

    /**
     * Detects the 4 corners of the largest book-shaped rectangle in the frame.
     * Returns a MatOfPoint2f with exactly 4 points ordered: TL, TR, BR, BL.
     * Returns null if no book is found.
     */
    private fun detectBookCorners(gray: Mat, frameWidth: Int, frameHeight: Int): MatOfPoint2f? {
        val frameArea = frameWidth.toDouble() * frameHeight.toDouble()

        // Adaptive threshold to find the book against the background
        val blurForEdge = Mat()
        Imgproc.GaussianBlur(gray, blurForEdge, Size(5.0, 5.0), 0.0)

        val edged = Mat()
        Imgproc.Canny(blurForEdge, edged, 40.0, 120.0)

        // Dilate to close gaps in edges
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.dilate(edged, edged, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edged, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        blurForEdge.release()
        edged.release()
        hierarchy.release()
        kernel.release()

        // Sort contours by area (largest first)
        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }

        var bestCorners: MatOfPoint2f? = null

        for (contour in sorted) {
            val area = Imgproc.contourArea(contour)
            val areaRatio = area / frameArea

            if (areaRatio < minBookAreaRatio) break  // Too small, rest will be smaller
            if (areaRatio > maxBookAreaRatio) {
                contour.release()
                continue // Skip if it's basically the whole frame
            }

            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

            val vertices = approx.toArray().size

            if (vertices == 4) {
                // Found a quadrilateral! Order the points: TL, TR, BR, BL
                bestCorners = orderPoints(approx)
                contour2f.release()
                approx.release()
                break
            }

            contour2f.release()
            approx.release()
        }

        // Clean up remaining contours
        for (c in sorted) c.release()

        return bestCorners
    }

    /**
     * Orders 4 points as: Top-Left, Top-Right, Bottom-Right, Bottom-Left.
     */
    private fun orderPoints(pts: MatOfPoint2f): MatOfPoint2f {
        val points = pts.toArray()
        // Sort by Y first to separate top/bottom
        val sortedByY = points.sortedBy { it.y }
        val topTwo = sortedByY.take(2).sortedBy { it.x }
        val bottomTwo = sortedByY.drop(2).sortedBy { it.x }

        return MatOfPoint2f(topTwo[0], topTwo[1], bottomTwo[1], bottomTwo[0])
    }

    /**
     * Applies a perspective transform to extract the book region as a flat rectangle.
     */
    private fun cropBookPerspective(src: Mat, corners: MatOfPoint2f): Mat? {
        val pts = corners.toArray()
        // TL=0, TR=1, BR=2, BL=3

        // Compute width and height of the destination rectangle
        val widthTop = distance(pts[0], pts[1])
        val widthBottom = distance(pts[3], pts[2])
        val maxWidth = maxOf(widthTop, widthBottom).toInt()

        val heightLeft = distance(pts[0], pts[3])
        val heightRight = distance(pts[1], pts[2])
        val maxHeight = maxOf(heightLeft, heightRight).toInt()

        if (maxWidth < 50 || maxHeight < 50) return null

        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth.toDouble(), 0.0),
            Point(maxWidth.toDouble(), maxHeight.toDouble()),
            Point(0.0, maxHeight.toDouble())
        )

        val transform = Imgproc.getPerspectiveTransform(corners, dst)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, transform, Size(maxWidth.toDouble(), maxHeight.toDouble()))

        transform.release()
        dst.release()

        return warped
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun enhancePage(src: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        val denoised = Mat()
        Imgproc.GaussianBlur(gray, denoised, Size(3.0, 3.0), 0.0)

        val thresh = Mat()
        Imgproc.adaptiveThreshold(
            denoised, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY, 15, 4.0
        )

        gray.release()
        denoised.release()
        return thresh
    }
}

data class ProcessingResult(
    val preview: Bitmap,
    val isCaptured: Boolean,
    val leftPage: Bitmap?,
    val rightPage: Bitmap?,
    val isStable: Boolean,
    val bookDetected: Boolean
)
