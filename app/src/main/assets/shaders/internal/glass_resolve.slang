#version 450

// Pase interno del Modo Glass (PROYECTO.md §10). Convierte la salida del preset, calculada sobre una entrada plana de
// gris GLASS_INPUT, en una capa con alfa premultiplicado: el sistema compone fondo·(1−a) + rgb encima de la app.
// L = luz que deja pasar cada píxel (1 = sin máscara). Lo añade el motor solo en Glass; no es un preset.

#pragma parameter GLASS_STRENGTH "Glass · Strength" 1.0 0.0 1.0 0.05
#pragma parameter GLASS_INPUT "Glass · Input brightness" 0.7 0.05 1.0 0.05
#pragma parameter GLASS_GAIN "Glass · Gain (0 = auto)" 0.0 0.0 4.0 0.05
#pragma parameter GLASS_COLOR "Glass · Mask color" 0.0 0.0 0.5 0.05
// Los dos siguientes no entran en el push constant: los lee la app. ANIM_FPS (T34) = cada cuántos FPS se redibuja la capa (0 = estática;
// con FPS > 0 FrameCount avanza y FrameTimeDelta es el real). BLUR (T35) = desenfoque, en píxeles, del contenido que hay debajo
// (lo hace el compositor de Android con FLAG_BLUR_BEHIND; API 31+).
#pragma parameter GLASS_ANIM_FPS "Glass · Animation (FPS, 0 = static)" 0.0 0.0 60.0 5.0
#pragma parameter GLASS_BLUR "Glass · Blur (px)" 0.0 0.0 20.0 1.0

layout(push_constant) uniform Push
{
    float GLASS_STRENGTH;
    float GLASS_INPUT;
    float GLASS_GAIN;
    float GLASS_COLOR;
} params;

layout(std140, set = 0, binding = 0) uniform UBO
{
    mat4 MVP;
} global;

#pragma stage vertex
layout(location = 0) in vec4 Position;
layout(location = 1) in vec2 TexCoord;
layout(location = 0) out vec2 vTexCoord;

void main()
{
    gl_Position = global.MVP * Position;
    vTexCoord = TexCoord;
}

#pragma stage fragment
layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 FragColor;
layout(set = 0, binding = 2) uniform sampler2D Source;

void main()
{
    // Luz relativa a la entrada plana: un píxel que la máscara no toca queda en 1
    vec3 m = clamp(texture(Source, vTexCoord).rgb * (params.GLASS_GAIN / params.GLASS_INPUT), 0.0, 1.0);
    bool color = params.GLASS_COLOR > 0.001;
    float L = color ? min(m.r, min(m.g, m.b)) : dot(m, vec3(0.2126, 0.7152, 0.0722));
    float a = (1.0 - L) * params.GLASS_STRENGTH;
    // Con color, lo que el alfa no puede expresar (la diferencia entre canales) se suma como luz
    vec3 rgb = color ? (m - vec3(L)) * (params.GLASS_COLOR * params.GLASS_STRENGTH) : vec3(0.0);
    FragColor = vec4(rgb, a);
}
