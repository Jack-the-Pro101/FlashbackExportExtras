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

//? if mc_26_1_2 {
/*

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtras;
import com.rethinkqaq.flashbackexportextras.exporting.HdrVideoCaptureState;
import com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrCaptureState;
import org.lwjgl.system.MemoryUtil;
import xyz.rrtt217.HDRMod.core.ColorTransformRenderer;
import xyz.rrtt217.HDRMod.util.Enums;
import xyz.rrtt217.HDRMod.util.TextureUpgradeUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// 26.1-only bridge to HDR Mod's Blaze3D color transform and 16-bit readback hooks.
final class HdrMod26_1ExportBridge {
    private static final int BUFFER_COUNT = 3;
    private static final int GL_RGB16 = 32859;
    private static final int GL_RGBA16F = 34842;
    private static final int GL_UNSIGNED_SHORT = 5123;
    private static final int GL_HALF_FLOAT = 5131;
    private static final long WAIT_NANOS = 1_000_000_000L;

    private final ReadbackStream hdr10 = new ReadbackStream(
            "HDR10", HdrVideoCaptureState::submit, HdrVideoCaptureState::fail);
    private final ReadbackStream sceneLinear = new ReadbackStream(
            "scene-linear HDR", SceneLinearHdrCaptureState::submit, SceneLinearHdrCaptureState::fail);
    private ColorTransformRenderer hdr10Renderer;
    private ColorTransformRenderer sceneLinearRenderer;
    private int width;
    private int height;

    void captureHdr(RenderTarget target, int captureWidth, int captureHeight,
                    float peakBrightness, long frameId, Enums.TransferFunction transferFunction) {
        RenderSystem.assertOnRenderThread();
        ensureSize(captureWidth, captureHeight);
        if (hdr10Renderer == null) {
            hdr10Renderer = new ColorTransformRenderer(target, "Flashback Export Extras HDR");
            ((HdrModColorTransformAccess) hdr10Renderer)
                    .flashbackexportextras$configureOutput(GL_RGB16, GL_UNSIGNED_SHORT);
        } else if (hdr10Renderer.getSrcTarget() != target) {
            hdr10Renderer.setSrcTarget(target);
            ((HdrModColorTransformAccess) hdr10Renderer)
                    .flashbackexportextras$configureOutput(GL_RGB16, GL_UNSIGNED_SHORT);
        }
        hdr10Renderer.updateColorTransformUniforms(peakBrightness, 0.0f,
                Enums.Primaries.BT2020, transferFunction);
        hdr10Renderer.render();
        hdr10.issue(hdr10Renderer.getDstTexture(), captureWidth, captureHeight,
                frameId, GL_UNSIGNED_SHORT);
    }

    // Backwards compatibility - defaults to PQ (HDR10)
    void captureHdr(RenderTarget target, int captureWidth, int captureHeight,
                    float peakBrightness, long frameId) {
        captureHdr(target, captureWidth, captureHeight, peakBrightness, frameId, 11);
    }

    // Overload that accepts transfer function as int (11 = PQ, 12 = S-Log3)
    void captureHdr(RenderTarget target, int captureWidth, int captureHeight,
                    float peakBrightness, long frameId, int transferFunction) {
        Enums.TransferFunction tf;
        if (transferFunction == 12) {
            // Try to use S-Log3 if available in HDR Mod
            try {
                tf = Enums.TransferFunction.valueOf("SLOG3");
            } catch (IllegalArgumentException e) {
                // SLOG3 not available in this HDR Mod version, fall back to PQ
                com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.warn(
                        "S-Log3 not available in HDR Mod, falling back to PQ (HDR10)");
                tf = Enums.TransferFunction.ST2084_PQ;
            }
        } else {
            tf = Enums.TransferFunction.ST2084_PQ;
        }
        captureHdr(target, captureWidth, captureHeight, peakBrightness, frameId, tf);
    }

    void captureSceneLinear(RenderTarget target, int captureWidth, int captureHeight, long frameId) {
        RenderSystem.assertOnRenderThread();
        ensureSize(captureWidth, captureHeight);
        if (sceneLinearRenderer == null) {
            sceneLinearRenderer = new ColorTransformRenderer(target, "Flashback Export Extras scene-linear HDR");
            ((HdrModColorTransformAccess) sceneLinearRenderer)
                    .flashbackexportextras$configureOutput(GL_RGBA16F, GL_HALF_FLOAT);
        } else if (sceneLinearRenderer.getSrcTarget() != target) {
            sceneLinearRenderer.setSrcTarget(target);
            ((HdrModColorTransformAccess) sceneLinearRenderer)
                    .flashbackexportextras$configureOutput(GL_RGBA16F, GL_HALF_FLOAT);
        }
        sceneLinearRenderer.updateColorTransformUniforms(1.0f, 0.0f,
                Enums.Primaries.SRGB, Enums.TransferFunction.UNSPECIFIED);
        sceneLinearRenderer.render();
        sceneLinear.issue(sceneLinearRenderer.getDstTexture(), captureWidth, captureHeight,
                frameId, GL_HALF_FLOAT);
    }

    void collectReady() {
        hdr10.collectReady(0L);
        sceneLinear.collectReady(0L);
    }

    void flush() {
        hdr10.flush();
        sceneLinear.flush();
    }

    boolean release() {
        if (!hdr10.release() || !sceneLinear.release()) return false;
        if (hdr10Renderer != null) hdr10Renderer.close();
        if (sceneLinearRenderer != null) sceneLinearRenderer.close();
        hdr10Renderer = null;
        sceneLinearRenderer = null;
        width = height = 0;
        return true;
    }

    private void ensureSize(int captureWidth, int captureHeight) {
        if (width == captureWidth && height == captureHeight) return;
        flush();
        if (!hdr10.release() || !sceneLinear.release()) {
            throw new IllegalStateException("26.1 HDR resources are still in use during resize");
        }
        if (hdr10Renderer != null) hdr10Renderer.close();
        if (sceneLinearRenderer != null) sceneLinearRenderer.close();
        hdr10Renderer = null;
        sceneLinearRenderer = null;
        width = captureWidth;
        height = captureHeight;
    }

    private static final class ReadbackStream {
        private final String label;
        private final BiConsumer<Long, ByteBuffer> frameConsumer;
        private final Consumer<Throwable> failureConsumer;
        private final GpuBuffer[] buffers = new GpuBuffer[BUFFER_COUNT];
        private final GpuFence[] fences = new GpuFence[BUFFER_COUNT];
        private final long[] frameIds = new long[BUFFER_COUNT];
        private int writeIndex;
        private int width;
        private int height;

        private ReadbackStream(String label, BiConsumer<Long, ByteBuffer> frameConsumer,
                               Consumer<Throwable> failureConsumer) {
            this.label = label;
            this.frameConsumer = frameConsumer;
            this.failureConsumer = failureConsumer;
        }

        void issue(com.mojang.blaze3d.textures.GpuTexture texture,
                   int captureWidth, int captureHeight, long frameId, int readPixelFormat) {
            ensureBuffers(captureWidth, captureHeight);
            collectReady(0L);
            int index = writeIndex;
            if (fences[index] != null && !collect(index, WAIT_NANOS)) {
                throw new IllegalStateException("Timed out waiting for 26.1 HDR readback buffer " + index);
            }

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try {
                TextureUpgradeUtils.setTargetReadPixelFormat(readPixelFormat);
                encoder.copyTextureToBuffer(texture, buffers[index], 0L, () -> {}, 0);
            } finally {
                TextureUpgradeUtils.resetTargetReadPixelFormat();
            }
            frameIds[index] = frameId;
            fences[index] = encoder.createFence();
            writeIndex = (writeIndex + 1) % BUFFER_COUNT;
        }

        void collectReady(long timeoutNanos) {
            for (int i = 0; i < BUFFER_COUNT; i++) {
                if (fences[i] != null) collect(i, timeoutNanos);
            }
        }

        void flush() {
            for (int i = 0; i < BUFFER_COUNT; i++) {
                if (fences[i] != null && !collect(i, WAIT_NANOS)) {
                    throw new IllegalStateException("Timed out flushing 26.1 HDR frame " + frameIds[i]);
                }
            }
        }

        boolean release() {
            for (GpuFence fence : fences) {
                if (fence != null && !fence.awaitCompletion(0L)) return false;
            }
            for (int i = 0; i < BUFFER_COUNT; i++) {
                if (fences[i] != null) fences[i].close();
                if (buffers[i] != null) buffers[i].close();
                fences[i] = null;
                buffers[i] = null;
                frameIds[i] = -1L;
            }
            writeIndex = 0;
            width = height = 0;
            return true;
        }

        private void ensureBuffers(int captureWidth, int captureHeight) {
            if (width == captureWidth && height == captureHeight && buffers[0] != null) return;
            flush();
            if (!release()) throw new IllegalStateException("26.1 HDR buffers are still in use");
            width = captureWidth;
            height = captureHeight;
            long size = (long) captureWidth * captureHeight * 8L;
            for (int i = 0; i < BUFFER_COUNT; i++) {
                buffers[i] = RenderSystem.getDevice().createBuffer(
                        () -> "Flashback Export Extras 26.1 " + label + " readback",
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
                frameIds[i] = -1L;
            }
        }

        private boolean collect(int index, long timeoutNanos) {
            GpuFence fence = fences[index];
            if (fence == null) return true;
            if (!fence.awaitCompletion(timeoutNanos)) return false;
            ByteBuffer result = null;
            try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                    .mapBuffer(buffers[index], true, false)) {
                int expected = width * height * 8;
                ByteBuffer source = mapped.data().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                source.rewind();
                if (source.remaining() < expected) {
                    throw new IllegalStateException("26.1 HDR readback is too small: "
                            + source.remaining() + " < " + expected);
                }
                source.limit(expected);
                result = MemoryUtil.memAlloc(expected);
                // HDR Mod's 26.1 screenshot path also reverses rows after
                // readback. Its screen-quad shader preserves texture Y, while
                // OpenGL returns the destination bottom-up.
                int rowBytes = width * 8;
                for (int y = height - 1; y >= 0; y--) {
                    ByteBuffer row = source.duplicate();
                    row.position(y * rowBytes);
                    row.limit((y + 1) * rowBytes);
                    result.put(row);
                }
                result.rewind();
                frameConsumer.accept(frameIds[index], result);
                result = null;
            } catch (RuntimeException e) {
                failureConsumer.accept(e);
                FlashbackExportExtras.LOGGER.error("26.1 {} readback failed for frame {}", label, frameIds[index], e);
                throw e;
            } finally {
                if (result != null) MemoryUtil.memFree(result);
                fence.close();
                fences[index] = null;
                frameIds[index] = -1L;
            }
            return true;
        }
    }
}

*///?}
