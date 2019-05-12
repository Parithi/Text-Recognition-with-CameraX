package com.parithi.textreco

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.util.Rational
import android.view.Surface
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import android.provider.MediaStore
import java.net.URI
import android.R.attr.orientation
import android.R.attr.bitmap
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.AlphaAnimation
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage


private const val REQUEST_CODE_PERMISSIONS = 10
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

class MainActivity : AppCompatActivity() {

    private var isImageCaptured = false
    private lateinit var imageCapture: ImageCapture
    private lateinit var preview: Preview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (allPermissionsGranted()) {
            view_finder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        view_finder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }
    }

    private fun startCamera() {
        initializePreview()
        initializeCameraConfig()
        bindCameraX()
        capture_button.setOnClickListener {
            if(isImageCaptured){
                resetCamera()
            } else {
                analysed_text.text = getString(R.string.please_wait)
                capture_button.text = getString(R.string.capturing)
                takePicture()
                isImageCaptured = true
            }
        }
    }

    private fun resetCamera() {
        capture_button.text = getString(R.string.capture)
        captured_image.setImageBitmap(null)
        analysed_text.text = getString(R.string.capture_message)
        analysed_text.setTextIsSelectable(false)
        isImageCaptured = false
    }

    private fun initializePreview() {
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetAspectRatio(Rational(1, 1))
            setTargetResolution(Size(640, 640))
        }.build()

        preview = Preview(previewConfig)

        preview.setOnPreviewOutputUpdateListener {
            val parent = view_finder.parent as ViewGroup
            parent.removeView(view_finder)
            parent.addView(view_finder, 0)

            view_finder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }
    }

    private fun initializeCameraConfig() {
        val imageCaptureConfig = ImageCaptureConfig.Builder()
            .apply {
                setTargetAspectRatio(Rational(1, 1))
                setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            }.build()

        imageCapture = ImageCapture(imageCaptureConfig)
    }

    private fun bindCameraX(){
        CameraX.bindToLifecycle(this, preview, imageCapture)
    }

    private fun takePicture() {
        val file = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg")

        imageCapture.takePicture(file, object : ImageCapture.OnImageSavedListener {
            override fun onError(error: ImageCapture.UseCaseError, message: String, exc: Throwable?) {
                val msg = "Photo capture failed: $message"
//                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                Log.e("CameraXApp", msg)
                exc?.printStackTrace()
            }

            override fun onImageSaved(file: File) {
                val msg = "Photo capture succeeded: ${file.absolutePath}"
//                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                showImageOnView(file.path)
                file.delete()
            }
        })
    }

    private fun showImageOnView(path : String) {
        val uri = Uri.fromFile(File(path))
        val exifInterface = ExifInterface(uri.path!!)
        val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
        val rotatedBitmap = rotateBitmap(bitmap, orientation)
        captured_image.setImageBitmap(rotatedBitmap)
        processImage(rotatedBitmap)
    }

    private fun processImage(bitmap : Bitmap?) {
        analysed_text.text = getString(R.string.processing)
        capture_button.text = getString(R.string.reset)
        bitmap?.let {
            val image = FirebaseVisionImage.fromBitmap(bitmap)
            val detector = FirebaseVision.getInstance().onDeviceTextRecognizer
            detector.processImage(image).addOnSuccessListener { firebaseVisionText ->
                val analysedText = firebaseVisionText.text
                if(!analysedText.isNullOrBlank()) {
                    analysed_text.text = analysedText
                    analysed_text.setTextIsSelectable(true)
                } else {
                    resetCamera()
                    Toast.makeText(baseContext, "No characters found", Toast.LENGTH_SHORT).show()
                }
            }
                .addOnFailureListener {
                    Toast.makeText(baseContext, "No characters found", Toast.LENGTH_SHORT).show()
                    it.printStackTrace()
                }
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap? {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_NORMAL -> return bitmap
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        try {
            val bmRotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()

            return bmRotated
        } catch (e: OutOfMemoryError) {
            Toast.makeText(baseContext, "Unable to load image", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
            return null
        }

    }

    private fun updateTransform() {
        val matrix = Matrix()

        val centerX = view_finder.width / 2f
        val centerY = view_finder.height / 2f

        val rotationDegrees = when (view_finder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        view_finder.setTransform(matrix)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                view_finder.post { startCamera() }
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    override fun onBackPressed() {
        if(isImageCaptured){
            resetCamera()
        } else {
            super.onBackPressed()
        }
    }
}
