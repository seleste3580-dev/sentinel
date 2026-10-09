#include <jni.h>

extern "C" bool sentinel_coordinates_valid(double latitude, double longitude);

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_sentinel_app_SentinelNative_validCoordinates(JNIEnv*, jobject, jdouble latitude, jdouble longitude) {
    return sentinel_coordinates_valid(latitude, longitude) ? JNI_TRUE : JNI_FALSE;
}
