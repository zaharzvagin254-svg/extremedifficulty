#version 150

in vec4 Position;
in vec2 UV0;

out vec2 texCoord;

uniform mat4 ProjMat;
uniform vec2 OutSize;

void main() {
    gl_Position = ProjMat * Position;
    texCoord = UV0;
}
