#version 330

uniform sampler2D InSampler;

layout(std140) uniform HdrParameters {
    float PeakBrightness;
    int TransferFunction;
};

in vec2 texCoord;
out vec4 fragColor;

const float PQ_M1 = 2610.0 / 16384.0;
const float PQ_M2 = 2523.0 / 32.0;
const float PQ_C1 = 3424.0 / 4096.0;
const float PQ_C2 = 2413.0 / 128.0;
const float PQ_C3 = 2392.0 / 128.0;

// === S-Log3 constants (Sony S-Log3 specification) ===
// S-Log3 is scene-referred: the input x is scene-linear exposure where
// 0.9 = 90% diffuse white, and the nits peak-brightness slider does not
// apply. S-Log3 encodes x in [0, 16.6] to code values [~0.01, ~0.91].
// V = (420 + log10((L + 0.01) / 0.19) * 261.5) / 1023
const float SLOG3_SDR_WHITE_SCALE = 0.9;
const float SLOG3_OFFSET = 0.01;
const float SLOG3_DIVISOR = 0.19;
const float SLOG3_MULTIPLIER = 261.5;
const float SLOG3_ADDEND = 420.0;
const float SLOG3_DENOMINATOR = 1023.0;
// GLSL has no log10 builtin; derive it from log2. log2(10) = 3.321928094887362.
const float LOG2_OF_10 = 3.321928094887362;

vec3 log10(vec3 x) {
    return log2(x) / vec3(LOG2_OF_10);
}

const mat3 BT709_TO_BT2020 = mat3(
    vec3(0.6274039149, 0.0690972880, 0.0163914394),
    vec3(0.3292830288, 0.9195404053, 0.0880133063),
    vec3(0.0433130674, 0.0113623152, 0.8955952525)
);

vec3 srgbDecode(vec3 color) {
    vec3 signColor = sign(color);
    vec3 absoluteColor = abs(color);
    vec3 linear = mix(
        pow((absoluteColor + vec3(0.055)) / vec3(1.055), vec3(2.4)),
        absoluteColor / vec3(12.92),
        lessThan(absoluteColor, vec3(0.04045))
    );
    return linear * signColor;
}

vec3 pqEncode(vec3 color) {
    vec3 normalized = max(color, vec3(0.0)) * PeakBrightness / 10000.0;
    vec3 power = pow(normalized, vec3(PQ_M1));
    vec3 pq = (vec3(PQ_C1) + vec3(PQ_C2) * power) / (vec3(1.0) + vec3(PQ_C3) * power);
    return clamp(pow(pq, vec3(PQ_M2)), 0.0, 1.0);
}

vec3 slog3Encode(vec3 color) {
    // Scene-referred: SDR white (1.0) maps to Sony's 90% scene white.
    vec3 normalized = max(color, vec3(0.0)) * SLOG3_SDR_WHITE_SCALE;
    // S-Log3: V = (420 + log10((L + 0.01) / 0.19) * 261.5) / 1023
    vec3 encoded = (vec3(SLOG3_ADDEND) + log10((normalized + vec3(SLOG3_OFFSET)) / vec3(SLOG3_DIVISOR)) * vec3(SLOG3_MULTIPLIER)) / vec3(SLOG3_DENOMINATOR);
    return clamp(encoded, 0.0, 1.0);
}

void main() {
    vec4 color = texture(InSampler, texCoord);
    vec3 linear = BT709_TO_BT2020 * srgbDecode(color.rgb);
    
    // Select transfer function based on uniform (11 = PQ, 12 = S-Log3)
    // Default to PQ for backwards compatibility
    if (TransferFunction == 12)
        color.rgb = slog3Encode(linear);
    else
        color.rgb = pqEncode(linear);
    
    fragColor = color;
}
