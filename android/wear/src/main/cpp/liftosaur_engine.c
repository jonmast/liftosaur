// JNI bridge to the Liftosaur watch bundle running in quickjs-ng.
//
// Derived from the measured spike harness (.scratch/wearos-port/spike/jni/jnibench.c), with
// the benchmarking scaffolding removed and logging redirected from stdout to logcat.
//
// Threading contract (spec §2.2): the runtime and context are owned by ONE thread for the
// process lifetime — the Kotlin side guarantees this via a single-threaded dispatcher. No
// locking here, because there is nothing to lock against; entering from a second thread is a
// bug in the caller, not a case to defend.
//
// Marshalling contract (spec §2.3): storage crosses as byte[], never jstring. This is a
// correctness decision, not a speed one — ART's jstring path corrupts embedded NUL in both
// directions, and marshalling was measured at 0.3% of call cost either way.

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>

#include "quickjs.h"

#define LOG_TAG "LiftosaurEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JSRuntime *g_rt = NULL;
static JSContext *g_ctx = NULL;

// console.* from JS lands here and is forwarded to logcat.
static JSValue native_log(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
  (void)this_val;
  const char *level = argc > 0 ? JS_ToCString(ctx, argv[0]) : NULL;
  const char *msg = argc > 1 ? JS_ToCString(ctx, argv[1]) : NULL;
  int prio = ANDROID_LOG_INFO;
  if (level) {
    if (strcmp(level, "error") == 0) {
      prio = ANDROID_LOG_ERROR;
    } else if (strcmp(level, "warn") == 0) {
      prio = ANDROID_LOG_WARN;
    } else if (strcmp(level, "debug") == 0) {
      prio = ANDROID_LOG_DEBUG;
    }
  }
  __android_log_print(prio, "LiftosaurJS", "%s", msg ? msg : "");
  if (level) {
    JS_FreeCString(ctx, level);
  }
  if (msg) {
    JS_FreeCString(ctx, msg);
  }
  return JS_UNDEFINED;
}

// Formats a pending JS exception (message + stack) into a caller-owned malloc'd string.
static char *take_exception_text(JSContext *ctx, const char *stage) {
  JSValue exc = JS_GetException(ctx);
  const char *str = JS_ToCString(ctx, exc);

  const char *stack_str = NULL;
  JSValue stack = JS_GetPropertyStr(ctx, exc, "stack");
  if (!JS_IsUndefined(stack) && !JS_IsException(stack)) {
    stack_str = JS_ToCString(ctx, stack);
  }

  size_t need = strlen(stage) + 4 + (str ? strlen(str) : 12) + (stack_str ? strlen(stack_str) + 2 : 0) + 1;
  char *out = malloc(need);
  if (out) {
    if (stack_str) {
      snprintf(out, need, "%s: %s\n%s", stage, str ? str : "(unprintable)", stack_str);
    } else {
      snprintf(out, need, "%s: %s", stage, str ? str : "(unprintable)");
    }
  }

  LOGE("%s", out ? out : "exception (allocation failed)");

  if (stack_str) {
    JS_FreeCString(ctx, stack_str);
  }
  if (str) {
    JS_FreeCString(ctx, str);
  }
  JS_FreeValue(ctx, stack);
  JS_FreeValue(ctx, exc);
  return out;
}

static void throw_java(JNIEnv *env, const char *msg) {
  jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
  if (cls) {
    (*env)->ThrowNew(env, cls, msg ? msg : "engine error");
  }
}

static void throw_java_owned(JNIEnv *env, char *msg) {
  throw_java(env, msg);
  free(msg);
}

static jbyteArray cstr_to_jbytes(JNIEnv *env, const char *buf, size_t len) {
  jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
  if (!out) {
    return NULL;
  }
  (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)buf);
  return out;
}

JNIEXPORT jboolean JNICALL Java_com_liftosaur_wear_engine_LiftosaurEngine_nativeInit(
    JNIEnv *env, jclass cls, jbyteArray prelude, jbyteArray bundle, jlong memoryLimitBytes) {
  (void)cls;
  if (g_rt) {
    return JNI_TRUE;
  }

  jsize plen = (*env)->GetArrayLength(env, prelude);
  jsize blen = (*env)->GetArrayLength(env, bundle);
  jbyte *pbuf = (*env)->GetByteArrayElements(env, prelude, NULL);
  jbyte *bbuf = (*env)->GetByteArrayElements(env, bundle, NULL);
  if (!pbuf || !bbuf) {
    if (pbuf) {
      (*env)->ReleaseByteArrayElements(env, prelude, pbuf, JNI_ABORT);
    }
    if (bbuf) {
      (*env)->ReleaseByteArrayElements(env, bundle, bbuf, JNI_ABORT);
    }
    throw_java(env, "could not access prelude/bundle bytes");
    return JNI_FALSE;
  }

  LOGI("init: prelude=%d bytes bundle=%d bytes ptr=%zu JSValue=%zu", (int)plen, (int)blen,
       sizeof(void *), sizeof(JSValue));

  g_rt = JS_NewRuntime();
  if (!g_rt) {
    (*env)->ReleaseByteArrayElements(env, prelude, pbuf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, bundle, bbuf, JNI_ABORT);
    throw_java(env, "JS_NewRuntime failed");
    return JNI_FALSE;
  }

  // Bounds malloc_size (~3MB required in practice), not RSS. Never trips in normal use;
  // converts a runaway into a clean error envelope instead of an OOM kill (ticket 12).
  if (memoryLimitBytes > 0) {
    JS_SetMemoryLimit(g_rt, (size_t)memoryLimitBytes);
  }

  g_ctx = JS_NewContext(g_rt);
  if (!g_ctx) {
    (*env)->ReleaseByteArrayElements(env, prelude, pbuf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, bundle, bbuf, JNI_ABORT);
    JS_FreeRuntime(g_rt);
    g_rt = NULL;
    throw_java(env, "JS_NewContext failed");
    return JNI_FALSE;
  }

  JSValue global = JS_GetGlobalObject(g_ctx);
  JS_SetPropertyStr(g_ctx, global, "__native_log",
                    JS_NewCFunction(g_ctx, native_log, "__native_log", 2));
  JS_FreeValue(g_ctx, global);

  JSValue pres =
      JS_Eval(g_ctx, (const char *)pbuf, (size_t)plen, "prelude.js", JS_EVAL_TYPE_GLOBAL);
  (*env)->ReleaseByteArrayElements(env, prelude, pbuf, JNI_ABORT);
  if (JS_IsException(pres)) {
    JS_FreeValue(g_ctx, pres);
    (*env)->ReleaseByteArrayElements(env, bundle, bbuf, JNI_ABORT);
    throw_java_owned(env, take_exception_text(g_ctx, "prelude eval"));
    return JNI_FALSE;
  }
  JS_FreeValue(g_ctx, pres);

  JSValue bres =
      JS_Eval(g_ctx, (const char *)bbuf, (size_t)blen, "watch-bundle.js", JS_EVAL_TYPE_GLOBAL);
  (*env)->ReleaseByteArrayElements(env, bundle, bbuf, JNI_ABORT);
  if (JS_IsException(bres)) {
    JS_FreeValue(g_ctx, bres);
    throw_java_owned(env, take_exception_text(g_ctx, "bundle eval"));
    return JNI_FALSE;
  }
  JS_FreeValue(g_ctx, bres);

  // One GC immediately after eval reclaims ~73KB for 6-7ms. No periodic cadence after this —
  // measured to change nothing, because there is no JS garbage to collect (ticket 12).
  JS_RunGC(g_rt);

  // Alive check. `typeof Liftosaur` is "function", NOT "object" — a `=== "object"` port
  // rejects a working engine (ticket 04).
  const char *probe = "typeof Liftosaur";
  JSValue tres = JS_Eval(g_ctx, probe, strlen(probe), "check.js", JS_EVAL_TYPE_GLOBAL);
  const char *tstr = JS_ToCString(g_ctx, tres);
  int ok = tstr && strcmp(tstr, "undefined") != 0;
  LOGI("typeof Liftosaur = %s", tstr ? tstr : "(null)");
  if (tstr) {
    JS_FreeCString(g_ctx, tstr);
  }
  JS_FreeValue(g_ctx, tres);

  if (!ok) {
    throw_java(env, "Liftosaur global missing after bundle eval");
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

// Calls Liftosaur[method](storage, [storage2,] ...extraArgs) and returns the result as UTF-8
// bytes. `storage` is the opaque storage JSON; `storage2` is the second storage payload the
// sync methods take (prepareSync's lastSynced, mergeStorage's incoming) and is omitted from
// argv entirely when null; `extraArgsJson` is a JSON array spread into the remaining argv.
//
// storage2 gets its own byte[] parameter rather than riding inside extraArgsJson because it is
// a full storage document: embedding it would mean escaping ~34KB of JSON into a JSON string
// and parsing it twice, and it would put storage back on a lossy text path for no gain.
JNIEXPORT jbyteArray JNICALL Java_com_liftosaur_wear_engine_LiftosaurEngine_nativeCall(
    JNIEnv *env, jclass cls, jstring jmethod, jbyteArray storage, jbyteArray storage2,
    jstring jextra) {
  (void)cls;
  if (!g_ctx) {
    throw_java(env, "engine not initialized");
    return NULL;
  }

  const char *method = (*env)->GetStringUTFChars(env, jmethod, NULL);
  if (!method) {
    throw_java(env, "bad method name");
    return NULL;
  }
  const char *extra = jextra ? (*env)->GetStringUTFChars(env, jextra, NULL) : NULL;

  JSValue global = JS_GetGlobalObject(g_ctx);
  JSValue lft = JS_GetPropertyStr(g_ctx, global, "Liftosaur");
  JS_FreeValue(g_ctx, global);
  if (JS_IsUndefined(lft) || JS_IsException(lft)) {
    JS_FreeValue(g_ctx, lft);
    (*env)->ReleaseStringUTFChars(env, jmethod, method);
    if (extra) {
      (*env)->ReleaseStringUTFChars(env, jextra, extra);
    }
    throw_java(env, "Liftosaur global missing");
    return NULL;
  }

  JSValue fn = JS_GetPropertyStr(g_ctx, lft, method);
  if (!JS_IsFunction(g_ctx, fn)) {
    char msg[192];
    snprintf(msg, sizeof(msg), "Liftosaur.%s is not a function", method);
    JS_FreeValue(g_ctx, fn);
    JS_FreeValue(g_ctx, lft);
    (*env)->ReleaseStringUTFChars(env, jmethod, method);
    if (extra) {
      (*env)->ReleaseStringUTFChars(env, jextra, extra);
    }
    throw_java(env, msg);
    return NULL;
  }

  // Storage crosses as bytes and becomes a JS string here; JS_NewStringLen is length-counted,
  // so embedded NUL survives.
  jsize slen = (*env)->GetArrayLength(env, storage);
  jbyte *sbuf = (*env)->GetByteArrayElements(env, storage, NULL);
  JSValue sval = JS_NewStringLen(g_ctx, (const char *)sbuf, (size_t)slen);
  (*env)->ReleaseByteArrayElements(env, storage, sbuf, JNI_ABORT);

  int argc = 1;
  JSValue argv[10];
  argv[0] = sval;

  if (storage2) {
    jsize s2len = (*env)->GetArrayLength(env, storage2);
    jbyte *s2buf = (*env)->GetByteArrayElements(env, storage2, NULL);
    argv[argc++] = JS_NewStringLen(g_ctx, (const char *)s2buf, (size_t)s2len);
    (*env)->ReleaseByteArrayElements(env, storage2, s2buf, JNI_ABORT);
  }

  int extra_count = 0;
  int bad_extra = 0;

  if (extra && extra[0]) {
    JSValue arr = JS_ParseJSON(g_ctx, extra, strlen(extra), "args.json");
    if (JS_IsException(arr)) {
      JS_FreeValue(g_ctx, arr);
      bad_extra = 1;
    } else {
      int64_t len = 0;
      JS_GetLength(g_ctx, arr, &len);
      int64_t room = (int64_t)((int)(sizeof(argv) / sizeof(argv[0])) - argc);
      if (len > room) {
        len = room;
      }
      for (int64_t i = 0; i < len; i++) {
        argv[argc + extra_count] = JS_GetPropertyUint32(g_ctx, arr, (uint32_t)i);
        extra_count++;
      }
      JS_FreeValue(g_ctx, arr);
    }
  }

  (*env)->ReleaseStringUTFChars(env, jmethod, method);
  if (extra) {
    (*env)->ReleaseStringUTFChars(env, jextra, extra);
  }

  if (bad_extra) {
    for (int i = 0; i < argc; i++) {
      JS_FreeValue(g_ctx, argv[i]);
    }
    JS_FreeValue(g_ctx, fn);
    JS_FreeValue(g_ctx, lft);
    throw_java_owned(env, take_exception_text(g_ctx, "extra args parse"));
    return NULL;
  }

  JSValue res = JS_Call(g_ctx, fn, lft, argc + extra_count, argv);

  for (int i = 0; i < argc + extra_count; i++) {
    JS_FreeValue(g_ctx, argv[i]);
  }
  JS_FreeValue(g_ctx, fn);
  JS_FreeValue(g_ctx, lft);

  if (JS_IsException(res)) {
    JS_FreeValue(g_ctx, res);
    throw_java_owned(env, take_exception_text(g_ctx, "call"));
    return NULL;
  }

  size_t rlen = 0;
  const char *rstr = JS_ToCStringLen(g_ctx, &rlen, res);
  JS_FreeValue(g_ctx, res);
  if (!rstr) {
    // Most likely an OOM tripped by JS_SetMemoryLimit while stringifying.
    throw_java_owned(env, take_exception_text(g_ctx, "result stringify"));
    return NULL;
  }
  jbyteArray out = cstr_to_jbytes(env, rstr, rlen);
  JS_FreeCString(g_ctx, rstr);
  return out;
}

// Diagnostic: pushes bytes through the exact marshalling path nativeCall uses
// (JS_NewStringLen in, JS_ToCStringLen out) and hands them straight back.
//
// This exists to test the byte[] boundary's central claim — that embedded NUL survives —
// which nothing in the real call surface can prove, because every method returns a
// JSON.stringify'd envelope in which NUL is re-escaped as \u0000 and so would pass even on a
// boundary that mangles raw bytes. Both halves are length-counted, so the NUL is carried as
// data rather than as a terminator; a jstring boundary substitutes U+FFFD outbound and
// truncates inbound (ticket 07).
JNIEXPORT jbyteArray JNICALL Java_com_liftosaur_wear_engine_LiftosaurEngine_nativeEcho(
    JNIEnv *env, jclass cls, jbyteArray bytes) {
  (void)cls;
  if (!g_ctx) {
    throw_java(env, "engine not initialized");
    return NULL;
  }

  jsize len = (*env)->GetArrayLength(env, bytes);
  jbyte *buf = (*env)->GetByteArrayElements(env, bytes, NULL);
  JSValue val = JS_NewStringLen(g_ctx, (const char *)buf, (size_t)len);
  (*env)->ReleaseByteArrayElements(env, bytes, buf, JNI_ABORT);

  if (JS_IsException(val)) {
    JS_FreeValue(g_ctx, val);
    throw_java_owned(env, take_exception_text(g_ctx, "echo in"));
    return NULL;
  }

  size_t outlen = 0;
  const char *out = JS_ToCStringLen(g_ctx, &outlen, val);
  JS_FreeValue(g_ctx, val);
  if (!out) {
    throw_java_owned(env, take_exception_text(g_ctx, "echo out"));
    return NULL;
  }
  jbyteArray result = cstr_to_jbytes(env, out, outlen);
  JS_FreeCString(g_ctx, out);
  return result;
}

// Adjusts the JS allocation ceiling on a live runtime. Exists for the self-test, which
// squeezes the limit to prove a runaway surfaces as a caught error rather than an OOM kill.
JNIEXPORT void JNICALL Java_com_liftosaur_wear_engine_LiftosaurEngine_nativeSetMemoryLimit(
    JNIEnv *env, jclass cls, jlong bytes) {
  (void)env;
  (void)cls;
  if (g_rt) {
    JS_SetMemoryLimit(g_rt, bytes < 0 ? (size_t)-1 : (size_t)bytes);
  }
}

// Bytes of JS-side allocation. Constant across calls with a constant payload — a rising
// value means a real leak, whereas RSS movement is just libc arena behavior (ticket 12).
JNIEXPORT jlong JNICALL Java_com_liftosaur_wear_engine_LiftosaurEngine_nativeMallocSize(
    JNIEnv *env, jclass cls) {
  (void)env;
  (void)cls;
  if (!g_rt) {
    return -1;
  }
  JSMemoryUsage usage;
  JS_ComputeMemoryUsage(g_rt, &usage);
  return (jlong)usage.malloc_size;
}
