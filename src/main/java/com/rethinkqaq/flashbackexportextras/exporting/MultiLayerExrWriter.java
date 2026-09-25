/*
 * Flashback Export Extras
 * Copyright (C) RethinkQAQ
 *
 * This file is part of Flashback Export Extras.
 *
 * Flashback Export Extras is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Flashback Export Extras is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with Flashback Export Extras. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package com.rethinkqaq.flashbackexportextras.exporting;

import com.mojang.blaze3d.platform.NativeImage;
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtras;
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtrasConfig;
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtrasConfig.ExrCompression;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyexr.EXRChannelInfo;
import org.lwjgl.util.tinyexr.EXRAttribute;
import org.lwjgl.util.tinyexr.EXRHeader;
import org.lwjgl.util.tinyexr.EXRImage;
import org.lwjgl.util.tinyexr.TinyEXR;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-layer OpenEXR writer using LWJGL tinyexr bindings.
 * Same approach as ReplayMod — SaveEXRImageToFile with EXRHeader/EXRImage.
 *
 * Perf: All native buffers and EXR structs are pre-allocated once and reused
 * across frames to eliminate per-frame allocation overhead (~40MB/frame).
 */
public class MultiLayerExrWriter implements AutoCloseable {

    private static final int NUM_CHANNELS = 5;
    private static final float INV_255 = 1.0f / 255.0f;

    // ReplayMod-proven order: A, B, G, R (matches BGRA pixel data layout)
    private static final String[] CHANNEL_NAMES = {
        "View Layer.Combined.A",
        "View Layer.Combined.B",
        "View Layer.Combined.G",
        "View Layer.Combined.R",
        "View Layer.Depth.Z"
    };

    private final Path outputDir;
    private final int width;
    private final int height;
    private final boolean linearizeDepth;
    private final boolean sceneLinearHdr;
    private final boolean sLog3Encoding;
    private final boolean acesCctEncoding;
    private final int compressionType;
    private int frameCount;
    private boolean closed = false;

    // === Pre-allocated pixel buffers (reused every frame) ===
    private final FloatBuffer rBuf, gBuf, bBuf, aBuf, zBuf;

    // === Pre-allocated EXR data structures ===
    private final EXRHeader header;
    private final EXRImage image;
    private final EXRChannelInfo.Buffer channelInfo;
    private final IntBuffer pixelTypes;
    private final IntBuffer requestedTypes;
    private final PointerBuffer imagePtrs;
    private final List<ByteBuffer> nameBufs;
    private final EXRAttribute.Buffer customAttributes;
    private final List<ByteBuffer> customAttributeBuffers;

    public MultiLayerExrWriter(Path outputDir, int width, int height, boolean linearizeDepth,
                               boolean sceneLinearHdr, boolean sLog3Encoding, boolean acesCctEncoding,
                               ExrCompression compression) throws IOException {
        this.outputDir = outputDir;
        this.width = width;
        this.height = height;
        this.linearizeDepth = linearizeDepth;
        this.sceneLinearHdr = sceneLinearHdr;
        this.sLog3Encoding = sLog3Encoding;
        this.acesCctEncoding = acesCctEncoding;
        this.compressionType = switch (compression == null ? ExrCompression.ZIP : compression) {
            case NONE -> TinyEXR.TINYEXR_COMPRESSIONTYPE_NONE;
            case ZIPS -> TinyEXR.TINYEXR_COMPRESSIONTYPE_ZIPS;
            case ZIP -> TinyEXR.TINYEXR_COMPRESSIONTYPE_ZIP;
        };
        this.frameCount = 0;
        Files.createDirectories(outputDir);

        int pixelCount = width * height;

        // --- Pre-allocate pixel buffers (5 × width × height × 4 bytes) ---
        this.rBuf = MemoryUtil.memAllocFloat(pixelCount);
        this.gBuf = MemoryUtil.memAllocFloat(pixelCount);
        this.bBuf = MemoryUtil.memAllocFloat(pixelCount);
        this.aBuf = MemoryUtil.memAllocFloat(pixelCount);
        this.zBuf = MemoryUtil.memAllocFloat(pixelCount);

        // --- Pre-allocate EXR data structures ---
        this.header = EXRHeader.calloc();
        this.image = EXRImage.calloc();
        this.channelInfo = EXRChannelInfo.calloc(NUM_CHANNELS);
        this.pixelTypes = MemoryUtil.memAllocInt(NUM_CHANNELS);
        this.requestedTypes = MemoryUtil.memAllocInt(NUM_CHANNELS);
        this.imagePtrs = MemoryUtil.memAllocPointer(NUM_CHANNELS);
        this.nameBufs = new ArrayList<>(NUM_CHANNELS);
        this.customAttributeBuffers = new ArrayList<>();

        // --- Pre-configure immutable channel metadata ---
        for (int i = 0; i < NUM_CHANNELS; i++) {
            ByteBuffer nameBuf = MemoryUtil.memUTF8(CHANNEL_NAMES[i]);
            nameBufs.add(nameBuf);
            channelInfo.get(i).name(nameBuf);
            pixelTypes.put(i, TinyEXR.TINYEXR_PIXELTYPE_FLOAT);
            // RGBA → HALF output; Depth → FLOAT output (ReplayMod pattern)
            requestedTypes.put(i, (i < 4) ? TinyEXR.TINYEXR_PIXELTYPE_HALF : TinyEXR.TINYEXR_PIXELTYPE_FLOAT);
        }
        pixelTypes.flip();
        requestedTypes.flip();

        // --- Pre-set image pointer table (native addresses are stable) ---
        // Channel order: A, B, G, R, Z
        imagePtrs.put(0, MemoryUtil.memAddress(aBuf));
        imagePtrs.put(1, MemoryUtil.memAddress(bBuf));
        imagePtrs.put(2, MemoryUtil.memAddress(gBuf));
        imagePtrs.put(3, MemoryUtil.memAddress(rBuf));
        imagePtrs.put(4, MemoryUtil.memAddress(zBuf));
        imagePtrs.flip();

        if (sceneLinearHdr) {
            customAttributes = EXRAttribute.calloc(1);
            ByteBuffer attributeName = MemoryUtil.memUTF8("chromaticities");
            ByteBuffer attributeType = MemoryUtil.memUTF8("chromaticities");
            ByteBuffer chromaticities = MemoryUtil.memAlloc(8 * Float.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            float whiteX = 0.3127f;
            float whiteY = 0.3290f;
            if (sLog3Encoding) {
                // BT.2020/UHD primaries and D65 white, in OpenEXR
                // chromaticities order (red, green, blue, white xy pairs).
                chromaticities.putFloat(0.7080f).putFloat(0.2920f);
                chromaticities.putFloat(0.1700f).putFloat(0.7970f);
                chromaticities.putFloat(0.1310f).putFloat(0.0460f);
            } else if (acesCctEncoding) {
                // ACES AP1 primaries (ACEScg) with the ACES white (~D60).
                chromaticities.putFloat(0.7130f).putFloat(0.2930f);
                chromaticities.putFloat(0.1650f).putFloat(0.8300f);
                chromaticities.putFloat(0.1280f).putFloat(0.0440f);
                whiteX = 0.32168f;
                whiteY = 0.33767f;
            } else {
                // Rec.709/sRGB primaries and D65 white.
                chromaticities.putFloat(0.6400f).putFloat(0.3300f);
                chromaticities.putFloat(0.3000f).putFloat(0.6000f);
                chromaticities.putFloat(0.1500f).putFloat(0.0600f);
            }
            chromaticities.putFloat(whiteX).putFloat(whiteY).flip();
            customAttributes.get(0)
                    .name(attributeName)
                    .type(attributeType)
                    .value(chromaticities);
            customAttributeBuffers.add(attributeName);
            customAttributeBuffers.add(attributeType);
            customAttributeBuffers.add(chromaticities);
        } else {
            customAttributes = null;
        }
    }

    /**
     * Writes one multi-layer EXR frame.
     * Fills pre-allocated buffers with new data, then calls tinyexr.
     */
    public void writeFrame(NativeImage colorImage, DepthCaptureState.DepthFrame depthFrame,
                           int frameNumber) throws IOException {
        fillSdrColor(colorImage);
        fillDepth(depthFrame);
        writeExr(frameNumber);
        frameCount++;
    }

    /**
     * Writes HDR color with the matching depth frame. The buffer is either a
     * scene-linear Rec.709 RGBA16F frame (kept linear or ACEScct-encoded) or a
     * GPU-encoded S-Log3/BT.2020 RGBA16 frame from the HDR video pipeline.
     */
    public void writeHdrFrame(ByteBuffer hdrColor, DepthCaptureState.DepthFrame depthFrame,
                              int frameNumber) throws IOException {
        if (sLog3Encoding) {
            // Already encoded on the GPU: 16-bit UNORM S-Log3 code values.
            fillSLog3Color(hdrColor);
        } else {
            fillSceneLinearHdrColor(hdrColor);
        }
        fillDepth(depthFrame);
        writeExr(frameNumber);
        frameCount++;
    }

    /**
     * Fills the pre-allocated float buffers from NativeImage and depth buffer.
     *
     * Uses getPixelsRGBA() for a single bulk copy (one memcpy) instead of
     * width*height individual JNI calls to getPixelRGBA(x,y).
     */
    private void fillSdrColor(NativeImage colorImage) {
        float inv255 = INV_255;
        int pixelCount = width * height;

        // --- Pass 1: Bulk copy pixels, then extract RGBA in pure Java ---
        /*? if >=1.21.4 {*/
        /*int[] pixelArray = colorImage.getPixelsABGR();
        *//*?} else {*/
        int[] pixelArray = colorImage.getPixelsRGBA();
        /*?}*/
        for (int i = 0; i < pixelCount; i++) {
            int pixel = pixelArray[i];
            // 0xAABBGGRR → individual float channels
            rBuf.put(i, (pixel & 0xFF) * inv255);
            gBuf.put(i, ((pixel >> 8) & 0xFF) * inv255);
            bBuf.put(i, ((pixel >> 16) & 0xFF) * inv255);
            // Force full opacity: Iris shaders may write non-0xFF alpha for compositing/HDR
            aBuf.put(i, 1.0f);
        }
    }

    private void fillSceneLinearHdrColor(ByteBuffer rgba16f) {
        int pixelCount = width * height;
        int requiredBytes = pixelCount * 8;
        ByteBuffer data = rgba16f.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        data.rewind();
        if (data.remaining() < requiredBytes) {
            throw new IllegalArgumentException("RGBA16F frame is too small: "
                    + data.remaining() + " < " + requiredBytes);
        }
        if (acesCctEncoding) {
            // Input is scene-linear Rec.709; convert to AP1 and apply the
            // ACEScct log encoding (AMPAS S-2016-001, matches OCIO). The
            // result is declared via the AP1 chromaticities header attribute.
            for (int i = 0; i < pixelCount; i++) {
                int offset = i * 8;
                float lr = halfToFloat(data.getShort(offset));
                float lg = halfToFloat(data.getShort(offset + 2));
                float lb = halfToFloat(data.getShort(offset + 4));
                float a = halfToFloat(data.getShort(offset + 6));
                // Matrix multiply needs the original linear values, so compute
                // all three outputs from lr/lg/lb before overwriting them.
                rBuf.put(i, acesCctEncode(AP1_FROM_709_00 * lr + AP1_FROM_709_01 * lg + AP1_FROM_709_02 * lb));
                gBuf.put(i, acesCctEncode(AP1_FROM_709_10 * lr + AP1_FROM_709_11 * lg + AP1_FROM_709_12 * lb));
                bBuf.put(i, acesCctEncode(AP1_FROM_709_20 * lr + AP1_FROM_709_21 * lg + AP1_FROM_709_22 * lb));
                aBuf.put(i, a);
            }
        } else {
            for (int i = 0; i < pixelCount; i++) {
                int offset = i * 8;
                rBuf.put(i, halfToFloat(data.getShort(offset)));
                gBuf.put(i, halfToFloat(data.getShort(offset + 2)));
                bBuf.put(i, halfToFloat(data.getShort(offset + 4)));
                aBuf.put(i, halfToFloat(data.getShort(offset + 6)));
            }
        }
    }

    /**
     * Ingests a GPU-encoded S-Log3/BT.2020 RGBA16 frame — the exact same
     * 16-bit UNORM data the S-Log3 video pipeline feeds to FFmpeg. The GPU
     * already applied sRGB decode, BT.709 → BT.2020 and the S-Log3 curve, so
     * this is a straight UNORM16 → float conversion.
     */
    private void fillSLog3Color(ByteBuffer rgba16) {
        int pixelCount = width * height;
        int requiredBytes = pixelCount * 8;
        ByteBuffer data = rgba16.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        data.rewind();
        if (data.remaining() < requiredBytes) {
            throw new IllegalArgumentException("RGBA16 S-Log3 frame is too small: "
                    + data.remaining() + " < " + requiredBytes);
        }
        float inv65535 = 1.0f / 65535.0f;
        for (int i = 0; i < pixelCount; i++) {
            int offset = i * 8;
            rBuf.put(i, (data.getShort(offset) & 0xFFFF) * inv65535);
            gBuf.put(i, (data.getShort(offset + 2) & 0xFFFF) * inv65535);
            bBuf.put(i, (data.getShort(offset + 4) & 0xFFFF) * inv65535);
            aBuf.put(i, (data.getShort(offset + 6) & 0xFFFF) * inv65535);
        }
    }

    // === ACEScct encoding (AMPAS S-2016-001; constants match OCIO exactly) ===
    // Rec.709 → AP1 (ACEScg) gamut matrix, computed with the ACES-standard
    // Bradford CAT from D65 to the ACES white (Lib.Academy.ColorSpaces.ctl).
    private static final float AP1_FROM_709_00 = 0.6003059002f;
    private static final float AP1_FROM_709_01 = 0.3332993445f;
    private static final float AP1_FROM_709_02 = 0.0663947552f;
    private static final float AP1_FROM_709_10 = 0.0727535602f;
    private static final float AP1_FROM_709_11 = 0.9200579845f;
    private static final float AP1_FROM_709_12 = 0.0071884553f;
    private static final float AP1_FROM_709_20 = 0.0250321333f;
    private static final float AP1_FROM_709_21 = 0.1093703672f;
    private static final float AP1_FROM_709_22 = 0.8655974994f;
    /** Log segment: ACEScct = (log2(x) + 9.72) / 17.52 for x ≥ 2^-7. */
    private static final float ACES_CCT_LOG_K = 17.52f;
    private static final float ACES_CCT_LOG_B = 9.72f;
    private static final float ACES_CCT_BREAK = 0.0078125f;
    private static final float INV_LOG2 = 1.4426950408889634f;
    /**
     * Linear toe below the break (C0/C1-continuous with the log segment):
     * ACEScct = 10.5402377416545 * x + 0.0729055341958354.
     */
    private static final float ACES_CCT_TOE_SLOPE = 10.5402377416545f;
    private static final float ACES_CCT_TOE_OFFSET = 0.0729055341958354f;

    private static float acesCctEncode(float lin) {
        if (lin >= ACES_CCT_BREAK) {
            return ((float) Math.log(lin) * INV_LOG2 + ACES_CCT_LOG_B) / ACES_CCT_LOG_K;
        }
        return lin * ACES_CCT_TOE_SLOPE + ACES_CCT_TOE_OFFSET;
    }

    private static float halfToFloat(short half) {
        int bits = half & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1f;
        int mantissa = bits & 0x03ff;
        if (exponent == 0) {
            if (mantissa == 0) return Float.intBitsToFloat(sign);
            while ((mantissa & 0x0400) == 0) {
                mantissa <<= 1;
                exponent--;
            }
            exponent++;
            mantissa &= ~0x0400;
        } else if (exponent == 31) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
        }
        int floatExponent = exponent + (127 - 15);
        return Float.intBitsToFloat(sign | (floatExponent << 23) | (mantissa << 13));
    }

    private void fillDepth(DepthCaptureState.DepthFrame depthFrame) {
        if (depthFrame == null || depthFrame.data == null) {
            throw new IllegalArgumentException("Missing depth frame");
        }
        FloatBuffer depthBuffer = depthFrame.data;

        // --- Pass 2: Fill depth (Y-flipped from GL bottom-up) ---
        if (linearizeDepth && depthFrame.encoding != DepthCaptureState.Encoding.LINEAR_WORLD_METERS) {
            float znear = depthFrame.zNear;
            float zfar = depthFrame.zFar;
            float twoZnZf = 2.0f * znear * zfar;
            float zfMinusZn = zfar - znear;
            float zfPlusZn = zfar + znear;

            for (int y = 0; y < height; y++) {
                int dstIdx = y * width;
                int srcY = height - 1 - y;
                for (int x = 0; x < width; x++, dstIdx++) {
                    float depth = depthBuffer.get(srcY * width + x);
                    if (depthFrame.encoding == DepthCaptureState.Encoding.REVERSED_NDC) {
                        depth = 1.0f - depth;
                    }
                    depth = twoZnZf / (zfPlusZn - (2.0f * depth - 1.0f) * zfMinusZn);
                    zBuf.put(dstIdx, depth);
                }
            }
        } else {
            for (int y = 0; y < height; y++) {
                int dstIdx = y * width;
                int srcY = height - 1 - y;
                for (int x = 0; x < width; x++, dstIdx++) {
                    float depth = depthBuffer.get(srcY * width + x);
                    if (depthFrame.encoding == DepthCaptureState.Encoding.REVERSED_NDC) {
                        depth = 1.0f - depth;
                    }
                    zBuf.put(dstIdx, depth);
                }
            }
        }

    }

    /**
     * Writes the current buffer contents to an EXR file.
     * Reconfigures header/image each call (Init zeros fields; we re-apply).
     */
    private void writeExr(int frameNumber) throws IOException {
        Path filePath = outputDir.resolve(String.format("%04d.exr", frameNumber));

        // Reset header/image to zero (InitEXR does memset), then re-apply config
        TinyEXR.InitEXRHeader(header);
        TinyEXR.InitEXRImage(image);

        header.channels(channelInfo);
        header.num_channels(NUM_CHANNELS);
        header.pixel_types(pixelTypes);
        header.requested_pixel_types(requestedTypes);
        if (sceneLinearHdr) {
            header.num_custom_attributes(1);
            header.custom_attributes(customAttributes);
        }
        header.compression_type(compressionType);

        image.width(width);
        image.height(height);
        image.num_channels(NUM_CHANNELS);
        imagePtrs.rewind();
        image.images(imagePtrs);

        ByteBuffer pathBuf = MemoryUtil.memUTF8(filePath.toAbsolutePath().toString());
        PointerBuffer err = MemoryUtil.memAllocPointer(1);
        try {
            long tinyExrStarted = System.nanoTime();
            int result = TinyEXR.SaveEXRImageToFile(image, header, pathBuf, err);
            long tinyExrNanos = System.nanoTime() - tinyExrStarted;
            if (result != 0) {
                long errAddr = err.get(0);
                String error = errAddr != 0 ? MemoryUtil.memUTF8(errAddr) : "unknown error";
                throw new IOException("tinyexr SaveEXRImageToFile failed: " + error + " (code " + result + ")");
            }
            long fileSize = Files.exists(filePath) ? Files.size(filePath) : -1L;
            ExportPerformanceTrace.tinyExr(frameNumber, tinyExrNanos, fileSize,
                    compressionType, filePath.toString());
        } finally {
            MemoryUtil.memFree(err);
            MemoryUtil.memFree(pathBuf);
        }
    }

    public int getFrameCount() { return frameCount; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        // Free all pre-allocated native memory
        MemoryUtil.memFree(rBuf);
        MemoryUtil.memFree(gBuf);
        MemoryUtil.memFree(bBuf);
        MemoryUtil.memFree(aBuf);
        MemoryUtil.memFree(zBuf);
        MemoryUtil.memFree(imagePtrs);
        MemoryUtil.memFree(pixelTypes);
        MemoryUtil.memFree(requestedTypes);
        for (ByteBuffer b : nameBufs) MemoryUtil.memFree(b);
        for (ByteBuffer b : customAttributeBuffers) MemoryUtil.memFree(b);
        if (customAttributes != null) customAttributes.free();
        channelInfo.free();
        image.free();
        header.free();
    }
}
