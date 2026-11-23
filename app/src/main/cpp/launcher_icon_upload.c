#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>

JNIEXPORT jobject JNICALL
Java_dev_ryuya_oneuihometweaks_LauncherIconCpuUpload_uploadPremultiplied(
        JNIEnv *env, jobject uploader, jobject pixels, jint width, jint height, jint source_stride,
        jboolean opaque, jint color_scale) {
    (void) uploader;
    if (width < 1 || width > 512 || height < 1 || height > 512 || source_stride < width * 4 ||
            color_scale < 0 || color_scale > 255) {
        return NULL;
    }
    const uint8_t *source = (*env)->GetDirectBufferAddress(env, pixels);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, pixels);
    if (source == NULL || capacity < (jlong) source_stride * (height - 1) + width * 4) {
        return NULL;
    }
    AHardwareBuffer_Desc destination = {
        .width = (uint32_t) width, .height = (uint32_t) height, .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY,
    };
    AHardwareBuffer *buffer = NULL;
    if (AHardwareBuffer_allocate(&destination, &buffer) != 0) {
        return NULL;
    }
    AHardwareBuffer_describe(buffer, &destination);
    if (destination.stride < (uint32_t) width) {
        AHardwareBuffer_release(buffer);
        return NULL;
    }
    void *destination_pixels = NULL;
    int locked = AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, NULL, &destination_pixels);
    jobject result = NULL;
    if (locked == 0) {
        for (jint row = 0; row < height; row++) {
            const uint8_t *input = source + (size_t) row * source_stride;
            uint8_t *output = (uint8_t *) destination_pixels + (size_t) row * destination.stride * 4;
            if (color_scale == 255) {
                memcpy(output, input, (size_t) width * 4);
                if (opaque) {
                    for (jint pixel = 0; pixel < width; pixel++) {
                        output[pixel * 4 + 3] = 255;
                    }
                }
            } else {
                // Black SRC_ATOP scales the three premultiplied color channels and preserves alpha.
                for (jint pixel = 0; pixel < width; pixel++) {
                    const jint offset = pixel * 4;
                    for (jint channel = 0; channel < 3; channel++) {
                        output[offset + channel] = (uint8_t) ((input[offset + channel] * color_scale + 127) / 255);
                    }
                    output[offset + 3] = opaque ? 255 : input[offset + 3];
                }
            }
        }
        // Without an output fence, unlock returns once the buffer can be read by the compositor.
        if (AHardwareBuffer_unlock(buffer, NULL) == 0) {
            result = AHardwareBuffer_toHardwareBuffer(env, buffer);
        }
    }
    AHardwareBuffer_release(buffer);
    return result;
}
