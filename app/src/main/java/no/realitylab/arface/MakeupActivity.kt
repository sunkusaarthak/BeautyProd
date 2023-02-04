package no.realitylab.arface

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.Image
import android.opengl.GLES20
import android.os.Bundle
import android.os.Environment
import android.util.Log
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
import java.io.FileOutputStream
import java.io.IOException
import java.nio.IntBuffer


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
        val pixelData = IntArray(mWidth * mHeight)

        // Read the pixels from the current GL frame.
        val buf = IntBuffer.wrap(pixelData)
        buf.position(0)
        GLES20.glReadPixels(
            0, 0, mWidth, mHeight,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )

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

        // Convert the pixel data from RGBA to what Android wants, ARGB.
        val bitmapData = IntArray(pixelData.size)
        for (i in 0 until mHeight) {
            for (j in 0 until mWidth) {
                val p = pixelData[i * mWidth + j]
                val b = p and 0x00ff0000 shr 16
                val r = p and 0x000000ff shl 16
                val ga = p and -0xff0100
                bitmapData[(mHeight - i - 1) * mWidth + j] = ga or r or b
            }
        }
        // Create a bitmap.
        val bmp = Bitmap.createBitmap(
            bitmapData,
            mWidth, mHeight, Bitmap.Config.ARGB_8888
        )

        // Write it to disk.
        val fos = FileOutputStream(out)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        fos.flush()
        fos.close()
        runOnUiThread {  }
    }
}
