#version 330

uniform sampler2D InSampler;
uniform float UiBrightness;
uniform float EotfEmulate;
uniform int Primaries;
uniform int TransferFunction;

in vec2 texCoord;

out vec4 fragColor;

// === PQ (ST.2084) constants ===
const float PQ_M1 = 2610.0/4096 * 1.0/4;
const float PQ_M2 = 2523.0/4096 * 128;
const float PQ_C1 = 3424.0/4096;
const float PQ_C2 = 2413.0/4096 * 32;
const float PQ_C3 = 2392.0/4096 * 32;

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

// === BT.709 → BT.2020 gamut conversion matrix ===
const mat3 BT709_TO_BT2020_MAT = mat3(
    vec3(0.6274039149284363, 0.06909728795289993, 0.0163914393633604),
    vec3(0.3292830288410187, 0.9195404052734375, 0.08801330626010895),
    vec3(0.04331306740641594, 0.01136231515556574, 0.8955952525138855));

vec3 sRGB_DecodeSafe(vec3 c) {
    vec3 s = sign(c);
    c = abs(c);
    bvec3 cutoff = lessThan(c, vec3(0.04045));
    vec3 higher = pow((c + vec3(0.055)) / vec3(1.055), vec3(2.4));
    vec3 lower = c / vec3(12.92);
    return mix(higher, lower, cutoff) * s;
}

vec3 PQ_Encode(vec3 c, float scaling) {
    c *= scaling / 10000.0;
    c = pow(c, vec3(PQ_M1));
    c = (vec3(PQ_C1) + vec3(PQ_C2) * c) / (vec3(1.0) + vec3(PQ_C3) * c);
    return clamp(pow(c, vec3(PQ_M2)), 0.0, 1.0);
}

vec3 SLog3_Encode(vec3 c) {
    // Scene-referred: SDR white (1.0) maps to Sony's 90% scene white; the
    // nits peak-brightness slider does not apply to S-Log3.
    c *= SLOG3_SDR_WHITE_SCALE;
    // S-Log3: V = (420 + log10((L + 0.01) / 0.19) * 261.5) / 1023
    c = (vec3(SLOG3_ADDEND) + log10((c + vec3(SLOG3_OFFSET)) / vec3(SLOG3_DIVISOR)) * vec3(SLOG3_MULTIPLIER)) / vec3(SLOG3_DENOMINATOR);
    return clamp(c, 0.0, 1.0);
}

void main() {
    vec4 color = texture(InSampler, texCoord);

    // Step 1: sRGB decode (reverse the scRGB-nl transfer)
    color.rgb = sRGB_DecodeSafe(color.rgb);

    // Step 2: Gamut conversion to BT.2020
    if (Primaries == 6)
        color.rgb = BT709_TO_BT2020_MAT * color.rgb;

    // Step 3: Transfer function encoding
    if (TransferFunction == 11)
        color.rgb = PQ_Encode(color.rgb, UiBrightness);
    else if (TransferFunction == 12)
        color.rgb = SLog3_Encode(color.rgb);

    fragColor = color;
}
