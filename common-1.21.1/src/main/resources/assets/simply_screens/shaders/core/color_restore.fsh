#version 150
uniform sampler2D Sampler0;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    vec4 source = texture(Sampler0, texCoord);
    // Semi-transparent samples already contributed to the world. Reblending would apply alpha twice.
    // Preserve those processed samples, including filtered silhouette edges and transparent holes.
    if (source.a < 0.99999) discard;
    fragColor = vec4(source.rgb, 1.0);
}
