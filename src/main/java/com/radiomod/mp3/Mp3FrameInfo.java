/*
 * minimp3-java: a Java port of minimp3 by lieff, https://github.com/lieff/minimp3
 * Translated from minimp3.h at commit 142823796ab8efa55e1bf11406028f3be77ecceb.
 *
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

/**
 * Information about the frame found by {@link Mp3Decoder#decodeFrame}: the equivalent of
 * {@code mp3dec_frame_info_t}.
 *
 * <p>As in the original, {@link #frameBytes} is written by every call. The other fields are written only
 * when a frame header is found; when the call returns because no frame was found they keep the values
 * they had before the call.
 */
public final class Mp3FrameInfo {
    /**
     * {@code frame_bytes}: how many bytes the caller must skip to reach the next frame. It includes any
     * data skipped before the frame ({@link #frameOffset}). 0 means that more data is needed.
     */
    public int frameBytes;
    /** {@code frame_offset}: the offset of the frame header from the start of the data passed in. */
    public int frameOffset;
    /** {@code channels}: 1 for mono, 2 for the other modes. */
    public int channels;
    /** {@code hz}: the sample rate, from 8000 to 48000. */
    public int hz;
    /** {@code layer}: the MPEG layer (1, 2 or 3). Only Layer III frames are decoded. */
    public int layer;
    /** {@code bitrate_kbps}: the bit rate from the header; 0 for free-format frames. */
    public int bitrateKbps;

    /** Creates an object with all fields set to 0. */
    public Mp3FrameInfo() {
    }
}
