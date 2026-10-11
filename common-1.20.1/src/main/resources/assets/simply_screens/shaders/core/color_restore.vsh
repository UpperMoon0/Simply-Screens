#version 150
in vec3 Position;
in vec2 UV0;
uniform mat4 ScreenProjection;
uniform mat4 ScreenModelView;
out vec2 texCoord;
void main() {
    gl_Position = ScreenProjection * ScreenModelView * vec4(Position, 1.0);
    texCoord = UV0;
}
