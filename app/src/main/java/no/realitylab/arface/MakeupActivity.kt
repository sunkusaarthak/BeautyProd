package no.realitylab.arface

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.*
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.sceneform.rendering.Renderable
import com.google.ar.sceneform.rendering.Texture
import com.google.ar.sceneform.ux.AugmentedFaceNode
import kotlinx.android.synthetic.main.activity_makeup.*
import java.io.File
import java.io.FileDescriptor.out
import java.io.FileOutputStream
import java.io.IOException


@Suppress("DEPRECATION")
class MakeupActivity : AppCompatActivity() {

    private var mWidth = 0
    private var mHeight = 0
    private var capturePicture = false

    companion object {
        const val MIN_OPENGL_VERSION = 3.0
    }

    lateinit var arFragment: FaceArFragment
    private var faceMeshTexture: Texture? = null
    var faceNodeMap = HashMap<AugmentedFace, AugmentedFaceNode>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!checkIsSupportedDeviceOrFinish()) {
            return
        }

        setContentView(R.layout.activity_makeup)
        arFragment = face_fragment as FaceArFragment
        Texture.builder()
            .setSource(this, R.drawable.htf)
            .build()
            .thenAccept { texture -> faceMeshTexture = texture }

        val sceneView = arFragment.arSceneView
        sceneView.cameraStreamRenderPriority = Renderable.RENDER_PRIORITY_FIRST
        val scene = sceneView.scene

        scene.addOnUpdateListener {
            faceMeshTexture.let {
                sceneView.session
                    ?.getAllTrackables(AugmentedFace::class.java)?.let {
                        for (f in it) {
                            if (!faceNodeMap.containsKey(f)) {
                                val faceNode = AugmentedFaceNode(f)
                                faceNode.setParent(scene)
                                faceNode.faceMeshTexture = faceMeshTexture
                                faceNodeMap.put(f, faceNode)
                            }
                        }
                        // Remove any AugmentedFaceNodes associated with an AugmentedFace that stopped tracking.
                        val iter = faceNodeMap.entries.iterator()
                        while (iter.hasNext()) {
                            val entry = iter.next()
                            val face = entry.key
                            if (face.trackingState == TrackingState.STOPPED) {
                                val faceNode = entry.value
                                faceNode.setParent(null)
                                iter.remove()
                            }
                        }
                    }
            }

            if (capturePicture) {
                capturePicture = false;
                SavePicture();
            }
        }
    }


    fun checkIsSupportedDeviceOrFinish() : Boolean {
        if (ArCoreApk.getInstance().checkAvailability(this) == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            Toast.makeText(this, "Augmented Faces requires ARCore", Toast.LENGTH_LONG).show()
            finish()
            return false
        }
        val openGlVersionString =  (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.deviceConfigurationInfo
            ?.glEsVersion

        openGlVersionString?.let { s ->
            if (java.lang.Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
                Toast.makeText(this, "Sceneform requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG)
                    .show()
                finish()
                return false
            }
        }
        return true
    }

    val REQUIRED_PERMISSIONS = arrayOf<String>(
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.CAMERA
    )

    /**
     * Check to see we have the necessary permissions for this app.
     */
    fun hasCameraPermission(activity: Activity?): Boolean {
        for (p in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity!!, p) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Check to see we have the necessary permissions for this app,
     * and ask for them if we don't.
     */
    fun requestCameraPermission(activity: Activity?) {
        val CAMERA_PERMISSION_CODE = 200
        ActivityCompat.requestPermissions(
            activity!!, REQUIRED_PERMISSIONS,
            CAMERA_PERMISSION_CODE
        )
    }

    /**
     * Check to see if we need to show the rationale for this permission.
     */
    fun shouldShowRequestPermissionRationale(activity: Activity?): Boolean {
        for (p in REQUIRED_PERMISSIONS) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity!!, p)) {
                return true
            }
        }
        return false
    }

    fun onSavePicture(view: View?) {
        // Here just a set a flag so we can copy
        // the image from the onDrawFrame() method.
        // This is required for OpenGL so we are on the rendering thread.
        if(!hasCameraPermission(this)) {
            requestCameraPermission(this)
            shouldShowRequestPermissionRationale(this)
        }
        this.capturePicture = true
    }

    /**
     * Call from the GLThread to save a picture of the current frame.
     */
    @Throws(IOException::class)
    open fun SavePicture() {

        // Create a file in the Pictures/HelloAR album.
        val out = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            ).toString() + "/HelloAR", "Img" +
                    java.lang.Long.toHexString(System.currentTimeMillis()) + ".png"
        )

        // Make sure the directory exists
        if (!out.parentFile.exists()) {
            out.parentFile.mkdirs()
        }


        var frag :View = findViewById(R.id.fragment_main)
        var bmp : Bitmap = takeScreenshotOfRootView(frag)
        getScreenShotFromView(frag, this) {
            bmp = it
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

        } else {
            bmp = getScreenShot(frag)
        }
        // Write it to disk.
        storeImage(bmp, out)

    }

    fun storeImage(bmp : Bitmap, out : File) {
        val fos = FileOutputStream(out)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        fos.flush()
        fos.close()
        runOnUiThread { Toast.makeText(this, "Img Save!", Toast.LENGTH_SHORT).show() }
    }

    // for api level 28
    fun getScreenShotFromView(view: View, activity: Activity, callback: (Bitmap) -> Unit) {
        activity.window?.let { window ->
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val locationOfViewInWindow = IntArray(2)
            view.getLocationInWindow(locationOfViewInWindow)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PixelCopy.request(
                        window,
                        Rect(
                            locationOfViewInWindow[0],
                            locationOfViewInWindow[1],
                            locationOfViewInWindow[0] + view.width,
                            locationOfViewInWindow[1] + view.height
                        ), bitmap, { copyResult ->
                            if (copyResult == PixelCopy.SUCCESS) {
                                callback(bitmap) }
                            else {

                            }
                            // possible to handle other result codes ...
                        },
                        Handler()
                    )
                }
            } catch (e: IllegalArgumentException) {
                // PixelCopy may throw IllegalArgumentException, make sure to handle it
                e.printStackTrace()
            }
        }
    }

    //deprecated version
/*  Method which will return Bitmap after taking screenshot. We have to pass the view which we want to take screenshot.  */
    fun getScreenShot(view: View): Bitmap {
        val screenView = view.rootView
        screenView.isDrawingCacheEnabled = true
        val bitmap = Bitmap.createBitmap(screenView.drawingCache)
        screenView.isDrawingCacheEnabled = false
        return bitmap
    }
    private fun takeScreenshot(view: View): Bitmap {
        view.isDrawingCacheEnabled = true
        view.buildDrawingCache(true)
        val b = Bitmap.createBitmap(view.drawingCache)
        view.isDrawingCacheEnabled = false
        return b
    }
    fun takeScreenshotOfRootView(v: View): Bitmap {
        return takeScreenshot(v.rootView)
    }
}
