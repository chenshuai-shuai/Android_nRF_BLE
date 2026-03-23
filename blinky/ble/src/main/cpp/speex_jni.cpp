#include <jni.h>
#include <speex/speex_preprocess.h>
#include <speex/speex_resampler.h>
#include <speex/speexdsp_types.h>

struct SpeexStateHolder {
    SpeexPreprocessState *st;
    int frame_size;
};

struct SpeexResamplerHolder {
    SpeexResamplerState *st;
    int channels;
};

extern "C" JNIEXPORT jlong JNICALL
Java_no_nordicsemi_android_blinky_ble_SpeexDspProcessor_nativeCreate(
        JNIEnv *env, jobject /*thiz*/, jint sampleRate, jint frameSize) {
    if (frameSize <= 0 || sampleRate <= 0) {
        return 0;
    }
    SpeexPreprocessState *st = speex_preprocess_state_init(frameSize, sampleRate);
    if (!st) {
        return 0;
    }

    int denoise = 1;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_DENOISE, &denoise);

    int noiseSuppress = -20; // dB
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &noiseSuppress);

    int agc = 1;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC, &agc);

    float agcLevel = 20000.0f;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_LEVEL, &agcLevel);

    int agcMaxGain = 36; // dB
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_MAX_GAIN, &agcMaxGain);

    int inc = 18; // dB/s
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_INCREMENT, &inc);

    int dec = 14; // dB/s
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_DECREMENT, &dec);

    int dereverb = 0;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_DEREVERB, &dereverb);

    auto *holder = new SpeexStateHolder();
    holder->st = st;
    holder->frame_size = frameSize;
    return reinterpret_cast<jlong>(holder);
}

extern "C" JNIEXPORT void JNICALL
Java_no_nordicsemi_android_blinky_ble_SpeexDspProcessor_nativeDestroy(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    auto *holder = reinterpret_cast<SpeexStateHolder *>(handle);
    if (holder->st) {
        speex_preprocess_state_destroy(holder->st);
    }
    delete holder;
}

extern "C" JNIEXPORT void JNICALL
Java_no_nordicsemi_android_blinky_ble_SpeexDspProcessor_nativeProcess(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jshortArray frame) {
    if (handle == 0 || frame == nullptr) return;
    auto *holder = reinterpret_cast<SpeexStateHolder *>(handle);
    if (!holder->st) return;

    jsize len = env->GetArrayLength(frame);
    if (len != holder->frame_size) return;

    jshort *buf = env->GetShortArrayElements(frame, nullptr);
    if (!buf) return;
    speex_preprocess_run(holder->st, reinterpret_cast<spx_int16_t *>(buf));
    env->ReleaseShortArrayElements(frame, buf, 0);
}

extern "C" JNIEXPORT jlong JNICALL
Java_no_nordicsemi_android_blinky_ble_SpeexResampler_nativeCreate(
        JNIEnv *env, jobject /*thiz*/, jint inputRate, jint outputRate, jint channels, jint quality) {
    if (inputRate <= 0 || outputRate <= 0 || channels <= 0) {
        return 0;
    }
    int err = RESAMPLER_ERR_SUCCESS;
    SpeexResamplerState *st = speex_resampler_init(
            static_cast<spx_uint32_t>(channels),
            static_cast<spx_uint32_t>(inputRate),
            static_cast<spx_uint32_t>(outputRate),
            quality,
            &err);
    if (!st || err != RESAMPLER_ERR_SUCCESS) {
        if (st) {
            speex_resampler_destroy(st);
        }
        return 0;
    }
    speex_resampler_skip_zeros(st);
    auto *holder = new SpeexResamplerHolder();
    holder->st = st;
    holder->channels = channels;
    return reinterpret_cast<jlong>(holder);
}

extern "C" JNIEXPORT void JNICALL
Java_no_nordicsemi_android_blinky_ble_SpeexResampler_nativeDestroy(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    auto *holder = reinterpret_cast<SpeexResamplerHolder *>(handle);
    if (holder->st) {
        speex_resampler_destroy(holder->st);
    }
    delete holder;
}

extern "C" JNIEXPORT void JNICALL
Java_no_nordicsemi_android_blinky_ble_SpeexResampler_nativeReset(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    auto *holder = reinterpret_cast<SpeexResamplerHolder *>(handle);
    if (!holder->st) return;
    speex_resampler_reset_mem(holder->st);
    speex_resampler_skip_zeros(holder->st);
}

extern "C" JNIEXPORT jint JNICALL
Java_no_nordicsemi_android_blinky_ble_SpeexResampler_nativeProcess(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jshortArray input, jint inputSamples,
        jshortArray output, jint outputCapacity) {
    if (handle == 0 || input == nullptr || output == nullptr || inputSamples <= 0 || outputCapacity <= 0) {
        return 0;
    }
    auto *holder = reinterpret_cast<SpeexResamplerHolder *>(handle);
    if (!holder->st) return 0;

    jsize inArrayLen = env->GetArrayLength(input);
    jsize outArrayLen = env->GetArrayLength(output);
    if (inputSamples > inArrayLen || outputCapacity > outArrayLen) {
        return 0;
    }

    jshort *inBuf = env->GetShortArrayElements(input, nullptr);
    if (!inBuf) return 0;
    jshort *outBuf = env->GetShortArrayElements(output, nullptr);
    if (!outBuf) {
        env->ReleaseShortArrayElements(input, inBuf, JNI_ABORT);
        return 0;
    }

    spx_uint32_t inLen = static_cast<spx_uint32_t>(inputSamples);
    spx_uint32_t outLen = static_cast<spx_uint32_t>(outputCapacity);
    int err;
    if (holder->channels == 1) {
        err = speex_resampler_process_int(holder->st,
                                          0,
                                          reinterpret_cast<spx_int16_t *>(inBuf),
                                          &inLen,
                                          reinterpret_cast<spx_int16_t *>(outBuf),
                                          &outLen);
    } else {
        err = speex_resampler_process_interleaved_int(holder->st,
                                                      reinterpret_cast<spx_int16_t *>(inBuf),
                                                      &inLen,
                                                      reinterpret_cast<spx_int16_t *>(outBuf),
                                                      &outLen);
    }

    env->ReleaseShortArrayElements(input, inBuf, JNI_ABORT);
    env->ReleaseShortArrayElements(output, outBuf, 0);
    return err == RESAMPLER_ERR_SUCCESS ? static_cast<jint>(outLen) : 0;
}
