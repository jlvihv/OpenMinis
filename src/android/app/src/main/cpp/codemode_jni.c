/* Android/JVM host for Pi's unmodified codemode prelude. No quickjs-libc/OS bindings. */
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "quickjs/quickjs.h"

typedef struct {
    JSRuntime *rt;
    JSContext *ctx;
    JSValue api, run, settle, stalled;
    atomic_int interrupted;
    int finished;
    JNIEnv *env; /* All VM operations and callbacks run on the same owner thread. */
    jobject receiver;
    jmethodID on_event;
} Codemode;

static int interrupt_handler(JSRuntime *rt, void *opaque) {
    (void)rt;
    Codemode *vm = opaque;
    return atomic_load_explicit(&vm->interrupted, memory_order_relaxed) || (*vm->env)->ExceptionCheck(vm->env);
}

static void post_bytes(Codemode *vm, const char *data, size_t length) {
    JNIEnv *env = vm->env;
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (!bytes) return;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)data);
    if (!(*env)->ExceptionCheck(env)) (*env)->CallVoidMethod(env, vm->receiver, vm->on_event, bytes);
    (*env)->DeleteLocalRef(env, bytes);
}

static void post_crash(Codemode *vm) {
    vm->finished = 1;
    if (!(*vm->env)->ExceptionCheck(vm->env) && !atomic_load(&vm->interrupted)) {
        const char error[] = "{\"type\":\"crash\",\"message\":\"Native QuickJS bridge/exception serialization failed (possibly out of memory)\"}";
        post_bytes(vm, error, sizeof(error) - 1);
    }
    // A script may catch the bridge exception. A transport failure is fatal,
    // so also stop its subsequent CPU loop without waiting for host scheduling.
    atomic_store(&vm->interrupted, 1);
}

/* Always consumes value; never store an exception sentinel in a JS property. */
static int put(JSContext *ctx, JSValueConst obj, const char *key, JSValue value) {
    if (JS_IsException(obj) || JS_IsException(value)) {
        JS_FreeValue(ctx, value);
        return -1;
    }
    return JS_SetPropertyStr(ctx, obj, key, value);
}

static int post(Codemode *vm, JSValue event) {
    if (JS_IsException(event)) return -1;
    JSValue json = JS_JSONStringify(vm->ctx, event, JS_UNDEFINED, JS_UNDEFINED);
    JS_FreeValue(vm->ctx, event);
    if (JS_IsException(json)) return -1;
    size_t length;
    const char *data = JS_ToCStringLen(vm->ctx, &length, json);
    int ok = data != NULL;
    if (data) { post_bytes(vm, data, length); JS_FreeCString(vm->ctx, data); }
    JS_FreeValue(vm->ctx, json);
    return ok && !(*vm->env)->ExceptionCheck(vm->env) ? 0 : -1;
}

static JSValue bridge(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    Codemode *vm = JS_GetContextOpaque(ctx);
    if (vm->finished || atomic_load(&vm->interrupted)) return JS_UNDEFINED;
    if (argc < 3) return JS_ThrowTypeError(ctx, "Invalid codemode bridge call");
    const char *kind = JS_ToCString(ctx, argv[0]);
    if (!kind) { post_crash(vm); return JS_EXCEPTION; }
    JSValue event = JS_NewObject(ctx), item = JS_UNDEFINED;
    JSValue a = argv[1], b = argv[2], c = argc > 3 ? argv[3] : JS_UNDEFINED;
    if (JS_IsException(event)) goto failure;
#define SET(obj, key, value) do { if (put(ctx, obj, key, value) < 0) goto failure; } while (0)
    if (!strcmp(kind, "call") || !strcmp(kind, "global")) {
        SET(event, "type", JS_NewString(ctx, "call"));
        SET(event, "target", JS_NewString(ctx, !strcmp(kind, "call") ? "tool" : "global"));
        SET(event, "id", JS_DupValue(ctx, a));
        SET(event, "name", JS_DupValue(ctx, b));
        if (!JS_IsUndefined(c)) SET(event, "args", JS_DupValue(ctx, c));
    } else if (!strcmp(kind, "output")) {
        SET(event, "type", JS_NewString(ctx, "output"));
        item = JS_NewObject(ctx);
        if (JS_IsException(item)) goto failure;
        SET(item, "type", JS_DupValue(ctx, a));
        const char *type = JS_ToCString(ctx, a);
        if (!type) goto failure;
        int image = !strcmp(type, "image");
        JS_FreeCString(ctx, type);
        SET(item, image ? "data" : "text", JS_DupValue(ctx, b));
        if (image) SET(item, "mimeType", JS_DupValue(ctx, c));
        JSValue owned = item;
        item = JS_UNDEFINED;
        SET(event, "item", owned);
    } else if (!strcmp(kind, "done")) {
        int ok = JS_ToBool(ctx, a);
        SET(event, "type", JS_NewString(ctx, "done"));
        SET(event, "ok", JS_NewBool(ctx, ok));
        if (ok) {
            if (!JS_IsUndefined(b)) SET(event, "value", JS_DupValue(ctx, b));
            SET(event, "writes", JS_DupValue(ctx, c));
        } else SET(event, "error", JS_DupValue(ctx, b));
    } else {
        JS_FreeCString(ctx, kind);
        JS_FreeValue(ctx, event);
        return JS_ThrowTypeError(ctx, "Unknown codemode bridge event");
    }
    int done = !strcmp(kind, "done");
    JS_FreeCString(ctx, kind);
    if (post(vm, event) < 0) { post_crash(vm); return JS_EXCEPTION; }
    if (done) vm->finished = 1;
    return JS_UNDEFINED;
failure:
    JS_FreeCString(ctx, kind);
    JS_FreeValue(ctx, event);
    JS_FreeValue(ctx, item);
    post_crash(vm);
    return JS_EXCEPTION;
#undef SET
}

/* QuickJS stack strings contain frames, not the error header. Match Pi's host formatting. */
static JSValue format_stack(JSContext *ctx, JSValueConst name, JSValueConst message, JSValueConst stack) {
    if (!JS_IsString(stack)) return JS_UNDEFINED;
    size_t n = 0, m = 0, s = 0;
    const char *a = JS_ToCStringLen(ctx, &n, name);
    const char *b = a ? JS_ToCStringLen(ctx, &m, message) : NULL;
    const char *c = b ? JS_ToCStringLen(ctx, &s, stack) : NULL;
    JSValue result = JS_EXCEPTION;
    if (c) {
        size_t length = n + m + s + 3;
        char *formatted = malloc(length);
        if (formatted) {
            memcpy(formatted, a, n); memcpy(formatted + n, ": ", 2);
            memcpy(formatted + n + 2, b, m); formatted[n + m + 2] = '\n';
            memcpy(formatted + n + m + 3, c, s);
            result = JS_NewStringLen(ctx, formatted, length);
            free(formatted);
        } else result = JS_ThrowOutOfMemory(ctx);
    }
    JS_FreeCString(ctx, a); JS_FreeCString(ctx, b); JS_FreeCString(ctx, c);
    return result;
}

static void report_exception(Codemode *vm) {
    if (vm->finished || atomic_load(&vm->interrupted) || (*vm->env)->ExceptionCheck(vm->env)) return;
    JSContext *ctx = vm->ctx;
    JSValue exception = JS_GetException(ctx), details = JS_UNDEFINED, event = JS_UNDEFINED;
    JSValue name = JS_UNDEFINED, message = JS_UNDEFINED, stack = JS_UNDEFINED, error = JS_UNDEFINED;
    name = JS_GetPropertyStr(ctx, exception, "name");
    if (JS_IsException(name)) goto failure;
    if (JS_IsUndefined(name)) name = JS_NewString(ctx, "Error");
    message = JS_GetPropertyStr(ctx, exception, "message");
    if (JS_IsException(message)) goto failure;
    if (JS_IsUndefined(message)) message = JS_ToString(ctx, exception);
    stack = JS_GetPropertyStr(ctx, exception, "stack");
    if (JS_IsException(stack)) goto failure;
    JSValue formatted = format_stack(ctx, name, message, stack);
    JS_FreeValue(ctx, stack); stack = formatted;
    if (JS_IsException(stack)) goto failure;
    details = JS_NewObject(ctx);
    if (JS_IsException(details)) goto failure;
    if (put(ctx, details, "name", JS_DupValue(ctx, name)) < 0 ||
        put(ctx, details, "message", JS_DupValue(ctx, message)) < 0 ||
        (!JS_IsUndefined(stack) && put(ctx, details, "stack", JS_DupValue(ctx, stack)) < 0)) goto failure;
    error = JS_JSONStringify(ctx, details, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(error)) goto failure;
    event = JS_NewObject(ctx);
    if (JS_IsException(event)) goto failure;
    if (put(ctx, event, "type", JS_NewString(ctx, "done")) < 0 ||
        put(ctx, event, "ok", JS_FALSE) < 0 || put(ctx, event, "error", JS_DupValue(ctx, error)) < 0) goto failure;
    int posted = post(vm, event);
    event = JS_UNDEFINED;
    if (posted < 0) goto failure;
    vm->finished = 1;
    goto cleanup;
failure:
    post_crash(vm);
cleanup:
    JS_FreeValue(ctx, exception); JS_FreeValue(ctx, details); JS_FreeValue(ctx, event);
    JS_FreeValue(ctx, name); JS_FreeValue(ctx, message); JS_FreeValue(ctx, stack); JS_FreeValue(ctx, error);
}

static void drain(Codemode *vm) {
    JSContext *ctx = NULL;
    int result;
    while (!vm->finished && (result = JS_ExecutePendingJob(vm->rt, &ctx)) > 0) {}
    if (!vm->finished && ctx && result < 0) { report_exception(vm); return; }
    if (!vm->finished) {
        JSValue value = JS_Call(vm->ctx, vm->stalled, vm->api, 0, NULL);
        if (JS_IsException(value)) report_exception(vm);
        JS_FreeValue(vm->ctx, value);
    }
}

static JSValue from_bytes(Codemode *vm, jbyteArray bytes) {
    if ((*vm->env)->ExceptionCheck(vm->env)) return JS_EXCEPTION;
    if (!bytes) return JS_UNDEFINED;
    jsize length = (*vm->env)->GetArrayLength(vm->env, bytes);
    jbyte *data = (*vm->env)->GetByteArrayElements(vm->env, bytes, NULL);
    if (!data) return JS_EXCEPTION;
    JSValue value = JS_NewStringLen(vm->ctx, (const char *)data, (size_t)length);
    (*vm->env)->ReleaseByteArrayElements(vm->env, bytes, data, JNI_ABORT);
    return value;
}

static void destroy(Codemode *vm) {
    if (!vm) return;
    if (vm->ctx) {
        JS_FreeValue(vm->ctx, vm->run);
        JS_FreeValue(vm->ctx, vm->settle);
        JS_FreeValue(vm->ctx, vm->stalled);
        JS_FreeValue(vm->ctx, vm->api);
        JS_FreeContext(vm->ctx);
    }
    if (vm->rt) JS_FreeRuntime(vm->rt);
    if (vm->receiver) (*vm->env)->DeleteGlobalRef(vm->env, vm->receiver);
    free(vm);
}

JNIEXPORT jlong JNICALL Java_com_openminis_app_tools_CodemodeNative_create(JNIEnv *env, jobject self,
        jbyteArray prelude, jbyteArray tools, jbyteArray globals, jbyteArray store) {
    Codemode *vm = calloc(1, sizeof(*vm));
    if (!vm) goto failed_no_vm;
    vm->env = env;
    vm->api = vm->run = vm->settle = vm->stalled = JS_UNDEFINED;
    atomic_init(&vm->interrupted, 0);
    vm->receiver = (*env)->NewGlobalRef(env, self);
    if (!vm->receiver) goto failed;
    jclass cls = (*env)->GetObjectClass(env, self);
    if (!cls) goto failed;
    vm->on_event = (*env)->GetMethodID(env, cls, "onEvent", "([B)V");
    (*env)->DeleteLocalRef(env, cls);
    if (!vm->on_event) goto failed;
    vm->rt = JS_NewRuntime();
    if (!vm->rt) goto failed;
    JS_SetMemoryLimit(vm->rt, 256 * 1024 * 1024);
    JS_SetMaxStackSize(vm->rt, 512 * 1024);
    JS_SetInterruptHandler(vm->rt, interrupt_handler, vm);
    vm->ctx = JS_NewContext(vm->rt);
    if (!vm->ctx) goto failed;
    JS_SetContextOpaque(vm->ctx, vm);
    JSValue source = from_bytes(vm, prelude);
    if (JS_IsException(source)) goto failed;
    size_t length;
    const char *code = JS_ToCStringLen(vm->ctx, &length, source);
    JSValue fn = code ? JS_Eval(vm->ctx, code, length, "codemode-prelude.js", JS_EVAL_TYPE_GLOBAL) : JS_EXCEPTION;
    if (code) JS_FreeCString(vm->ctx, code);
    JS_FreeValue(vm->ctx, source);
    JSValue args[] = { JS_NewCFunction(vm->ctx, bridge, "bridge", 4), from_bytes(vm, tools),
        from_bytes(vm, globals), from_bytes(vm, store) };
    int valid_args = 1;
    for (int i = 0; i < 4; i++) if (JS_IsException(args[i])) valid_args = 0;
    if (!JS_IsException(fn) && valid_args) vm->api = JS_Call(vm->ctx, fn, JS_UNDEFINED, 4, args);
    for (int i = 0; i < 4; i++) JS_FreeValue(vm->ctx, args[i]);
    JS_FreeValue(vm->ctx, fn);
    if (JS_IsException(vm->api) || JS_IsUndefined(vm->api)) goto failed;
    vm->run = JS_GetPropertyStr(vm->ctx, vm->api, "run");
    vm->settle = JS_GetPropertyStr(vm->ctx, vm->api, "settle");
    vm->stalled = JS_GetPropertyStr(vm->ctx, vm->api, "stalled");
    return (jlong)(intptr_t)vm;
failed:
    destroy(vm);
failed_no_vm:
    if (!(*env)->ExceptionCheck(env)) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (error) {
            (*env)->ThrowNew(env, error, "Unable to initialize native QuickJS codemode");
            (*env)->DeleteLocalRef(env, error);
        }
    }
    return 0;
}

JNIEXPORT void JNICALL Java_com_openminis_app_tools_CodemodeNative_start(JNIEnv *env, jobject self,
        jlong handle, jbyteArray source) {
    (void)self;
    Codemode *vm = (Codemode *)(intptr_t)handle;
    vm->env = env;
    JS_UpdateStackTop(vm->rt);
    if (atomic_load(&vm->interrupted)) return;
    JSValue string = from_bytes(vm, source);
    if (JS_IsException(string)) { report_exception(vm); return; }
    size_t length;
    const char *code = JS_ToCStringLen(vm->ctx, &length, string);
    JSValue fn = code ? JS_Eval(vm->ctx, code, length, "codemode.js", JS_EVAL_TYPE_GLOBAL) : JS_EXCEPTION;
    if (code) JS_FreeCString(vm->ctx, code);
    JS_FreeValue(vm->ctx, string);
    if (JS_IsException(fn)) { report_exception(vm); return; }
    JSValue result = JS_Call(vm->ctx, vm->run, vm->api, 1, &fn);
    JS_FreeValue(vm->ctx, fn);
    if (JS_IsException(result)) report_exception(vm);
    JS_FreeValue(vm->ctx, result);
    drain(vm);
}

JNIEXPORT void JNICALL Java_com_openminis_app_tools_CodemodeNative_settle(JNIEnv *env, jobject self,
        jlong handle, jint id, jboolean ok, jbyteArray payload) {
    (void)self;
    Codemode *vm = (Codemode *)(intptr_t)handle;
    vm->env = env;
    if (vm->finished || atomic_load(&vm->interrupted)) return;
    JS_UpdateStackTop(vm->rt);
    JSValue args[] = { JS_NewInt32(vm->ctx, id), JS_NewBool(vm->ctx, ok), from_bytes(vm, payload) };
    JSValue result = JS_IsException(args[2]) ? JS_EXCEPTION : JS_Call(vm->ctx, vm->settle, vm->api, 3, args);
    for (int i = 0; i < 3; i++) JS_FreeValue(vm->ctx, args[i]);
    if (JS_IsException(result)) report_exception(vm);
    JS_FreeValue(vm->ctx, result);
    drain(vm);
}

JNIEXPORT void JNICALL Java_com_openminis_app_tools_CodemodeNative_interrupt(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    atomic_store_explicit(&((Codemode *)(intptr_t)handle)->interrupted, 1, memory_order_relaxed);
}

JNIEXPORT void JNICALL Java_com_openminis_app_tools_CodemodeNative_release(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    Codemode *vm = (Codemode *)(intptr_t)handle;
    vm->env = env;
    destroy(vm);
}
