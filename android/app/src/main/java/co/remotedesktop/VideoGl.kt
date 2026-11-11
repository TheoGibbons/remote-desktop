package co.remotedesktop

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The OpenGL step between the mirrored screen and the video encoder
 * ([PhoneVideoEncoder]). Android mirrors the screen into [surface], which
 * feeds a texture; [draw] paints that texture onto the encoder's input.
 *
 * It is there so that the app chooses when a picture is encoded. Mirrored
 * straight into the encoder, every change of the screen would be encoded
 * whether or not the network could take it, and a still screen could never
 * be encoded again for a viewer that needs a keyframe.
 *
 * Everything here belongs to the thread that made it: the OpenGL context is
 * current on that thread from the constructor until [release]. It decides
 * nothing; a failure is thrown.
 *
 * @param output the encoder's input surface, [width] x [height].
 * @param sourceWidth the size the screen is mirrored at, which the texture's
 * buffers must be told or pictures arrive 1 x 1.
 * @param onPicture a new picture is in the texture; its time in nanoseconds
 * as Android stamped it. Called on [handler]'s thread, which must be this one.
 * @param onFailure the picture could not be taken into the texture.
 */
internal class VideoGl(
    output: Surface,
    private val width: Int,
    private val height: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    handler: Handler,
    private val onPicture: (timestampNs: Long) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var texture: SurfaceTexture? = null
    private val transform = FloatArray(16)
    private var released = false

    /** What the screen is mirrored into. */
    lateinit var surface: Surface
        private set

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize ${error()}" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val wanted = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                // A config a video encoder can read from.
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE)
            check(EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "No recordable EGL config ${error()}"
            }
            val config = configs[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext ${error()}" }
            window = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${error()}" }
            check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent ${error()}" }

            program = program()
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            checkGl("texture")

            val made = SurfaceTexture(textureId)
            texture = made
            made.setDefaultBufferSize(sourceWidth, sourceHeight)
            made.setOnFrameAvailableListener({
                if (released) return@setOnFrameAvailableListener
                try {
                    // Taking the picture is also what makes Android tell of the next one.
                    it.updateTexImage()
                } catch (e: RuntimeException) {
                    onFailure(e)
                    return@setOnFrameAvailableListener
                }
                onPicture(it.timestamp)
            }, handler)
            surface = Surface(made)
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    /** Paints the picture the texture holds onto the encoder's input, stamped [timestampNs]. */
    fun draw(timestampNs: Long) {
        check(!released) { "Released" }
        val source = checkNotNull(texture)
        source.getTransformMatrix(transform)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coordinate = GLES20.glGetAttribLocation(program, "aCoordinate")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, CORNERS)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, COORDINATES)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("draw")
        EGLExt.eglPresentationTimeANDROID(display, window, timestampNs)
        check(EGL14.eglSwapBuffers(display, window)) { "eglSwapBuffers ${error()}" }
    }

    /** Lets go of the context, the texture and [surface]. Safe to call twice, and on a half-made one. */
    fun release() {
        if (released) return
        released = true
        try {
            texture?.setOnFrameAvailableListener(null)
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
                    if (program != 0) GLES20.glDeleteProgram(program)
                    if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                }
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                // The display itself is the whole app's, and stays.
                EGL14.eglReleaseThread()
            }
        } catch (_: RuntimeException) {
            // Being let go of; there is nothing better to do with it.
        }
        try {
            if (::surface.isInitialized) surface.release()
        } catch (_: RuntimeException) {
        }
        texture?.release()
        texture = null
    }

    private fun program(): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val made = GLES20.glCreateProgram()
        check(made != 0) { "glCreateProgram" }
        GLES20.glAttachShader(made, vertex)
        GLES20.glAttachShader(made, fragment)
        GLES20.glLinkProgram(made)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(made, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "Link: ${GLES20.glGetProgramInfoLog(made)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return made
    }

    private fun shader(type: Int, source: String): Int {
        val made = GLES20.glCreateShader(type)
        check(made != 0) { "glCreateShader" }
        GLES20.glShaderSource(made, source)
        GLES20.glCompileShader(made)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(made, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { "Compile: ${GLES20.glGetShaderInfoLog(made)}" }
        return made
    }

    private fun checkGl(what: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "OpenGL error 0x${Integer.toHexString(error)} in $what" }
    }

    private fun error() = "0x${Integer.toHexString(EGL14.eglGetError())}"

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        /** The whole output, as a strip of two triangles, and the texture's corners to match. */
        val CORNERS = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val COORDINATES = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aCoordinate;
            uniform mat4 uTransform;
            varying vec2 vCoordinate;
            void main() {
                gl_Position = aPosition;
                vCoordinate = (uTransform * aCoordinate).xy;
            }"""

        // High precision where the phone has it: at medium, a picture 2,340 pixels
        // tall is sampled a fraction of a pixel off, and text softens.
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vCoordinate;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vCoordinate);
            }"""
    }
}
