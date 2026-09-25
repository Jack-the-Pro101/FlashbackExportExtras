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

//? if legacy_hdr {

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import com.mojang.blaze3d.systems.RenderSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/** HDR color transform implemented with the stable LWJGL OpenGL API. */
public class HdrColorTransformShader implements AutoCloseable {
    private int texture = -1, fbo = -1, program = -1, vao = -1, vbo = -1;
    private int width = -1, height = -1;

    private static String resource(String path) {
        try (InputStream in = HdrColorTransformShader.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing HDR shader resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read HDR shader resource: " + path, e);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("HDR shader compilation failed: " + log);
        }
        return shader;
    }

    private void ensureResources(int w, int h) {
        if (program >= 0 && width == w && height == h) return;
        close();
        width = w; height = h;

        int vertex = compile(GL20.GL_VERTEX_SHADER, resource("/assets/flashbackexportextras/shaders/core/screenquad_flip.vsh"));
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, resource("/assets/flashbackexportextras/shaders/core/hdr_color_transform.fsh"));
        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, fragment);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 1, "UV0");
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            throw new IllegalStateException("HDR shader linking failed: " + GL20.glGetProgramInfoLog(program));

        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, (ByteBuffer) null);

        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("HDR framebuffer is incomplete");
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        float[] quad = {0,0,0, 0,0, 1,0,0, 1,0, 1,1,0, 1,1, 0,1,0, 0,1};
        FloatBuffer data = org.lwjgl.system.MemoryUtil.memAllocFloat(20).put(quad).flip();
        vao = GL30.glGenVertexArrays(); vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 20, 0);
        GL20.glEnableVertexAttribArray(1); GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 12);
        GL30.glBindVertexArray(0); org.lwjgl.system.MemoryUtil.memFree(data);
    }

    public int render(int srcTextureId, float peakBrightness, int transferFunction) {
        RenderSystem.assertOnRenderThread();
        try (LegacyOpenGlRenderState state = LegacyOpenGlRenderState.capture()) {
            state.bindTextureForInspection(srcTextureId);
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (w <= 0 || h <= 0) throw new IllegalStateException("Invalid HDR source texture size");
            ensureResources(w, h);
            state.beginFullscreenPass(fbo, width, height, program, srcTextureId, vao);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "InSampler"), 0);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "UiBrightness"), peakBrightness);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Primaries"), 6);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "TransferFunction"), transferFunction);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
        }
        return texture;
    }

    @Override public void close() {
        if (program >= 0) GL20.glDeleteProgram(program); if (vbo >= 0) GL15.glDeleteBuffers(vbo);
        if (vao >= 0) GL30.glDeleteVertexArrays(vao); if (fbo >= 0) GL30.glDeleteFramebuffers(fbo);
        if (texture >= 0) GL11.glDeleteTextures(texture);
        program = vbo = vao = fbo = texture = -1;
    }
}
//?}
