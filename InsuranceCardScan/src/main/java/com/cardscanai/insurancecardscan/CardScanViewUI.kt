package com.cardscanai.insurancecardscan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import com.cardscanai.insurancecardscan.databinding.ActivityCardScanViewUiBinding
import org.opencv.android.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f

import org.opencv.core.Core

import org.opencv.core.MatOfInt

import org.opencv.core.Mat

import org.opencv.core.CvType
import org.opencv.core.Scalar
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

class CardScanViewUI : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2 {
    lateinit var binding:ActivityCardScanViewUiBinding
    var squares: ArrayList<MatOfPoint> = ArrayList()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding=DataBindingUtil.setContentView(this,R.layout.activity_card_scan_view_ui)
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // camera permission granted
                activateOpenCVCameraView()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // In an educational UI, explain to the user why your app requires this
                // permission for a specific feature to behave as expected. In this UI,
                // include a "cancel" or "no thanks" button that allows the user to
                // continue using your app without granting the permission.
            }
            else -> {
                // directly ask for the permission.
                requestPermissions(
                    arrayOf(Manifest.permission.CAMERA),
                    1
                )
            }
        }
    }
    private val mLoaderCallback: BaseLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                LoaderCallbackInterface.SUCCESS -> {
                    Log.d("openCV","OpenCV loaded successfully")
                    activateOpenCVCameraView()
                }
                else -> super.onManagerConnected(status)
            }
        }
    }

    private fun activateOpenCVCameraView() {
        binding.camera.setCameraPermissionGranted()
        val activeCamera = CameraBridgeViewBase.CAMERA_ID_BACK
        binding.camera.setCameraIndex(activeCamera)
        binding.camera.visibility = View.VISIBLE
        binding.camera.setCvCameraViewListener(this)
        binding.camera.enableView()


    }

    override fun onCameraViewStarted(width: Int, height: Int) {

    }

    override fun onCameraViewStopped() {

    }

    override fun onDestroy() {
        binding.camera.disableView();
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume()
        // there's no need to load the opencv library if there is no camera preview (I think that sounds reasonable (?))
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (!OpenCVLoader.initDebug()) {
                OpenCVLoader.initAsync(
                    OpenCVLoader.OPENCV_VERSION_3_4_0, this,
                    mLoaderCallback
                )
            } else {
                Log.d("opencv","OpenCV library found inside package. Using it!");
                mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
            }
        }
    }
    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame?): Mat {
        var image = inputFrame!!.rgba()
        if (Math.random() > 0.80) {
            findSquares(image, squares)
        }

        val biggestPolygonIndex = getBiggestPolygonIndex(squares)
        if (biggestPolygonIndex != null) {
            val biggest: MatOfPoint = squares[biggestPolygonIndex]
            val corners = getCornersFromPoints(biggest.toList())
            println("corner size " + corners!!.size)
            for (corner in corners) {
                Imgproc.drawMarker(image, corner, Scalar(0.toDouble(), 191.toDouble(), 255.toDouble()), 0, 20, 3)
            }
            setGreenFrame(squares, biggestPolygonIndex, image)
            val rect=Imgproc.boundingRect(biggest)
            if (rect.width>700 && rect.height>700) {
                Log.d("contourHeightWidth",rect.height.toString()+" "+rect.width.toString())
            }
        }


        return image



    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // overengineered check for if permission with our request code and permission name was granted
        if (requestCode == 1) {
            val indexOfCameraPermission = permissions.indexOf(Manifest.permission.CAMERA)
            if (indexOfCameraPermission != -1) {
                if (grantResults.isNotEmpty()) {
                    if (grantResults[indexOfCameraPermission] == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(
                            applicationContext,
                            "Camera permission granted!",
                            Toast.LENGTH_LONG
                        ).show()
                        activateOpenCVCameraView()
                    } else {
                        Toast.makeText(
                            applicationContext,
                            "Camera permission is required to run this app!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
    var thresh = 50
    var N:Int = 11

    // helper function:
    // finds a cosine of angle between vectors
    // from pt0->pt1 and from pt0->pt2
    fun angle(pt1: Point, pt2: Point, pt0: Point): Double {
        val dx1 = pt1.x - pt0.x
        val dy1 = pt1.y - pt0.y
        val dx2 = pt2.x - pt0.x
        val dy2 = pt2.y - pt0.y
        return (dx1 * dx2 + dy1 * dy2) / Math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10)
    }

    // returns sequence of squares detected on the image.
    // the sequence is stored in the specified memory storage
    fun findSquares(image: Mat, squares: ArrayList<MatOfPoint>) {
        squares.clear()
        val smallerImg = Mat(
            Size(
                (image.width() / 2).toDouble(),
                (image.height() / 2).toDouble()
            ), image.type()
        )
        val gray = Mat(image.size(), image.type())
        val gray0 = Mat(image.size(), CvType.CV_8U)

        // down-scale and upscale the image to filter out the noise
        Imgproc.pyrDown(image, smallerImg, smallerImg.size())
        Imgproc.pyrUp(smallerImg, image, image.size())

        // find squares in every color plane of the image
        for (c in 0..2) {
            extractChannel(image, gray, c)

            // try several threshold levels
            for (l in 1 until N) {
                //Cany removed... Didn't work so well
                Imgproc.threshold(
                    gray,
                    gray0,
                    ((l + 1) * 255 / N).toDouble(),
                    255.0,
                    Imgproc.THRESH_BINARY
                )
                val contours: List<MatOfPoint> = ArrayList()

                // find contours and store them all as a list
                Imgproc.findContours(
                    gray0,
                    contours,
                    Mat(),
                    Imgproc.RETR_LIST,
                    Imgproc.CHAIN_APPROX_SIMPLE
                )
                var approx = MatOfPoint()

                // test each contour
                for (i in contours.indices) {

                    // approximate contour with accuracy proportional
                    // to the contour perimeter
                    approx = approxPolyDP(
                        contours[i],
                        Imgproc.arcLength(MatOfPoint2f(contours[i].toArray()), true) * 0.02,
                        true
                    )


                    // square contours should have 4 vertices after approximation
                    // relatively large area (to filter out noisy contours)
                    // and be convex.
                    // Note: absolute value of an area is used because
                    // area may be positive or negative - in accordance with the
                    // contour orientation
                    if (approx.toArray().size == 4 && Math.abs(Imgproc.contourArea(approx)) > 1000 &&
                        Imgproc.isContourConvex(approx)
                    ) {
                        var maxCosine = 0.0
                        for (j in 2..4) {
                            // find the maximuFm cosine of the angle between joint edges
                            val cosine = Math.abs(
                                angle(
                                    approx.toArray()[j % 4],
                                    approx.toArray()[j - 2], approx.toArray()[j - 1]
                                )
                            )
                            maxCosine = Math.max(maxCosine, cosine)
                        }

                        // if cosines of all angles are small
                        // (all angles are ~90 degree) then write quandrange
                        // vertices to resultant sequence
                        if (maxCosine < 0.3) squares.add(approx)
                    }
                }
            }
        }
    }

    fun extractChannel(source: Mat?, out: Mat?, channelNum: Int) {
        val sourceChannels: List<Mat> = ArrayList()
        val outChannel: MutableList<Mat> = ArrayList()
        Core.split(source, sourceChannels)
        outChannel.add(Mat(sourceChannels[0].size(), sourceChannels[0].type()))
        Core.mixChannels(sourceChannels, outChannel, MatOfInt(channelNum, 0))
        Core.merge(outChannel, out)
    }

    fun approxPolyDP(curve: MatOfPoint, epsilon: Double, closed: Boolean): MatOfPoint {
        val tempMat = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(curve.toArray()), tempMat, epsilon, closed)
        return MatOfPoint(tempMat.toArray())
    }
    private fun getCornersFromPoints(points: List<Point>): List<Point>? {
        var minX = 0.0
        var minY = 0.0
        var maxX = 0.0
        var maxY = 0.0
        for (point in points) {
            val x = point.x
            val y = point.y
            if (minX == 0.0 || x < minX) {
                minX = x
            }
            if (minY == 0.0 || y < minY) {
                minY = y
            }
            if (maxX == 0.0 || x > maxX) {
                maxX = x
            }
            if (maxY == 0.0 || y > maxY) {
                maxY = y
            }
        }
        val corners: MutableList<Point> = ArrayList(4)
        corners.add(Point(minX, minY))
        corners.add(Point(minX, maxY))
        corners.add(Point(maxX, minY))
        corners.add(Point(maxX, maxY))
        return corners
    }

    private fun getBiggestPolygonIndex(contours: List<MatOfPoint>): Int? {
        var maxVal = 0.0
        var maxValIdx: Int? = null
        for (contourIdx in contours.indices) {
            val contourArea: Double = Imgproc.contourArea(contours[contourIdx])
            if (maxVal < contourArea) {
                maxVal = contourArea
                maxValIdx = contourIdx
            }
        }
        return maxValIdx
    }

    private fun setGreenFrame(
        contours: List<MatOfPoint>,
        biggestPolygonIndex: Int,
        originalImage: Mat
    ) {
        Imgproc.drawContours(originalImage, contours, biggestPolygonIndex, Scalar(124.toDouble(), 252.toDouble(), 0.toDouble()), 3)
    }
}