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

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Backend boundary for GPU work performed during Flashback export. */
public interface GpuExportBackend extends AutoCloseable {
    boolean supportsHdr();

    default boolean supportsSceneLinearHdr() { return false; }

    /** Captures the numbered frame before the game clears the main depth attachment. */
    void captureDepth(RenderTarget target, int width, int height, float depthFar, long frameId);

    /**
     * Queues a depth capture on the encoder which is about to clear the source
     * attachment. Modern renderers must preserve this ordering: a separately
     * submitted encoder may otherwise run after the clear and read only its
     * uniform clear value. Older backends intentionally ignore the encoder.
     */
    default void captureDepthBeforeClear(Object clearEncoder, RenderTarget target, int width, int height,
                                         float depthFar, long frameId) {
        captureDepth(target, width, height, depthFar, frameId);
    }

    /** Queues an RGBA16 BT.2020/PQ capture for the matching export frame. */
    default void captureHdr(RenderTarget target, int width, int height,
                            float peakBrightness, long frameId) {
        captureHdr(target, width, height, peakBrightness, frameId, 11);
    }

    /** Queues an HDR capture with the specified transfer function.
     * @param transferFunction 11 = PQ (HDR10/ST.2084), 12 = S-Log3 */
    default void captureHdr(RenderTarget target, int width, int height,
                            float peakBrightness, long frameId, int transferFunction) {
        captureHdr(target, width, height, peakBrightness, frameId);
    }

    /** Queues an RGBA16F scene-linear Rec.709 capture for the matching export frame. */
    default void captureSceneLinearHdr(RenderTarget target, int width, int height, long frameId) {}

    void endFrame();

    /** Drain pending GPU readback before the EXR writer is finalized. */
    default void flush() { endFrame(); }

    /**
     * Releases GPU objects from the render thread. Returning {@code false}
     * keeps this backend queued until outstanding GPU work completes.
     */
    default boolean releaseOnRenderThread() {
        close();
        return true;
    }

    @Override
    void close();
}
