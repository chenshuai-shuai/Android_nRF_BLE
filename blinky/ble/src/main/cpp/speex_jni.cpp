#include <jni.h>
#include <speex/speex_preprocess.h>
#include <speex/speexdsp_types.h>

struct SpeexStateHolder {
    SpeexPreprocessState *st;
    int frame_size;
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

    int noiseSuppress = -12; // dB (lighter to keep clarity)
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &noiseSuppress);

    int agc = 1;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC, &agc);

    float agcLevel = 12000.0f;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_LEVEL, &agcLevel);

    int agcMaxGain = 26; // dB
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_MAX_GAIN, &agcMaxGain);

    int inc = 12; // dB/s
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_INCREMENT, &inc);

    int dec = 12; // dB/s
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
