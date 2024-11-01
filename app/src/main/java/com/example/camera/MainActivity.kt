package com.example.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.icu.text.SimpleDateFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.utils.MatrixExt.postRotate
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrowseGallery
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camera.ui.theme.CameraTheme
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale


class MainActivity : ComponentActivity() {

    private var recording: Recording? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val controller = remember {
                LifecycleCameraController(application).apply { setEnabledUseCases(
                    CameraController.IMAGE_CAPTURE or
                    CameraController.VIDEO_CAPTURE
                )}

            }
            val viewModel = viewModel()
            val scaffoldState = rememberBottomSheetScaffoldState()

            val bitmaps = viewModel.bitmap.collectAsState()
            val scope = rememberCoroutineScope()

            if(ContextCompat.checkSelfPermission(application, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 0)
            }

            CameraTheme {

                BottomSheetScaffold(
                    scaffoldState = scaffoldState,
                    sheetPeekHeight = 0.dp,
                    sheetContent = {
                    LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2),
                        modifier = Modifier.padding(16.dp),) {
                        items(bitmaps.value){
                            bitmap ->
                            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "image",
                                modifier =  Modifier.clip(RoundedCornerShape(10.dp))
                                    .padding(8.dp))
                        }
                    }
                }) {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .fillMaxSize()){
                        cameraPreview(controller, Modifier.fillMaxSize())

                        IconButton(onClick = {
                            controller.cameraSelector = if (controller.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA){
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            }else{
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }
                        }, modifier = Modifier
                            .padding(20.dp)
                            .align(Alignment.TopEnd)
                        )
                        {
                            Icon(imageVector = Icons.Default.Cameraswitch, contentDescription = "Camera switch", tint = Color.White)
                        }

                        Row(modifier = Modifier
                            .align(Alignment.BottomCenter),
                            horizontalArrangement = Arrangement.SpaceBetween)
                        {
                            IconButton(onClick = {
                                scope.launch {
                                    scaffoldState.bottomSheetState.expand()
                                }
                                takePhoto(controller, viewModel::onTakePhoto)
                                                 }, modifier = Modifier.padding(16.dp))
                            {
                                Icon(imageVector = Icons.Default.Camera, contentDescription = "Camera shutter", tint = Color.White)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    scaffoldState.bottomSheetState.expand()
                                }
                               }, modifier = Modifier.padding(16.dp))
                            {
                                Icon(imageVector = Icons.Default.BrowseGallery, contentDescription = "Gallery", tint = Color.White)
                            }
                            IconButton(onClick = {
                                takeVideo(controller)
                            }, modifier = Modifier.padding(16.dp))
                            {
                                Icon(imageVector = Icons.Default.Videocam, contentDescription = "Gallery", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun takePhoto(
        controller: LifecycleCameraController,
        onImageClicked: (Bitmap) -> Unit
    ){
        controller.takePicture(
            ContextCompat.getMainExecutor(applicationContext),
        object : ImageCapture.OnImageCapturedCallback(){
            override fun onCaptureSuccess(image: ImageProxy){
                super.onCaptureSuccess(image)
                val matrix = Matrix().apply {
                    postScale(-1f, 1f, image.width / 2f, image.height / 2f)
                    postRotate(image.imageInfo.rotationDegrees.toFloat() + 180f)
                }
                val rotatedBitmap = Bitmap.createBitmap(image.toBitmap(), 0, 0, image.width, image.height, matrix, true)
                onImageClicked(rotatedBitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                super.onError(exception)
                Log.e("Camera", "Couldn't take photo: ", exception)
            }
        })
    }

    private fun takeVideo(
        controller: LifecycleCameraController
    ){
        if(recording != null){
            recording?.stop()
            recording = null
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())

        val contentVales = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MyCameraApp")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentVales).build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        recording = controller.startRecording(
            mediaStoreOutputOptions,
            AudioConfig.create(true),
            ContextCompat.getMainExecutor(applicationContext),
        ){event ->
            when(event){
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()){
                        Log.e("Camera", "Video recording error: ${event.error}")
                        recording?.close()
                        recording = null
                    }else{
                        Toast.makeText(applicationContext, "Video saved", Toast.LENGTH_LONG).show()
                        updateMediaStore(event.outputResults.outputUri)
                    }
                }
            }
        }
    }

    private fun updateMediaStore(outputUri: Uri) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        contentResolver.update(outputUri, contentValues, null, null)
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}


