#pragma once

#include <android/log.h>

// Diagnóstico solo en debug (T91). SV_DIAG sustituye a __android_log_print para los mensajes de información, depuración y aviso:
// en release (NDEBUG) no se compila ni se evalúan sus argumentos. Los errores (ANDROID_LOG_ERROR) se siguen registrando siempre.
#ifdef NDEBUG
#define SV_DIAG(prio, tag, ...) ((void)0)
#else
#define SV_DIAG(prio, tag, ...) __android_log_print(prio, tag, __VA_ARGS__)
#endif
