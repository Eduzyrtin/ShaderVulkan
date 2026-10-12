#pragma once
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>

// Progreso de la carga de un preset (análisis de los .slang + compilación de pipelines), consultado desde Kotlin para
// mostrar "LOADING SHADER xx%". Estado global: la carga se lanza desde hilos distintos y el renderer puede recrearse.
namespace LoadProgress {
    inline std::atomic<bool> active{false};
    // reserved: la compilación que 'total' ya cuenta (de loadPreset) y aún no ha empezado (0 o 1). running: compilaciones en
    // curso (una por salida del motor, T15). Se tocan con mtx. La barra se cierra cuando no queda ninguna de las dos.
    inline int reserved = 0;
    inline int running = 0;
    inline std::atomic<int> done{0};
    inline std::atomic<int> total{0};
    inline std::mutex mtx;
    inline std::string label;
    inline std::string status; // motivo de un fallo de la carga ("" = todo bien); protegido por mtx

    inline void setLabel(const std::string& l) {
        std::lock_guard<std::mutex> g(mtx);
        label = l;
    }
    // withCompile: 'totalSteps' ya cuenta los pasos de la compilación posterior (la primera salida que compile los toma de aquí)
    inline void begin(int totalSteps, bool withCompile, const std::string& l) {
        std::lock_guard<std::mutex> g(mtx);
        done = 0;
        total = std::max(1, totalSteps);
        reserved = withCompile ? 1 : 0;
        running = 0;
        active = true;
        label = l;
        status.clear(); // una carga nueva empieza sin el aviso de la anterior
    }
    // Una salida empieza a compilar 'steps' pasos+texturas: usa su reserva de loadPreset, se suma a la barra de otra salida que
    // ya compila (modo dual: una sola barra para las dos) o abre una barra nueva (recompilación suelta: giro, resolución...).
    inline void compileBegin(int steps) {
        std::lock_guard<std::mutex> g(mtx);
        if (reserved > 0) {
            reserved--;
        } else if (running > 0) {
            total += steps;
        } else {
            done = 0;
            total = std::max(1, steps);
            label = "Compilando shaders";
            status.clear();
        }
        running++;
        active = true;
    }
    // Una salida terminó de compilar. 'current' = su compilación sigue vigente (si quedó obsoleta, llegará otra y seguirá la barra).
    inline void compileEnd(bool current) {
        std::lock_guard<std::mutex> g(mtx);
        if (running > 0) running--;
        if (current && running == 0 && reserved == 0) active = false;
    }
    // Fallo de la carga (pase que no compila, preset ilegible...). Lo recoge la UI con consumeStatus() al terminar.
    inline void setStatus(const std::string& s) {
        std::lock_guard<std::mutex> g(mtx);
        status = s;
    }
    // Añade un aviso al que ya hubiera (varios fallos en una misma carga se muestran juntos)
    inline void addStatus(const std::string& s) {
        std::lock_guard<std::mutex> g(mtx);
        status = status.empty() ? s : status + " · " + s;
    }
    inline std::string consumeStatus() {
        std::lock_guard<std::mutex> g(mtx);
        std::string s;
        s.swap(status);
        return s;
    }
    inline void step(const std::string& nextLabel) {
        done++;
        setLabel(nextLabel);
    }
    inline void finish() {
        std::lock_guard<std::mutex> g(mtx);
        reserved = 0;
        running = 0;
        active = false;
    }
    // -1 = nada cargando; 0..99 mientras carga (100 nunca: al terminar pasa a -1)
    inline int percent() {
        if (!active.load()) return -1;
        int t = total.load();
        if (t <= 0) return 0;
        return std::min(99, done.load() * 100 / t);
    }
    inline std::string currentLabel() {
        std::lock_guard<std::mutex> g(mtx);
        return label;
    }
}
