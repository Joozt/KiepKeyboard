#pragma once
#include <android/log.h>
// Read on the device with: adb logcat -v time -s kiep
#define KLOG(...) __android_log_print(ANDROID_LOG_INFO, "kiep", __VA_ARGS__)
#define KLOGW(...) __android_log_print(ANDROID_LOG_WARN, "kiep", __VA_ARGS__)
