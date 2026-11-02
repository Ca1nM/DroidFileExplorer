/*
 * JNI wrapper around the LZMA SDK's LZMA2 decoder (public domain, Igor Pavlov; see lzma/).
 * One handle = one decoder with its own dictionary, allocated with malloc so it stays off the Java heap.
 * Handles are not thread-safe; the Java side gives each worker thread its own.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "lzma/Lzma2Dec.h"

static void *alloc_fn(ISzAllocPtr p, size_t size) {
    (void)p;
    return malloc(size ? size : 1);
}

static void free_fn(ISzAllocPtr p, void *address) {
    (void)p;
    free(address);
}

static const ISzAlloc g_alloc = { alloc_fn, free_fn };

JNIEXPORT jlong JNICALL
Java_com_sza_androidfolderexplorer_data_source_Lzma2Native_create(JNIEnv *env, jclass cls, jint prop) {
    (void)env; (void)cls;
    CLzma2Dec *dec = (CLzma2Dec *)malloc(sizeof(CLzma2Dec));
    if (dec == NULL) return 0;
    Lzma2Dec_CONSTRUCT(dec);
    if (Lzma2Dec_Allocate(dec, (Byte)prop, &g_alloc) != SZ_OK) {
        free(dec);
        return 0;
    }
    Lzma2Dec_Init(dec);
    return (jlong)(intptr_t)dec;
}

JNIEXPORT jint JNICALL
Java_com_sza_androidfolderexplorer_data_source_Lzma2Native_decode(
        JNIEnv *env, jclass cls, jlong handle,
        jbyteArray in, jint inOff, jint inLen,
        jbyteArray out, jint outOff, jint outLen, jintArray res) {
    (void)cls;
    CLzma2Dec *dec = (CLzma2Dec *)(intptr_t)handle;
    if (dec == NULL) return SZ_ERROR_PARAM;

    jbyte *src = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, in, NULL);
    if (src == NULL) return SZ_ERROR_MEM;
    jbyte *dst = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (dst == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, in, src, JNI_ABORT);
        return SZ_ERROR_MEM;
    }

    SizeT srcLen = (SizeT)inLen;
    SizeT dstLen = (SizeT)outLen;
    ELzmaStatus status = LZMA_STATUS_NOT_SPECIFIED;
    SRes r = Lzma2Dec_DecodeToBuf(dec, (Byte *)dst + outOff, &dstLen,
                                  (const Byte *)src + inOff, &srcLen, LZMA_FINISH_ANY, &status);

    (*env)->ReleasePrimitiveArrayCritical(env, out, dst, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, in, src, JNI_ABORT);

    jint info[3] = { (jint)srcLen, (jint)dstLen, (jint)status };
    (*env)->SetIntArrayRegion(env, res, 0, 3, info);
    return (jint)r;
}

JNIEXPORT void JNICALL
Java_com_sza_androidfolderexplorer_data_source_Lzma2Native_destroy(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    CLzma2Dec *dec = (CLzma2Dec *)(intptr_t)handle;
    if (dec == NULL) return;
    Lzma2Dec_Free(dec, &g_alloc);
    free(dec);
}
