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
package com.rethinkqaq.flashbackexportextras.gpu;

//? if >=26.1 {

/*

import com.mojang.blaze3d.pipeline.RenderTarget;
//? if >=26.2 {
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.shaders.UniformType;
//?}
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
//? if >=26.2 {
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.resources.ResourceLocation;
//?}
import com.rethinkqaq.flashbackexportextras.exporting.DepthCaptureState;
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtrasConfig;
import com.rethinkqaq.flashbackexportextras.exporting.HdrExportState;
import com.rethinkqaq.flashbackexportextras.exporting.HdrVideoCaptureState;
import com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrCaptureState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

// Blaze3D boundary for 26.x; resource implementation is version-specific.
public final class Blaze3dExportBackend implements GpuExportBackend {
    private static final int BUFFER_COUNT = 3;
    private final GpuBuffer[] depthBuffers = new GpuBuffer[BUFFER_COUNT];
    private final GpuFence[] depthFences = new GpuFence[BUFFER_COUNT];
    private final long[] depthFrameIds = new long[BUFFER_COUNT];
    private final float[] depthNear = new float[BUFFER_COUNT];
    private final float[] depthFarValues = new float[BUFFER_COUNT];
    private final DepthCaptureState.Encoding[] depthEncodings = new DepthCaptureState.Encoding[BUFFER_COUNT];
    private final String[] depthSources = new String[BUFFER_COUNT];
    //? if <26.2 {
    private HdrMod26_1ExportBridge hdrModBridge;
    //?}
    private int writeIndex;
    private int width;
    private int height;
    private boolean depthReadbackFailed;
    private int depthDebugFrame;
    //? if >=26.2 {
    private GpuTexture hdrCopyTexture;
    private GpuTextureView hdrCopyView;
    private RenderPipeline hdrCopyPipeline;
    private final GpuBuffer[] hdrReadbackBuffers = new GpuBuffer[BUFFER_COUNT];
    private final GpuFence[] hdrReadbackFences = new GpuFence[BUFFER_COUNT];
    private final long[] hdrReadbackFrameIds = new long[BUFFER_COUNT];
    private GpuBuffer hdrUniformBuffer;
    private int hdrWriteIndex;
    private int hdrWidth;
    private int hdrHeight;
    private boolean hdrReadbackFailed;
    private long hdrStagingWaitCount;
    private long hdrLongestWaitNanos;
    private GpuTexture sceneLinearTexture;
    private GpuTextureView sceneLinearView;
    private RenderPipeline sceneLinearPipeline;
    private final GpuBuffer[] sceneLinearBuffers = new GpuBuffer[BUFFER_COUNT];
    private final GpuFence[] sceneLinearFences = new GpuFence[BUFFER_COUNT];
    private final long[] sceneLinearFrameIds = new long[BUFFER_COUNT];
    private int sceneLinearWriteIndex;
    private int sceneLinearWidth;
    private int sceneLinearHeight;
    private boolean sceneLinearReadbackFailed;
    private GpuTexture depthCopyTexture;
    private GpuTextureView depthCopyView;
    private RenderPipeline depthCopyPipeline;
    private GpuBuffer depthUniformBuffer;
    //?}
    @Override public boolean supportsHdr() {
        //? if >=26.2 {
        return true;
        //?}
        //? if <26.2 {
        return HdrExportState.isHdrModLoaded();
        //?}
    }
    @Override public boolean supportsSceneLinearHdr() {
        //? if >=26.2 {
        return true;
        //?}
        //? if <26.2 {
        return HdrExportState.isHdrModLoaded();
        //?}
    }
    @Override
    public void captureDepth(RenderTarget target, int width, int height, float depthFar, long frameId) {
        captureDepthInternal(null, target, width, height, depthFar, frameId);
    }

    @Override
    public void captureDepthBeforeClear(Object clearEncoder, RenderTarget target, int width, int height,
                                        float depthFar, long frameId) {
        captureDepthInternal(clearEncoder instanceof CommandEncoder encoder ? encoder : null,
                target, width, height, depthFar, frameId);
    }

    private void captureDepthInternal(CommandEncoder clearEncoder, RenderTarget target,
                                      int width, int height, float depthFar, long frameId) {
        if (target == null || !target.useDepth || depthReadbackFailed) {
            throw new IllegalStateException("Blaze3D depth target is unavailable for frame " + frameId);
        }
        try {
            RenderSystem.assertOnRenderThread();
            if (!ensureDepthBuffers(width, height)) {
                throw new IllegalStateException("Unable to allocate depth readback buffers");
            }
            collectDepth();
            int index = writeIndex;
            if (depthFences[index] != null) {
                if (!collectDepth(index, 1_000_000_000L)) {
                    throw new IllegalStateException("Timed out waiting for depth frame "
                            + depthFrameIds[index]);
                }
            }

            CommandEncoder encoder = clearEncoder != null
                    ? clearEncoder : RenderSystem.getDevice().createCommandEncoder();
            boolean ownsEncoder = clearEncoder == null;
            //? if >=26.2 {
            GpuTexture texture = target.getDepthTexture();
            GpuTextureView textureView = target.getDepthTextureView();
            if (texture == null || textureView == null) {
                throw new IllegalStateException("Missing 26.2 depth texture view");
            }
            boolean reversed = !com.rethinkqaq.flashbackexportextras.exporting.IrisDepthCaptureState
                    .isShaderPackPipelineActive();
            captureDepth26_2(encoder, texture, textureView, width, height, depthFar, frameId, index,
                    reversed, reversed ? "Minecraft 26.2 reversed-Z depth"
                            : "Iris shaderpack main depth (standard-Z)");
            if (ownsEncoder) encoder.submit();
            //?}
            //? if <26.2 {
            GpuTexture texture = target.getDepthTexture();
            if (texture == null) throw new IllegalStateException("Missing 26.1 depth texture");
            encoder.copyTextureToBuffer(texture, depthBuffers[index], 0L, () -> {}, 0);
            depthFrameIds[index] = frameId;
            depthNear[index] = 0.05f;
            depthFarValues[index] = depthFar;
            depthEncodings[index] = DepthCaptureState.Encoding.STANDARD_NDC;
            depthSources[index] = "Minecraft 26.1 depth";
            depthFences[index] = encoder.createFence();
            //? if >=26.2 {
            encoder.submit();
            //?}
            //?}
            writeIndex = (writeIndex + 1) % BUFFER_COUNT;
        } catch (RuntimeException e) {
            depthReadbackFailed = true;
            closeDepthBuffers();
            com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.error(
                    "Blaze3D depth readback failed for frame " + frameId, e);
            throw e;
        }
    }

    //? if >=26.2 {
    private void captureDepth26_2(CommandEncoder encoder, GpuTexture sourceTexture, GpuTextureView sourceView,
                                    int captureWidth, int captureHeight, float depthFar,
                                    long frameId, int index, boolean reversed, String source) {
        if (sourceTexture == null || sourceView == null) {
            throw new IllegalStateException("Missing source depth texture");
        }
        ensureDepthTarget(captureWidth, captureHeight);
        boolean linearize = FlashbackExportExtrasConfig.INSTANCE.depthLinearizeWorldSpace;
        ByteBuffer parameters = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        parameters.putFloat(0.05f).putFloat(depthFar)
                .putFloat(reversed ? 1.0f : 0.0f)
                .putFloat(linearize ? 1.0f : 0.0f).flip();
        encoder.writeToBuffer(depthUniformBuffer.slice(), parameters);
        try (RenderPass pass = encoder.createRenderPass(
                () -> "Flashback Export Extras depth transform", depthCopyView,
                java.util.Optional.empty())) {
            pass.setPipeline(depthCopyPipeline);
            pass.bindTexture("InDepth", sourceView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setUniform("DepthParameters", depthUniformBuffer);
            pass.draw(3, 1, 0, 0);
        }
        encoder.copyTextureToBuffer(depthCopyTexture, depthBuffers[index], 0L, () -> {}, 0);
        depthFrameIds[index] = frameId;
        depthNear[index] = 0.05f;
        depthFarValues[index] = depthFar;
        depthEncodings[index] = linearize
                ? DepthCaptureState.Encoding.LINEAR_WORLD_METERS
                : DepthCaptureState.Encoding.STANDARD_NDC;
        depthSources[index] = source;
        depthFences[index] = encoder.createFence();
    }
    //?}

    @Override
    public void captureHdr(RenderTarget target, int width, int height,
                           float peakBrightness, long frameId) {
        // Default to PQ (HDR10) for backwards compatibility
        captureHdr(target, width, height, peakBrightness, frameId, 11);
    }

    public void captureHdr(RenderTarget target, int width, int height,
                           float peakBrightness, long frameId, int transferFunction) {
        //? if >=26.2 {
        if (target == null || target.getColorTexture() == null || target.getColorTextureView() == null
                || hdrReadbackFailed) return;
        try {
            RenderSystem.assertOnRenderThread();
            ensureHdrTarget(width, height);
            collectHdrReady(0L);
            int index = hdrWriteIndex;
            if (hdrReadbackFences[index] != null) {
                long waitStarted = System.nanoTime();
                boolean complete = collectHdr(index, 1_000_000_000L);
                hdrStagingWaitCount++;
                hdrLongestWaitNanos = Math.max(hdrLongestWaitNanos, System.nanoTime() - waitStarted);
                if (!complete) {
                    throw new IllegalStateException("Timed out waiting for HDR frame "
                            + hdrReadbackFrameIds[index]);
                }
            }
            // std140 layout: float PeakBrightness (4 bytes) + int TransferFunction (4 bytes) = 8 bytes, padded to 16
            ByteBuffer parameters = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
            parameters.putFloat(peakBrightness);
            parameters.putInt(transferFunction);
            parameters.putFloat(0.0f).putFloat(0.0f).flip(); // padding to 16 bytes

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToBuffer(hdrUniformBuffer.slice(), parameters);
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Flashback Export Extras HDR colour transform", hdrCopyView, java.util.Optional.empty())) {
                pass.setPipeline(hdrCopyPipeline);
                pass.bindTexture("InSampler", target.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.setUniform("HdrParameters", hdrUniformBuffer);
                pass.draw(3, 1, 0, 0);
            }
            encoder.copyTextureToBuffer(hdrCopyTexture, hdrReadbackBuffers[index], 0L, () -> {}, 0);
            hdrReadbackFrameIds[index] = frameId;
            hdrReadbackFences[index] = encoder.createFence();
            encoder.submit();
            hdrWriteIndex = (hdrWriteIndex + 1) % BUFFER_COUNT;
        } catch (RuntimeException e) {
            hdrReadbackFailed = true;
            HdrVideoCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.error(
                    "Blaze3D HDR capture failed for frame " + frameId, e);
        }
        //?}
        //? if <26.2 {
        if (target == null || target.getColorTexture() == null || target.getColorTextureView() == null) return;
        try {
            if (hdrModBridge == null) hdrModBridge = new HdrMod26_1ExportBridge();
            // Pass transfer function as int (11 = PQ, 12 = S-Log3); bridge handles enum conversion
            hdrModBridge.captureHdr(target, width, height, peakBrightness, frameId, transferFunction);
        } catch (RuntimeException e) {
            HdrVideoCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.error(
                    "26.1 HDR capture failed for frame " + frameId, e);
        }
        //?}
    }

    @Override
    public void captureSceneLinearHdr(RenderTarget target, int width, int height, long frameId) {
        //? if >=26.2 {
        if (target == null || target.getColorTexture() == null
                || target.getColorTextureView() == null || sceneLinearReadbackFailed) return;
        try {
            RenderSystem.assertOnRenderThread();
            ensureSceneLinearTarget(width, height);
            collectSceneLinearReady(0L);

            int index = sceneLinearWriteIndex;
            if (sceneLinearFences[index] != null
                    && !collectSceneLinear(index, 1_000_000_000L)) {
                throw new IllegalStateException(
                        "Timed out waiting for scene-linear HDR buffer " + index);
            }

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Flashback Export Extras scene-linear HDR transform",
                    sceneLinearView, java.util.Optional.empty())) {
                pass.setPipeline(sceneLinearPipeline);
                pass.bindTexture("InSampler", target.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            encoder.copyTextureToBuffer(sceneLinearTexture, sceneLinearBuffers[index], 0L, () -> {}, 0);
            sceneLinearFrameIds[index] = frameId;
            sceneLinearFences[index] = encoder.createFence();
            encoder.submit();
            sceneLinearWriteIndex = (sceneLinearWriteIndex + 1) % BUFFER_COUNT;
        } catch (RuntimeException e) {
            sceneLinearReadbackFailed = true;
            SceneLinearHdrCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.error(
                    "Blaze3D scene-linear HDR capture failed for frame " + frameId, e);
        }
        //?}
        //? if <26.2 {
        if (target == null || target.getColorTexture() == null || target.getColorTextureView() == null) return;
        try {
            if (hdrModBridge == null) hdrModBridge = new HdrMod26_1ExportBridge();
            hdrModBridge.captureSceneLinear(target, width, height, frameId);
        } catch (RuntimeException e) {
            SceneLinearHdrCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.error(
                    "26.1 scene-linear HDR capture failed for frame " + frameId, e);
        }
        //?}
    }

    //? if >=26.2 {
    private void ensureHdrTarget(int newWidth, int newHeight) {
        if (hdrCopyTexture != null && hdrWidth == newWidth && hdrHeight == newHeight) return;
        flushHdr();
        if (!closeHdrTarget()) {
            throw new IllegalStateException("HDR10 resources are still in use");
        }
        long size = (long) newWidth * newHeight * 8L;
        hdrWidth = newWidth;
        hdrHeight = newHeight;
        hdrCopyTexture = RenderSystem.getDevice().createTexture(
                "Flashback Export Extras HDR10 colour transform",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA16_UNORM, newWidth, newHeight, 1, 1);
        hdrCopyView = RenderSystem.getDevice().createTextureView(hdrCopyTexture);
        for (int i = 0; i < BUFFER_COUNT; i++) {
            hdrReadbackBuffers[i] = RenderSystem.getDevice().createBuffer(
                    () -> "Flashback Export Extras HDR10 readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
            hdrReadbackFrameIds[i] = -1L;
        }
        hdrUniformBuffer = RenderSystem.getDevice().createBuffer(
                () -> "Flashback Export Extras HDR10 parameters",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, 16L);
        hdrCopyPipeline = RenderPipeline.builder()
                // String locations default to minecraft:, which Iris treats as
                // an overridable vanilla program. Keep our utility pipeline
                // in this mod's namespace.
                .withLocation(ResourceLocation.fromNamespaceAndPath(
                        "flashbackexportextras", "hdr_color_transform_blaze"))
                .withVertexShader(ResourceLocation.fromNamespaceAndPath(
                        "flashbackexportextras", "core/flashbackexportextras_hdr_color_transform_blaze"))
                .withFragmentShader(ResourceLocation.fromNamespaceAndPath(
                        "flashbackexportextras", "core/flashbackexportextras_hdr_color_transform_blaze"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withSampler("InSampler")
                        .withUniform("HdrParameters", UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(new ColorTargetState(java.util.Optional.empty(), GpuFormat.RGBA16_UNORM,
                        ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
    }

    private boolean closeHdrTarget() {
        for (GpuFence fence : hdrReadbackFences) {
            if (fence != null && !fence.awaitCompletion(0L)) return false;
        }
        if (hdrUniformBuffer != null) hdrUniformBuffer.close();
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (hdrReadbackFences[i] != null) hdrReadbackFences[i].close();
            if (hdrReadbackBuffers[i] != null) hdrReadbackBuffers[i].close();
            hdrReadbackFences[i] = null;
            hdrReadbackBuffers[i] = null;
            hdrReadbackFrameIds[i] = -1L;
        }
        if (hdrCopyView != null) hdrCopyView.close();
        if (hdrCopyTexture != null) hdrCopyTexture.close();
        hdrUniformBuffer = null;
        hdrCopyView = null;
        hdrCopyTexture = null;
        hdrCopyPipeline = null;
        hdrWidth = hdrHeight = 0;
        hdrWriteIndex = 0;
        return true;
    }

    private void collectHdrReady(long timeoutNanos) {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (hdrReadbackFences[i] != null) collectHdr(i, timeoutNanos);
        }
    }

    private boolean collectHdr(int index, long timeoutNanos) {
        GpuFence fence = hdrReadbackFences[index];
        if (fence == null) return true;
        if (!fence.awaitCompletion(timeoutNanos)) return false;
        try (GpuBufferSlice.MappedView mapped = hdrReadbackBuffers[index].slice().map(true, false)) {
            int expected = hdrWidth * hdrHeight * 8;
            ByteBuffer source = mapped.data().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            source.rewind();
            if (source.remaining() < expected) {
                throw new IllegalStateException("HDR10 readback is too small: "
                        + source.remaining() + " < " + expected);
            }
            source.limit(expected);
            ByteBuffer result = MemoryUtil.memAlloc(expected);
            try {
                result.put(source);
                result.rewind();
                HdrVideoCaptureState.submit(hdrReadbackFrameIds[index], result);
                result = null;
            } finally {
                if (result != null) MemoryUtil.memFree(result);
            }
        } finally {
            fence.close();
            hdrReadbackFences[index] = null;
            hdrReadbackFrameIds[index] = -1L;
        }
        return true;
    }

    private void flushHdr() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (hdrReadbackFences[i] != null && !collectHdr(i, 1_000_000_000L)) {
                throw new IllegalStateException("Timed out flushing HDR10 frame "
                        + hdrReadbackFrameIds[i]);
            }
        }
        if (hdrStagingWaitCount > 0L) {
            com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.info(
                    "26.2 HDR10 async readback: staging waits={}, longest={} ms",
                    hdrStagingWaitCount, hdrLongestWaitNanos / 1_000_000.0);
        }
    }

    private void ensureDepthTarget(int newWidth, int newHeight) {
        if (depthCopyTexture != null && depthCopyTexture.getWidth(0) == newWidth
                && depthCopyTexture.getHeight(0) == newHeight) return;
        closeDepthTarget();
        depthCopyTexture = RenderSystem.getDevice().createTexture(
                "Flashback Export Extras depth transform",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.R32_FLOAT, newWidth, newHeight, 1, 1);
        depthCopyView = RenderSystem.getDevice().createTextureView(depthCopyTexture);
        depthUniformBuffer = RenderSystem.getDevice().createBuffer(
                () -> "Flashback Export Extras depth parameters",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, 16L);
        depthCopyPipeline = RenderPipeline.builder()
                .withLocation(ResourceLocation.fromNamespaceAndPath(
                        "flashbackexportextras", "depth_copy_blaze"))
                .withVertexShader(ResourceLocation.fromNamespaceAndPath(
                        "flashbackexportextras", "core/flashbackexportextras_depth_copy"))
                .withFragmentShader(ResourceLocation.fromNamespaceAndPath(
                        "flashbackexportextras", "core/flashbackexportextras_depth_copy"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withSampler("InDepth")
                        .withUniform("DepthParameters", UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(new ColorTargetState(java.util.Optional.empty(),
                        GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
    }

    private void closeDepthTarget() {
        if (depthUniformBuffer != null) depthUniformBuffer.close();
        if (depthCopyView != null) depthCopyView.close();
        if (depthCopyTexture != null) depthCopyTexture.close();
        depthUniformBuffer = null;
        depthCopyView = null;
        depthCopyTexture = null;
        depthCopyPipeline = null;
    }

    private void ensureSceneLinearTarget(int newWidth, int newHeight) {
        if (sceneLinearTexture != null && sceneLinearWidth == newWidth
                && sceneLinearHeight == newHeight) return;
        flushSceneLinearHdr();
        if (!closeSceneLinearTarget()) {
            throw new IllegalStateException("Scene-linear HDR resources are still in use");
        }

        sceneLinearWidth = newWidth;
        sceneLinearHeight = newHeight;
        long size = (long) newWidth * newHeight * 8L;
        sceneLinearTexture = RenderSystem.getDevice().createTexture(
                "Flashback Export Extras scene-linear HDR transform",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA16_FLOAT, newWidth, newHeight, 1, 1);
        sceneLinearView = RenderSystem.getDevice().createTextureView(sceneLinearTexture);
        for (int i = 0; i < BUFFER_COUNT; i++) {
            sceneLinearBuffers[i] = RenderSystem.getDevice().createBuffer(
                    () -> "Flashback Export Extras scene-linear HDR readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
            sceneLinearFrameIds[i] = -1L;
        }
        sceneLinearPipeline = RenderPipeline.builder()
                .withLocation(ResourceLocation.fromNamespaceAndPath(
                        "flashbackexportextras", "scene_linear_hdr_blaze"))
                .withVertexShader(ResourceLocation.fromNamespaceAndPath(
                        "flashbackexportextras", "core/flashbackexportextras_hdr_color_transform_blaze"))
                .withFragmentShader(ResourceLocation.fromNamespaceAndPath(
                        "flashbackexportextras", "core/flashbackexportextras_scene_linear_hdr_blaze"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withSampler("InSampler")
                        .build())
                .withColorTargetState(new ColorTargetState(java.util.Optional.empty(),
                        GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
    }

    private void collectSceneLinearReady(long timeoutNanos) {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (sceneLinearFences[i] != null) collectSceneLinear(i, timeoutNanos);
        }
    }

    private boolean collectSceneLinear(int index, long timeoutNanos) {
        GpuFence fence = sceneLinearFences[index];
        if (fence == null) return true;
        if (!fence.awaitCompletion(timeoutNanos)) return false;
        try (GpuBufferSlice.MappedView mapped = sceneLinearBuffers[index].slice().map(true, false)) {
            int expected = sceneLinearWidth * sceneLinearHeight * 8;
            ByteBuffer source = mapped.data().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            source.rewind();
            if (source.remaining() < expected) {
                throw new IllegalStateException("Scene-linear HDR readback is too small: "
                        + source.remaining() + " < " + expected);
            }
            source.limit(expected);
            ByteBuffer result = MemoryUtil.memAlloc(expected);
            try {
                result.put(source);
                result.rewind();
                SceneLinearHdrCaptureState.submit(sceneLinearFrameIds[index], result);
                result = null;
            } finally {
                if (result != null) MemoryUtil.memFree(result);
            }
        } finally {
            fence.close();
            sceneLinearFences[index] = null;
            sceneLinearFrameIds[index] = -1L;
        }
        return true;
    }

    private void flushSceneLinearHdr() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (sceneLinearFences[i] != null
                    && !collectSceneLinear(i, 1_000_000_000L)) {
                throw new IllegalStateException("Timed out flushing scene-linear HDR frame "
                        + sceneLinearFrameIds[i]);
            }
        }
    }

    private boolean closeSceneLinearTarget() {
        for (GpuFence fence : sceneLinearFences) {
            if (fence != null && !fence.awaitCompletion(0L)) return false;
        }
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (sceneLinearFences[i] != null) sceneLinearFences[i].close();
            if (sceneLinearBuffers[i] != null) sceneLinearBuffers[i].close();
            sceneLinearFences[i] = null;
            sceneLinearBuffers[i] = null;
            sceneLinearFrameIds[i] = -1L;
        }
        if (sceneLinearView != null) sceneLinearView.close();
        if (sceneLinearTexture != null) sceneLinearTexture.close();
        sceneLinearView = null;
        sceneLinearTexture = null;
        sceneLinearPipeline = null;
        sceneLinearWidth = sceneLinearHeight = 0;
        sceneLinearWriteIndex = 0;
        return true;
    }
    //?}
    private boolean ensureDepthBuffers(int newWidth, int newHeight) {
        if (width == newWidth && height == newHeight && depthBuffers[0] != null) return true;
        if (!closeDepthBuffers()) {
            com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.warn(
                    "Deferring depth-buffer resize until pending GPU copies complete");
            return false;
        }
        width = newWidth;
        height = newHeight;
        long size = (long) newWidth * newHeight * 4L;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            depthBuffers[i] = RenderSystem.getDevice().createBuffer(
                    () -> "Flashback Export Extras depth readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                    size);
        }
        return true;
    }

    private void collectDepth() {
        int index = (writeIndex + BUFFER_COUNT - 1) % BUFFER_COUNT;
        collectDepth(index, 0L);
    }

    private boolean collectDepth(int index, long timeoutNanos) {
        GpuFence fence = depthFences[index];
        if (fence == null) return true;
        if (!fence.awaitCompletion(timeoutNanos)) return false;
        //? if >=26.2 {
        try (GpuBufferSlice.MappedView mapped = depthBuffers[index].slice().map(true, false)) {
            ByteBuffer data = mapped.data().duplicate();
            // GPU readback buffers use little-endian byte order. A duplicated
            // ByteBuffer defaults to BIG_ENDIAN in Java, corrupting every
            // float sample (1.0f becomes 4.6006E-41).
            data.order(ByteOrder.LITTLE_ENDIAN);
            data.rewind();
            var copy = DepthCaptureState.acquireBuffer();
            java.nio.FloatBuffer source = data.asFloatBuffer();
            copy.put(source);
            copy.rewind();
            logDepthReadback(source, copy, index);
            DepthCaptureState.submit(new DepthCaptureState.DepthFrame(depthFrameIds[index], copy,
                    depthNear[index], depthFarValues[index], depthEncodings[index], depthSources[index]));
        } finally {
            fence.close();
            depthFences[index] = null;
            depthFrameIds[index] = -1L;
            depthEncodings[index] = null;
            depthSources[index] = null;
        }
        //?}
        //? if <26.2 {
        try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(depthBuffers[index], true, false)) {
                ByteBuffer data = mapped.data().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                data.rewind();
                var copy = DepthCaptureState.acquireBuffer();
                java.nio.FloatBuffer source = data.asFloatBuffer();
                copy.put(source);
                copy.rewind();
                logDepthReadback(source, copy, index);
                DepthCaptureState.submit(new DepthCaptureState.DepthFrame(depthFrameIds[index], copy,
                        depthNear[index], depthFarValues[index], depthEncodings[index], depthSources[index]));
        } finally {
            fence.close();
            depthFences[index] = null;
            depthFrameIds[index] = -1L;
            depthEncodings[index] = null;
            depthSources[index] = null;
        }
        //?}
        return true;
    }

    private boolean closeDepthBuffers() {
        boolean pending = false;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (depthFences[i] != null && !depthFences[i].awaitCompletion(0L)) {
                pending = true;
                continue;
            }
            if (depthFences[i] != null) depthFences[i].close();
            if (depthBuffers[i] != null) depthBuffers[i].close();
            depthFences[i] = null;
            depthBuffers[i] = null;
            depthFrameIds[i] = -1L;
            depthNear[i] = 0.05f;
            depthFarValues[i] = 0.0f;
            depthEncodings[i] = null;
            depthSources[i] = null;
        }
        if (!pending) writeIndex = 0;
        return !pending;
    }

    @Override public void endFrame() {
        collectDepth();
        //? if >=26.2 {
        collectHdrReady(0L);
        collectSceneLinearReady(0L);
        //?}
        //? if <26.2 {
        if (hdrModBridge != null) hdrModBridge.collectReady();
        //?}
    }

    @Override
    public void flush() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (depthFences[i] != null) collectDepth(i, 1_000_000_000L);
        }
        //? if >=26.2 {
        flushHdr();
        flushSceneLinearHdr();
        //?}
        //? if <26.2 {
        if (hdrModBridge != null) hdrModBridge.flush();
        //?}
    }
    @Override public boolean releaseOnRenderThread() {
        if (!RenderSystem.isOnRenderThread()) return false;
        if (!closeDepthBuffers()) return false;
        //? if >=26.2 {
        if (!closeSceneLinearTarget()) return false;
        if (!closeHdrTarget()) return false;
        closeDepthTarget();
        //?}
        //? if <26.2 {
        if (hdrModBridge != null && !hdrModBridge.release()) return false;
        hdrModBridge = null;
        //?}
        return true;
    }

    @Override public void close() {
        releaseOnRenderThread();
    }

    private void logDepthReadback(java.nio.FloatBuffer raw, java.nio.FloatBuffer converted, int bufferIndex) {
        if (!Boolean.getBoolean("flashbackexportextras.debugDepth")) return;
        int frame = depthDebugFrame++;
        if (frame >= 3 && frame % 30 != 0) return;

        float rawMin = Float.POSITIVE_INFINITY;
        float rawMax = Float.NEGATIVE_INFINITY;
        float convertedMin = Float.POSITIVE_INFINITY;
        float convertedMax = Float.NEGATIVE_INFINITY;
        int rawFinite = 0;
        int convertedFinite = 0;
        int count = converted.remaining();
        for (int i = 0; i < count; i++) {
            float rawValue = raw.get(i);
            if (Float.isFinite(rawValue)) {
                rawMin = Math.min(rawMin, rawValue);
                rawMax = Math.max(rawMax, rawValue);
                rawFinite++;
            }
            float convertedValue = converted.get(i);
            if (Float.isFinite(convertedValue)) {
                convertedMin = Math.min(convertedMin, convertedValue);
                convertedMax = Math.max(convertedMax, convertedValue);
                convertedFinite++;
            }
        }

        int center = Math.max(0, Math.min(count - 1, (height / 2) * width + width / 2));
        int quarter = Math.max(0, Math.min(count - 1, (height / 4) * width + width / 4));
        int thirdQuarter = Math.max(0, count - 1 - quarter);
        com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.info(
                "Blaze3D depth readback #{}: source={}, buffer={}, size={}x{}, "
                        + "raw[finite={}/{}, min={}, max={}, q1={}, center={}, q3={}], "
                        + "standard[finite={}/{}, min={}, max={}, q1={}, center={}, q3={}]",
                frame, depthSources[bufferIndex], bufferIndex, width, height,
                rawFinite, count, rawMin, rawMax,
                raw.get(quarter), raw.get(center), raw.get(thirdQuarter),
                convertedFinite, count, convertedMin, convertedMax,
                converted.get(quarter), converted.get(center), converted.get(thirdQuarter));
    }
}
*/
//?}
