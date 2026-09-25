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

//? if <26.1 {

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.rethinkqaq.flashbackexportextras.exporting.DepthCaptureState;
import com.rethinkqaq.flashbackexportextras.exporting.DepthFrameCapture;

/** Transitional backend for pre-26.1 versions. */
public final class LegacyOpenGlExportBackend implements GpuExportBackend {
    private DepthFrameCapture depthCapture;
    /*? if hdr {*/
    private com.rethinkqaq.flashbackexportextras.exporting.HdrColorTransformShader hdrShader;
    private com.rethinkqaq.flashbackexportextras.exporting.HdrFrameCapture hdrCapture;
    private com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrShader sceneLinearShader;
    private com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrPboCapture sceneLinearCapture;
    private boolean hdrReadbackFailed;
    private boolean sceneLinearReadbackFailed;
    /*?}*/

    @Override public boolean supportsHdr() {
        /*? if hdr {*/
        return true;
        /*?} else {*/
        /*return false;
        *//*?}*/
    }
    @Override public boolean supportsSceneLinearHdr() {
        /*? if hdr {*/
        return true;
        /*?} else {*/
        /*return false;
        *//*?}*/
    }
    @Override
    public void captureDepth(RenderTarget target, int width, int height, float depthFar, long frameId) {
        if (target == null || !target.useDepth) {
            throw new IllegalStateException("Legacy depth target is unavailable for frame " + frameId);
        }
        if (depthCapture == null) depthCapture = new DepthFrameCapture();
        int textureId = depthTextureId(target);
        if (textureId <= 0) {
            throw new IllegalStateException("Legacy depth texture is unavailable for frame " + frameId);
        }
        depthCapture.issueReadback(textureId, width, height, frameId,
                0.05f, depthFar, DepthCaptureState.Encoding.STANDARD_NDC,
                "Minecraft legacy depth");
    }

    @Override
    public void captureHdr(RenderTarget target, int width, int height,
                           float peakBrightness, long frameId) {
        // Default to PQ (HDR10) for backwards compatibility
        captureHdr(target, width, height, peakBrightness, frameId, 11);
    }

    @Override
    public void captureHdr(RenderTarget target, int width, int height,
                           float peakBrightness, long frameId, int transferFunction) {
        /*? if hdr {*/
        if (target == null || hdrReadbackFailed) return;
        try {
            if (hdrShader == null) hdrShader = new com.rethinkqaq.flashbackexportextras.exporting.HdrColorTransformShader();
            if (hdrCapture == null) hdrCapture = new com.rethinkqaq.flashbackexportextras.exporting.HdrFrameCapture();
            int hdrTexture = hdrShader.render(colorTextureId(target), peakBrightness, transferFunction);
            hdrCapture.issueReadback(hdrTexture, width, height, frameId);
        } catch (RuntimeException e) {
            hdrReadbackFailed = true;
            com.rethinkqaq.flashbackexportextras.exporting.HdrVideoCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.error(
                    "OpenGL HDR capture failed for frame " + frameId, e);
        }
        /*?}*/
    }

    @Override
    public void captureSceneLinearHdr(RenderTarget target, int width, int height, long frameId) {
        /*? if hdr {*/
        if (target == null || sceneLinearReadbackFailed) return;
        try {
            if (sceneLinearShader == null) sceneLinearShader = new com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrShader();
            if (sceneLinearCapture == null) sceneLinearCapture = new com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrPboCapture();
            int linearTexture = sceneLinearShader.render(colorTextureId(target));
            sceneLinearCapture.issue(linearTexture, width, height, frameId);
        } catch (RuntimeException e) {
            sceneLinearReadbackFailed = true;
            com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.error(
                    "OpenGL scene-linear HDR capture failed for frame " + frameId, e);
        }
        /*?}*/
    }

    @Override public void endFrame() {
        if (depthCapture != null) depthCapture.collectReady(0L);
        /*? if hdr {*/
        if (hdrCapture != null) hdrCapture.collectReady(0L);
        if (sceneLinearCapture != null) sceneLinearCapture.collectReady(0L);
        /*?}*/
    }

    @Override public void flush() {
        if (depthCapture != null) depthCapture.flush();
        /*? if hdr {*/
        if (hdrCapture != null) hdrCapture.flush();
        if (sceneLinearCapture != null) sceneLinearCapture.flush();
        /*?}*/
    }

    @Override public boolean releaseOnRenderThread() {
        if (!RenderSystem.isOnRenderThread()) return false;
        if (depthCapture != null && !depthCapture.release()) return false;
        /*? if hdr {*/
        if (hdrCapture != null && !hdrCapture.release()) return false;
        if (sceneLinearCapture != null && !sceneLinearCapture.release()) return false;
        /*?}*/
        close();
        return true;
    }

    @Override public void close() {
        if (depthCapture != null) depthCapture.close();
        /*? if hdr {*/
        if (hdrCapture != null) hdrCapture.close();
        if (sceneLinearCapture != null) sceneLinearCapture.close();
        if (hdrShader != null) hdrShader.close();
        if (sceneLinearShader != null) sceneLinearShader.close();
        hdrCapture = null;
        sceneLinearCapture = null;
        hdrShader = null;
        sceneLinearShader = null;
        depthCapture = null;
        hdrReadbackFailed = false;
        sceneLinearReadbackFailed = false;
        /*?}*/
    }

    /*? if hdr {*/
    private static int colorTextureId(RenderTarget target) {
        /*? if >=1.21.5 {*/
        /*return ((com.mojang.blaze3d.opengl.GlTexture) target.getColorTexture()).glId();
        *//*?} else {*/
        return target.getColorTextureId();
        /*?}*/
    }
    /*?}*/

    private static int depthTextureId(RenderTarget target) {
        /*? if >=1.21.5 {*/
        /*return ((com.mojang.blaze3d.opengl.GlTexture) target.getDepthTexture()).glId();
        *//*?} else {*/
        return target.getDepthTextureId();
        /*?}*/
    }
}
//?}
