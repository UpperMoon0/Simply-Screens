#version 330

uniform sampler2D Sampler0;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec4 source = texture(Sampler0, texCoord0);
    // Preserve transparent/semitransparent world-pass composition. Only replace opaque samples;
    // blending is disabled so source alpha is never applied a second time.
    if (source.a < 0.99999) discard;
    fragColor = vec4(source.rgb, 1.0);
}
