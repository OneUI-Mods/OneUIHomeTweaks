package dev.ryuya.oneuihometweaks

import android.graphics.SurfaceTexture
import android.opengl.EGL14.*
import android.opengl.EGLConfig
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import android.os.Handler
import android.os.Trace
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Draws the wallpaper stream into the dock's compositor surface, with no GPU readback. */
internal class GlassDockRenderer : AutoCloseable {
    data class Shape(
        val width: Int,
        val height: Int,
        val leftInSource: Float,
        val topInSource: Float,
        val screenScaleX: Float,
        val screenScaleY: Float,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val corner: Float,
        val density: Float,
        val strength: Float,
        val dark: Boolean,
        val edgeMix: Float = 0f,
    )

    private val vertices = ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }
    private val transform = FloatArray(16)
    private var display = EGL_NO_DISPLAY
    private var context = EGL_NO_CONTEXT
    private var target = EGL_NO_SURFACE
    private var initialized = false
    private var input: SurfaceTexture? = null
    private var stream: Surface? = null
    private var program = 0
    private var texture = 0
    private var hasFrame = false
    private var lastTimestamp = Long.MIN_VALUE
    private var position = -1
    private var textureTransform = -1
    private var dockBounds = -1
    private var sourceSize = -1
    private var screenScale = -1
    private var material = -1

    fun start(output: Surface, width: Int, height: Int): Surface {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
        check(display != EGL_NO_DISPLAY && eglInitialize(display, null, 0, null, 0)) { "EGL display" }
        initialized = true
        val attributes = intArrayOf(
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "EGL config" }
        val config = checkNotNull(configs[0])
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE), 0)
        check(context != EGL_NO_CONTEXT) { "EGL context" }
        target = eglCreateWindowSurface(display, config, output, intArrayOf(EGL_NONE), 0)
        check(target != EGL_NO_SURFACE && eglMakeCurrent(display, target, target, context)) { "EGL target" }
        eglSwapInterval(display, 0)

        program = linkProgram()
        position = glGetAttribLocation(program, "position")
        textureTransform = glGetUniformLocation(program, "textureTransform")
        dockBounds = glGetUniformLocation(program, "dockBounds")
        sourceSize = glGetUniformLocation(program, "sourceSize")
        screenScale = glGetUniformLocation(program, "screenScale")
        material = glGetUniformLocation(program, "material")
        val names = IntArray(1)
        glGenTextures(1, names, 0)
        texture = names[0]
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val source = SurfaceTexture(texture).also { input = it }
        source.setDefaultBufferSize(width, height)
        return Surface(source).also { stream = it }
    }

    fun observe(handler: Handler, frameAvailable: () -> Unit) {
        input?.setOnFrameAvailableListener({ frameAvailable() }, handler)
    }

    fun draw(shape: Shape, newFrame: Boolean): Boolean {
        val source = input ?: return false
        if (newFrame) {
            source.updateTexImage()
            val timestamp = source.timestamp
            if (hasFrame && timestamp == lastTimestamp) return false
            lastTimestamp = timestamp
            source.getTransformMatrix(transform)
            hasFrame = true
        }
        if (!hasFrame) return false
        Trace.beginSection(if (newFrame) "GlassDockStream.frame" else "GlassDockStream.style")
        try {
            glViewport(0, 0, shape.width, shape.height)
            glUseProgram(program)
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture)
            glUniformMatrix4fv(textureTransform, 1, false, transform, 0)
            glUniform4f(dockBounds, shape.leftInSource, shape.topInSource, shape.width.toFloat(), shape.height.toFloat())
            glUniform2f(sourceSize, shape.sourceWidth.toFloat(), shape.sourceHeight.toFloat())
            glUniform2f(screenScale, shape.screenScaleX, shape.screenScaleY)
            glUniform4f(material, shape.corner, shape.density, shape.strength, shape.edgeMix)
            glEnableVertexAttribArray(position)
            glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, vertices)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            check(eglSwapBuffers(display, target)) { "EGL swap" }
            return true
        } finally {
            Trace.endSection()
        }
    }

    override fun close() {
        input?.setOnFrameAvailableListener(null)
        stream?.release()
        input?.release()
        input = null
        stream = null
        if (!initialized) return
        if (program != 0) glDeleteProgram(program)
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        if (target != EGL_NO_SURFACE) eglDestroySurface(display, target)
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context)
        eglTerminate(display)
        eglReleaseThread()
        initialized = false
    }

    private fun linkProgram(): Int {
        val vertex = compile(GL_VERTEX_SHADER, VERTEX)
        val fragment = try {
            compile(GL_FRAGMENT_SHADER, FRAGMENT)
        } catch (error: RuntimeException) {
            glDeleteShader(vertex)
            throw error
        }
        val result = glCreateProgram()
        try {
            glAttachShader(result, vertex)
            glAttachShader(result, fragment)
            glLinkProgram(result)
            val linked = IntArray(1)
            glGetProgramiv(result, GL_LINK_STATUS, linked, 0)
            check(linked[0] == GL_TRUE) { glGetProgramInfoLog(result) }
            return result
        } catch (error: RuntimeException) {
            glDeleteProgram(result)
            throw error
        } finally {
            glDeleteShader(vertex)
            glDeleteShader(fragment)
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        val compiled = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GL_TRUE) {
            val message = glGetShaderInfoLog(shader)
            glDeleteShader(shader)
            error(message)
        }
        return shader
    }

    private companion object {
        const val VERTEX = """
            attribute vec2 position;
            varying highp vec2 uv;
            void main() {
                uv = vec2(position.x + 1.0, 1.0 - position.y) * 0.5;
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        // These are the dock optics from GlassLens, evaluated on an external GPU texture.
        const val FRAGMENT = """#extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 uv;
            uniform samplerExternalOES wallpaper;
            uniform mat4 textureTransform;
            uniform vec4 dockBounds;
            uniform vec2 sourceSize;
            uniform vec2 screenScale;
            uniform vec4 material;

            vec3 sampleWallpaper(vec2 p) {
                vec2 position = clamp((p * screenScale + dockBounds.xy) / sourceSize, 0.0, 1.0);
                vec4 coord = textureTransform * vec4(position.x, 1.0 - position.y, 0.0, 1.0);
                return texture2D(wallpaper, coord.xy).rgb;
            }

            $GLASS_CIRCULAR_BEVEL

            void main() {
                vec2 p = uv * dockBounds.zw;
                vec2 halfSize = dockBounds.zw * 0.5;
                vec2 local = p - halfSize;
                vec2 corner = abs(local) - halfSize + material.x;
                vec2 outward = max(corner, 0.0);
                float distanceToEdge = length(outward) + min(max(corner.x, corner.y), 0.0) - material.x;
                if (distanceToEdge >= 0.5) {
                    gl_FragColor = vec4(0.0);
                    return;
                }
                if (dot(outward, outward) < 0.0001) {
                    outward = corner.x > corner.y ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
                }
                vec2 normal = normalize(outward) * sign(local);
                float depth = max(0.0, -distanceToEdge);
                float density = material.y;
                float strength = material.z;
                float limit = min(halfSize.x, halfSize.y) * 0.88;
                float innerHeight = min($GLASS_DOCK_BEVEL_DP * density, limit);
                float outerHeight = min(12.0 * density, limit);
                if (depth >= innerHeight || material.w <= 0.0) {
                    gl_FragColor = vec4(0.0);
                    return;
                }
                float inner = circularBevel(depth, innerHeight) * 19.0 * density * strength;
                float outer = circularBevel(depth, outerHeight) * 34.0 * density * strength;
                vec2 face = p + normal * inner;
                float spread = density * (0.45 + 1.5 * strength);
                vec3 color = sampleWallpaper(face) * 0.62;
                color += (sampleWallpaper(face + vec2(spread, 0.0)) +
                    sampleWallpaper(face - vec2(spread, 0.0)) +
                    sampleWallpaper(face + vec2(0.0, spread)) +
                    sampleWallpaper(face - vec2(0.0, spread))) * 0.095;
                float edgeMix = clamp(1.0 - depth / max(outerHeight, 0.5), 0.0, 1.0);
                if (edgeMix > 0.0) {
                    vec2 edge = p + normal * outer;
                    vec2 fringe = normal * (1.6 * density * edgeMix * strength);
                    vec3 refracted = vec3(sampleWallpaper(edge + fringe).r, sampleWallpaper(edge).g,
                        sampleWallpaper(edge - fringe).b);
                    color = mix(color, refracted, edgeMix * strength);
                }
                float edgeOpacity = 1.0 - smoothstep(outerHeight * 0.33, innerHeight, depth);
                float alpha = clamp(0.5 - distanceToEdge, 0.0, 1.0) * min(1.0, strength / 0.45)
                    * edgeOpacity * material.w;
                gl_FragColor = vec4(color * alpha, alpha);
            }
        """
    }
}
