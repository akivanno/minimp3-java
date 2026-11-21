/*
 * minimp3-java: a Java port of minimp3 by lieff, https://github.com/lieff/minimp3
 * Translated from minimp3.h at commit 142823796ab8efa55e1bf11406028f3be77ecceb.
 *
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

/**
 * A pure-Java MP3 (MPEG-1/2/2.5 Layer III) decoder, ported from
 * <a href="https://github.com/lieff/minimp3">minimp3</a>.
 *
 * <p>{@link com.radiomod.mp3.Mp3Decoder} decodes one frame per call, like {@code mp3dec_decode_frame};
 * {@link com.radiomod.mp3.Mp3FrameInfo} describes the frame found.
 */
package com.radiomod.mp3;
