#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <pthread.h>
#include <cstring>

#define TAG "KapCoreNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

extern "C" {
    int kap_client_start(const char* server, const char* psk, const char* uid, const char* hwid, const char* socks_addr, int behind_cdn, int debug, int downlink_workers, int uplink_pipeline, int insecure, int cookie_uplink);
    int kap_client_start_multi(const char* server, const char* endpoints_json, const char* endpoints_behind_cdn_json, const char* endpoints_cookie_uplink_json, const char* psk, const char* uid, const char* hwid, const char* socks_addr, int behind_cdn, int debug, int downlink_workers, int uplink_pipeline, int insecure, int cookie_uplink);
    void kap_client_stop();
    int kap_singbox_start(const char* config_json, int tun_fd);
    void kap_singbox_stop();
    int kap_convert_dat_to_srs(int is_geosite, const char* input_path, const char* output_path, const char* category);
    int kap_convert_dat_to_srs_all(int is_geosite, const char* input_path, const char* output_dir, const char* prefix);
}

static int pfd[2];
static pthread_t thr;
static const char* log_tag = "KapGoCore";

static void* logger_thread(void*) {
    ssize_t rdsz;
    char buf[1024];
    while ((rdsz = read(pfd[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[rdsz - 1] == '\n') --rdsz;
        buf[rdsz] = 0;
        __android_log_write(ANDROID_LOG_INFO, log_tag, buf);
    }
    return 0;
}

static int start_logger() {
    setvbuf(stdout, 0, _IOLBF, 0);
    setvbuf(stderr, 0, _IOLBF, 0);
    pipe(pfd);
    dup2(pfd[1], STDOUT_FILENO);
    dup2(pfd[1], STDERR_FILENO);
    if (pthread_create(&thr, 0, logger_thread, 0) != 0) return -1;
    pthread_detach(thr);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_unknkriod_kapclient_native_KapCore_start(
        JNIEnv* env,
        jobject /* thiz */,
        jstring serverUrl,
        jbyteArray psk,
        jstring uid,
        jstring hwid,
        jstring socks5Addr,
        jboolean behindCdn,
        jboolean debug,
        jint downlinkWorkers,
        jint uplinkPipeline,
        jboolean insecure,
        jboolean cookieUplink) {

    static bool logger_started = false;
    if (!logger_started) {
        start_logger();
        logger_started = true;
    }

    const char *nativeServerUrl = env->GetStringUTFChars(serverUrl, 0);
    const char *nativeUid = uid ? env->GetStringUTFChars(uid, 0) : nullptr;
    const char *nativeHwid = hwid ? env->GetStringUTFChars(hwid, 0) : nullptr;
    const char *nativeSocks5Addr = env->GetStringUTFChars(socks5Addr, 0);

    jbyte* pskBytes = env->GetByteArrayElements(psk, 0);
    int pskLen = env->GetArrayLength(psk);
    char* nativePsk = new char[pskLen + 1];
    std::memcpy(nativePsk, pskBytes, pskLen);
    nativePsk[pskLen] = '\0';

    int res = kap_client_start(
        nativeServerUrl,
        nativePsk,
        nativeUid,
        nativeHwid,
        nativeSocks5Addr,
        behindCdn ? 1 : 0,
        debug ? 1 : 0,
        downlinkWorkers,
        uplinkPipeline,
        insecure ? 1 : 0,
        cookieUplink ? 1 : 0
    );

    env->ReleaseStringUTFChars(serverUrl, nativeServerUrl);
    if (nativeUid) env->ReleaseStringUTFChars(uid, nativeUid);
    if (nativeHwid) env->ReleaseStringUTFChars(hwid, nativeHwid);
    env->ReleaseStringUTFChars(socks5Addr, nativeSocks5Addr);
    env->ReleaseByteArrayElements(psk, pskBytes, JNI_ABORT);
    delete[] nativePsk;

    return res;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_unknkriod_kapclient_native_KapCore_startMulti(
        JNIEnv* env,
        jobject /* thiz */,
        jstring serverUrl,
        jstring endpointsJson,
        jstring endpointsBehindCdnJson,
        jstring endpointsCookieUplinkJson,
        jbyteArray psk,
        jstring uid,
        jstring hwid,
        jstring socks5Addr,
        jboolean behindCdn,
        jboolean debug,
        jint downlinkWorkers,
        jint uplinkPipeline,
        jboolean insecure,
        jboolean cookieUplink) {

    static bool logger_started = false;
    if (!logger_started) {
        start_logger();
        logger_started = true;
    }

    const char *nativeServerUrl = env->GetStringUTFChars(serverUrl, 0);
    const char *nativeEndpointsJson = endpointsJson ? env->GetStringUTFChars(endpointsJson, 0) : nullptr;
    const char *nativeEndpointsBehindCdnJson = endpointsBehindCdnJson ? env->GetStringUTFChars(endpointsBehindCdnJson, 0) : nullptr;
    const char *nativeEndpointsCookieUplinkJson = endpointsCookieUplinkJson ? env->GetStringUTFChars(endpointsCookieUplinkJson, 0) : nullptr;
    const char *nativeUid = uid ? env->GetStringUTFChars(uid, 0) : nullptr;
    const char *nativeHwid = hwid ? env->GetStringUTFChars(hwid, 0) : nullptr;
    const char *nativeSocks5Addr = env->GetStringUTFChars(socks5Addr, 0);

    jbyte* pskBytes = env->GetByteArrayElements(psk, 0);
    int pskLen = env->GetArrayLength(psk);
    char* nativePsk = new char[pskLen + 1];
    std::memcpy(nativePsk, pskBytes, pskLen);
    nativePsk[pskLen] = '\0';

    int res = kap_client_start_multi(
        nativeServerUrl,
        nativeEndpointsJson,
        nativeEndpointsBehindCdnJson,
        nativeEndpointsCookieUplinkJson,
        nativePsk,
        nativeUid,
        nativeHwid,
        nativeSocks5Addr,
        behindCdn ? 1 : 0,
        debug ? 1 : 0,
        downlinkWorkers,
        uplinkPipeline,
        insecure ? 1 : 0,
        cookieUplink ? 1 : 0
    );

    env->ReleaseStringUTFChars(serverUrl, nativeServerUrl);
    if (nativeEndpointsJson) env->ReleaseStringUTFChars(endpointsJson, nativeEndpointsJson);
    if (nativeEndpointsBehindCdnJson) env->ReleaseStringUTFChars(endpointsBehindCdnJson, nativeEndpointsBehindCdnJson);
    if (nativeEndpointsCookieUplinkJson) env->ReleaseStringUTFChars(endpointsCookieUplinkJson, nativeEndpointsCookieUplinkJson);
    if (nativeUid) env->ReleaseStringUTFChars(uid, nativeUid);
    if (nativeHwid) env->ReleaseStringUTFChars(hwid, nativeHwid);
    env->ReleaseStringUTFChars(socks5Addr, nativeSocks5Addr);
    env->ReleaseByteArrayElements(psk, pskBytes, JNI_ABORT);
    delete[] nativePsk;

    return res;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_unknkriod_kapclient_native_KapCore_stop(
        JNIEnv* env,
        jobject /* thiz */) {
    LOGD("JNI: Stopping KAP Core");
    kap_client_stop();
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_unknkriod_kapclient_native_KapCore_startSingBox(
        JNIEnv* env,
        jobject /* thiz */,
        jstring configJson,
        jint tunFd) {
    const char *nativeConfigJson = env->GetStringUTFChars(configJson, 0);
    int res = kap_singbox_start(nativeConfigJson, tunFd);
    env->ReleaseStringUTFChars(configJson, nativeConfigJson);
    return res;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_unknkriod_kapclient_native_KapCore_stopSingBox(
        JNIEnv* env,
        jobject /* thiz */) {
    kap_singbox_stop();
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_unknkriod_kapclient_native_KapCore_getStats(
        JNIEnv* env,
        jobject /* thiz */) {
    return env->NewStringUTF("{\"status\": \"running\"}");
}

extern "C" JNIEXPORT jint JNICALL
Java_me_unknkriod_kapclient_native_KapCore_convertDatToSrs(
        JNIEnv* env,
        jobject /* thiz */,
        jboolean isGeosite,
        jstring inputPath,
        jstring outputPath,
        jstring category) {
    const char *nativeInputPath = env->GetStringUTFChars(inputPath, 0);
    const char *nativeOutputPath = env->GetStringUTFChars(outputPath, 0);
    const char *nativeCategory = env->GetStringUTFChars(category, 0);

    int res = kap_convert_dat_to_srs(isGeosite ? 1 : 0, nativeInputPath, nativeOutputPath, nativeCategory);

    env->ReleaseStringUTFChars(inputPath, nativeInputPath);
    env->ReleaseStringUTFChars(outputPath, nativeOutputPath);
    env->ReleaseStringUTFChars(category, nativeCategory);
    return res;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_unknkriod_kapclient_native_KapCore_convertDatToSrsAll(
        JNIEnv* env,
        jobject /* thiz */,
        jboolean isGeosite,
        jstring inputPath,
        jstring outputDir,
        jstring prefix) {
    const char *nativeInputPath = env->GetStringUTFChars(inputPath, 0);
    const char *nativeOutputDir = env->GetStringUTFChars(outputDir, 0);
    const char *nativePrefix = env->GetStringUTFChars(prefix, 0);

    int res = kap_convert_dat_to_srs_all(isGeosite ? 1 : 0, nativeInputPath, nativeOutputDir, nativePrefix);

    env->ReleaseStringUTFChars(inputPath, nativeInputPath);
    env->ReleaseStringUTFChars(outputDir, nativeOutputDir);
    env->ReleaseStringUTFChars(prefix, nativePrefix);
    return res;
}
