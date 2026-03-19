#version 150

uniform sampler2D DiffuseSampler;
uniform float RedStrength;
uniform float GreenStrength;
uniform float BlueStrength;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 color = texture(DiffuseSampler, texCoord);

    // Яркость пикселя (luminance)
    float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));

    // Красный тинт: умножаем каналы на множители
    // Сохраняем детали но смещаем палитру в красный
    vec3 tinted;
    tinted.r = clamp(color.r * RedStrength,   0.0, 1.0);
    tinted.g = clamp(color.g * GreenStrength, 0.0, 1.0);
    tinted.b = clamp(color.b * BlueStrength,  0.0, 1.0);

    // Смешиваем оригинал и тинт 50/50 чтобы не было слишком агрессивно
    // но мир всё равно выглядит явно красноватым и зловещим
    vec3 result = mix(color.rgb, tinted, 0.6);

    fragColor = vec4(result, color.a);
}
